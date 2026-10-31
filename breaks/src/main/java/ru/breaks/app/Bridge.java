package ru.breaks.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

/**
 * Мост «сайт → телефон»: сайт видит объект window.BreaksApp и через него показывает уведомления
 * и ставит напоминания о своих перерывах. Ничего, кроме уведомлений, мост не умеет.
 */
final class Bridge {
    private final Context ctx;
    Bridge(Context c) { ctx = c.getApplicationContext(); }

    @JavascriptInterface public String version() { return BuildConfigHolder.VERSION; }

    @JavascriptInterface public void notify(String title, String text) {
        Notifier.show(ctx, (int) (System.currentTimeMillis() & 0x7fffffff), safe(title), safe(text));
    }

    @JavascriptInterface public void schedule(String key, double atMs, String title, String text) {
        Notifier.schedule(ctx, safe(key), (long) atMs, safe(title), safe(text));
    }

    @JavascriptInterface public void cancelAll() { Notifier.cancelAll(ctx); }

    private static String safe(String s) { return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s); }
}
