package ru.dashboard.app;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Оболочка для дашбордов на Google Apps Script.
 * Все данные по-прежнему берутся из Google Таблиц через опубликованный веб-скрипт;
 * приложение только показывает сайт в собственном окне.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "dashboard";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_URL = "url_";

    private WebView web;
    private ProgressBar progress;
    private View picker, offline;
    private LinearLayout pickerList;
    private SharedPreferences prefs;

    private final List<String> names = new ArrayList<>();
    private final List<String> defaultUrls = new ArrayList<>();

    private ValueCallback<Uri[]> fileCallback;
    private ActivityResultLauncher<Intent> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        picker = findViewById(R.id.picker);
        offline = findViewById(R.id.offline);
        pickerList = findViewById(R.id.pickerList);
        findViewById(R.id.retry).setOnClickListener(v -> {
            offline.setVisibility(View.GONE);
            web.reload();
        });

        filePicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData()));
                fileCallback = null;
            }
        });

        loadConfig();
        setupWebView();
        buildPicker();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { onBack(); }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            int sel = prefs.getInt(KEY_SELECTED, -1);
            String url = sel >= 0 ? urlFor(sel) : "";
            if (sel >= 0 && !url.isEmpty()) open(sel); else showPicker();
        }
    }

    // ---------- Конфигурация ----------

    private void loadConfig() {
        try (InputStream in = getAssets().open("dashboards.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray arr = new JSONObject(bos.toString(StandardCharsets.UTF_8.name())).getJSONArray("dashboards");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                names.add(o.optString("name", "Дашборд " + (i + 1)));
                defaultUrls.add(o.optString("url", "").trim());
            }
        } catch (Exception e) {
            names.add("Дашборд");
            defaultUrls.add("");
        }
    }

    /** Ссылка, сохранённая пользователем, важнее ссылки из dashboards.json. */
    private String urlFor(int i) {
        String saved = prefs.getString(KEY_URL + i, "");
        return !saved.isEmpty() ? saved : defaultUrls.get(i);
    }

    // ---------- Экран выбора ----------

    private void buildPicker() {
        // оставляем заголовок и подсказку, остальное перестраиваем
        while (pickerList.getChildCount() > 2) pickerList.removeViewAt(2);
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            TextView item = new TextView(this);
            String sub = urlFor(i).isEmpty() ? "\nСсылка не задана — нажмите, чтобы добавить" : "";
            item.setText(names.get(i) + sub);
            item.setTextColor(ContextCompat.getColor(this, R.color.fg));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            item.setTypeface(Typeface.DEFAULT_BOLD);
            int p = dp(18);
            item.setPadding(p, p, p, p);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ContextCompat.getColor(this, R.color.card));
            bg.setCornerRadius(dp(14));
            item.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                if (urlFor(idx).isEmpty()) askUrl(idx, true); else open(idx);
            });
            item.setOnLongClickListener(v -> { askUrl(idx, false); return true; });
            pickerList.addView(item);
        }
    }

    private void showPicker() {
        buildPicker();
        picker.setVisibility(View.VISIBLE);
        offline.setVisibility(View.GONE);
    }

    private void askUrl(int idx, boolean openAfter) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://script.google.com/macros/s/…/exec");
        input.setText(urlFor(idx));
        FrameLayout box = new FrameLayout(this);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(names.get(idx))
                .setMessage("Вставьте ссылку на опубликованный дашборд (заканчивается на /exec).")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (!u.startsWith("https://script.google.com/")) {
                        Toast.makeText(this, "Ссылка должна начинаться с https://script.google.com/", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putString(KEY_URL + idx, u).apply();
                    buildPicker();
                    if (openAfter) open(idx);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void open(int idx) {
        prefs.edit().putInt(KEY_SELECTED, idx).apply();
        picker.setVisibility(View.GONE);
        offline.setVisibility(View.GONE);
        web.clearHistory();
        web.loadUrl(urlFor(idx));
    }

    // ---------- WebView ----------

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // вход запоминается (localStorage)
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);    // ссылки target=_blank открываются здесь же
        s.setMediaPlaybackRequiresUserGesture(true);

        // Тёмную тему рисует сам дашборд (prefers-color-scheme), автозатемнение отключаем.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true); // Apps Script работает внутри iframe googleusercontent.com

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ((scheme.equals("http") || scheme.equals("https")) && isInternal(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    offline.setVisibility(View.VISIBLE);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            // Импорт Excel / CSAT: выбор файла на телефоне
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = params.createIntent();
                i.setType("*/*"); // xlsx/csv иногда не распознаются по MIME — разрешаем любые
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    filePicker.launch(Intent.createChooser(i, "Выберите файл"));
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("http")) {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    String name = URLUtil.guessFileName(url, cd, mime);
                    r.setMimeType(mime);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) r.addRequestHeader("Cookie", cookie);
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    Toast.makeText(this, "Скачивание: " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            } else {
                Toast.makeText(this, "Этот файл можно скачать только в браузере", Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.equals("script.google.com")
                || host.endsWith(".googleusercontent.com")
                || host.equals("accounts.google.com")
                || host.equals("docs.google.com");
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Кнопка «Назад» ----------

    private void onBack() {
        if (picker.getVisibility() == View.VISIBLE) {
            if (prefs.getInt(KEY_SELECTED, -1) >= 0 && web.getUrl() != null) {
                picker.setVisibility(View.GONE);
            } else {
                finish();
            }
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        String[] items = {"Обновить страницу", "Сменить дашборд", "Выйти"};
        new AlertDialog.Builder(this)
                .setTitle(names.get(Math.max(0, Math.min(prefs.getInt(KEY_SELECTED, 0), names.size() - 1))))
                .setItems(items, (d, w) -> {
                    if (w == 0) { offline.setVisibility(View.GONE); web.reload(); }
                    else if (w == 1) showPicker();
                    else finish();
                })
                .show();
    }

    // ---------- Жизненный цикл ----------

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
