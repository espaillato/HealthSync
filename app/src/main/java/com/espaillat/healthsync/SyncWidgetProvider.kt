package com.espaillat.healthsync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget. Purely a status display + launcher shortcut — it does NOT trigger a
 * sync itself. Actual background sync is owned by the once-a-day WorkManager schedule (see
 * SyncWorker.schedulePeriodicSync), so the widget's periodic onUpdate (widget_info.xml) is a
 * cheap local SharedPreferences read + RemoteViews update, not a network wakeup.
 */
class SyncWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val syncState = SyncState(context)

        val statusText = when (syncState.lastSyncStatus) {
            SyncStatus.SUCCESS -> syncState.lastSyncTimestamp?.toDisplayString()
                ?.let { context.getString(R.string.widget_last_sync, it) }
                ?: context.getString(R.string.widget_subtitle)
            SyncStatus.ERROR -> context.getString(R.string.widget_error)
            SyncStatus.NEVER -> context.getString(R.string.widget_subtitle)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_sync)
            views.setTextViewText(R.id.widget_status, statusText)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
