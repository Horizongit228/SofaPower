package com.sofa.power;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofaPowerWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_WAKE = "com.sofa.power.ACTION_WAKE_WIDGET";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateWidget(context, manager, id, "Нажми, чтобы включить ПК");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_WAKE.equals(intent.getAction())) return;
        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            String message;
            try {
                SecureStore store = new SecureStore(context);
                String mac = WolUtils.normalizeMac(store.getString(SecureStore.KEY_MAC, ""));
                if (mac == null) message = "Открой SofaPower и настрой ПК";
                else {
                    WolUtils.send(context, mac);
                    message = "Команда отправлена ⚡";
                }
            } catch (Exception e) {
                message = "Нет домашней сети";
            }
            updateAll(context, message);
            pendingResult.finish();
        });
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int id, String subtitle) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sofapower);
        views.setTextViewText(R.id.widget_subtitle, subtitle);

        Intent wake = new Intent(context, SofaPowerWidgetProvider.class).setAction(ACTION_WAKE);
        PendingIntent wakePending = PendingIntent.getBroadcast(
                context, 1101, wake, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_button, wakePending);

        Intent open = new Intent(context, ModernActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 1102, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_title, openPending);
        manager.updateAppWidget(id, views);
    }

    public static void updateAll(Context context, String subtitle) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, SofaPowerWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) updateWidget(context, manager, id, subtitle);
    }
}
