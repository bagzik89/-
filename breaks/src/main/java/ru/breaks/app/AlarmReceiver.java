package ru.breaks.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Срабатывает в запланированное время и показывает уведомление «Через 5 минут обед» и т. п. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Notifier.show(c, i.getIntExtra("id", 1), i.getStringExtra("title"), i.getStringExtra("text"));
    }
}
