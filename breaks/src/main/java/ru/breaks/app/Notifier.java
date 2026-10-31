package ru.breaks.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.HashSet;
import java.util.Set;

/** Уведомления и запланированные напоминания (AlarmManager — срабатывают и при закрытом приложении). */
final class Notifier {
    static final String CHANNEL = "breaks";
    private static final String PREFS = "alarms";

    private Notifier() { }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Перерывы и заявки", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Напоминания о перерывах, заявки на обмен");
            ch.enableVibration(true);
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    static void show(Context c, int id, String title, String text) {
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try { NotificationManagerCompat.from(c).notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    /** Напоминание на время atMs (мс с 1970). key — постоянный ключ, повторный вызов с тем же ключом заменяет напоминание. */
    static void schedule(Context c, String key, long atMs, String title, String text) {
        if (atMs <= System.currentTimeMillis()) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(c, key, title, text);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> keys = new HashSet<>(p.getStringSet("keys", new HashSet<>()));
        keys.add(key);
        p.edit().putStringSet("keys", keys).apply();
    }

    /** Снять все запланированные напоминания (сайт перед новым расписанием вызывает это и ставит заново). */
    static void cancelAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String k : p.getStringSet("keys", new HashSet<>())) am.cancel(pending(c, k, "", ""));
        p.edit().remove("keys").apply();
    }

    private static PendingIntent pending(Context c, String key, String title, String text) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("ru.breaks.app.REMIND." + key)
                .putExtra("title", title).putExtra("text", text).putExtra("id", key.hashCode());
        return PendingIntent.getBroadcast(c, key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
