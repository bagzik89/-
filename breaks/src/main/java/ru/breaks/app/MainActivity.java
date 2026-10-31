package ru.breaks.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Приложение «Перерывы»: сайт «Расписание перерывов» (Google Apps Script) в отдельном окне
 * + уведомления телефона: напоминание за 5 минут до перерыва и в его начало (даже при закрытом
 * приложении), заявки на обмен. Данные — те же, что на сайте, обновлять приложение при изменениях сайта не нужно.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "breaks";
    private static final String KEY_URL = "url";

    private WebView web;
    private ProgressBar progress;
    private View offline;
    private SharedPreferences prefs;
    private String defaultUrl = "";
    private ActivityResultLauncher<String> notifPermission;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        offline = findViewById(R.id.offline);
        findViewById(R.id.picker).setVisibility(View.GONE);
        findViewById(R.id.retry).setOnClickListener(v -> { offline.setVisibility(View.GONE); web.reload(); });

        Notifier.ensureChannel(this);
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) Toast.makeText(this, "Без разрешения на уведомления напоминаний о перерывах не будет", Toast.LENGTH_LONG).show();
        });
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        loadConfig();
        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { onBack(); }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else if (url().isEmpty()) askUrl();
        else web.loadUrl(url());
    }

    private void loadConfig() {
        try (InputStream in = getAssets().open("breaks.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            defaultUrl = new JSONObject(bos.toString(StandardCharsets.UTF_8.name())).optString("url", "").trim();
        } catch (Exception ignored) { }
    }

    /** Ссылка, введённая на телефоне, важнее ссылки, зашитой в приложение. */
    private String url() {
        String saved = prefs.getString(KEY_URL, "");
        return !saved.isEmpty() ? saved : defaultUrl;
    }

    private void askUrl() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://script.google.com/macros/s/…/exec");
        input.setText(url());
        FrameLayout box = new FrameLayout(this);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Ссылка на сайт")
                .setMessage("Вставьте ссылку на «Расписание перерывов» — её даёт супервайзер (заканчивается на /exec).")
                .setView(box)
                .setCancelable(!url().isEmpty())
                .setPositiveButton("Открыть", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (!u.startsWith("https://script.google.com/")) {
                        Toast.makeText(this, "Ссылка должна начинаться с https://script.google.com/", Toast.LENGTH_LONG).show();
                        if (url().isEmpty()) askUrl();
                        return;
                    }
                    prefs.edit().putString(KEY_URL, u).apply();
                    web.clearHistory();
                    web.loadUrl(u);
                })
                .setNegativeButton(url().isEmpty() ? "Выйти" : "Отмена", (d, w) -> { if (url().isEmpty()) finish(); })
                .show();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // вход запоминается (localStorage сайта)
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false); // звук уведомлений сайта
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false); // тёмную тему рисует сам сайт
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true); // Apps Script работает внутри iframe googleusercontent.com

        // Мост для уведомлений: window.BreaksApp на сайте.
        web.addJavascriptInterface(new Bridge(this), "BreaksApp");

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
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { progress.setVisibility(View.VISIBLE); }
            @Override public void onPageFinished(WebView view, String url) { progress.setVisibility(View.GONE); CookieManager.getInstance().flush(); }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) { progress.setVisibility(View.GONE); offline.setVisibility(View.VISIBLE); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.equals("script.google.com") || host.endsWith(".googleusercontent.com") || host.equals("accounts.google.com");
    }

    private void openExternal(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show(); }
    }

    private void onBack() {
        if (web.canGoBack()) { web.goBack(); return; }
        String[] items = {"Обновить страницу", "Изменить ссылку на сайт", "Свернуть"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(items, (d, w) -> {
                    if (w == 0) { offline.setVisibility(View.GONE); web.reload(); }
                    else if (w == 1) askUrl();
                    else moveTaskToBack(true); // не закрываем: так сайт продолжает следить за перерывами
                })
                .show();
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }

    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
}
