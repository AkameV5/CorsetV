package com.akamev.corset.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.akamev.corset.CorsetApplication
import com.akamev.corset.MainActivity
import com.akamev.corset.R
import java.util.Locale

class CorsetAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_FORCE_UPDATE -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {
        const val ACTION_FORCE_UPDATE = "com.akamev.corset.widget.ACTION_FORCE_UPDATE"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, CorsetAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val app = context.applicationContext as? CorsetApplication
            val views = RemoteViews(context.packageName, R.layout.widget_corset_layout)

            // Intent to open MainActivity on widget tap
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

            if (app == null) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val bluetoothController = app.container.bluetoothController
            val deviceState = bluetoothController.deviceState.value
            val currentAngle = bluetoothController.lastTelemetry?.angle
            val isConnected = deviceState.isConnected && deviceState.hasSavedDevice

            if (isConnected) {
                // Connected / Paired: Orange widget matching app theme
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.bg_widget_card_orange)
                views.setTextColor(R.id.widget_brand, Color.parseColor("#FFFFFF"))
                views.setTextColor(R.id.widget_angle_label, Color.parseColor("#FFE2D1"))
                views.setTextColor(R.id.widget_battery_text, Color.parseColor("#FFFFFF"))
                views.setInt(R.id.widget_battery_icon, "setColorFilter", Color.parseColor("#FFFFFF"))

                val angleText = if (currentAngle != null) {
                    String.format(Locale.getDefault(), "%.1f°", currentAngle)
                } else {
                    "--°"
                }
                views.setTextViewText(R.id.widget_angle_value, angleText)
                views.setTextColor(R.id.widget_angle_value, Color.parseColor("#FFFFFF"))

                val batteryText = deviceState.batteryLevel?.let { "$it%" } ?: "--%"
                views.setTextViewText(R.id.widget_battery_text, batteryText)
            } else {
                // Not connected / Not paired: Black widget
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.bg_widget_card_black)
                views.setTextColor(R.id.widget_brand, Color.parseColor("#94A3B8"))
                views.setTextColor(R.id.widget_angle_label, Color.parseColor("#64748B"))
                views.setTextColor(R.id.widget_battery_text, Color.parseColor("#94A3B8"))
                views.setInt(R.id.widget_battery_icon, "setColorFilter", Color.parseColor("#64748B"))

                views.setTextViewText(R.id.widget_angle_value, "--°")
                views.setTextColor(R.id.widget_angle_value, Color.parseColor("#64748B"))

                val batteryText = deviceState.batteryLevel?.let { "$it%" } ?: "--%"
                views.setTextViewText(R.id.widget_battery_text, batteryText)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
