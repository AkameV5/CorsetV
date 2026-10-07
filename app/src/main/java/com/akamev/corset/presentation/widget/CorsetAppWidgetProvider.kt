package com.akamev.corset.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.Toast
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
            ACTION_CALIBRATE -> {
                handleCalibrate(context)
            }
            ACTION_FORCE_UPDATE -> {
                updateAllWidgets(context)
            }
        }
    }

    private fun handleCalibrate(context: Context) {
        val app = context.applicationContext as? CorsetApplication ?: return
        val bluetoothController = app.container.bluetoothController

        if (bluetoothController.deviceState.value.isConnected) {
            bluetoothController.writeCommand("SET")
            app.container.appPreferences.setCalibrationDone(true)
            app.container.appPreferences.clearBaselineAngle()
            Toast.makeText(context, context.getString(R.string.widget_calibrated_toast), Toast.LENGTH_SHORT).show()
            updateAllWidgets(context)
        } else {
            // If device is not connected, open app so user can connect
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openIntent)
        }
    }

    companion object {
        const val ACTION_CALIBRATE = "com.akamev.corset.widget.ACTION_CALIBRATE"
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

            // Intent to calibrate on button tap
            val calibrateIntent = Intent(context, CorsetAppWidgetProvider::class.java).apply {
                action = ACTION_CALIBRATE
            }
            val calibratePendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                calibrateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_calibrate, calibratePendingIntent)

            if (app == null) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val bluetoothController = app.container.bluetoothController
            val deviceState = bluetoothController.deviceState.value
            val currentAngle = bluetoothController.lastTelemetry?.angle

            // 1. Connection status
            if (!deviceState.hasSavedDevice) {
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.widget_status_no_device))
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.ic_status_disconnected)
                views.setTextViewText(R.id.widget_battery_text, "--%")
                views.setTextViewText(R.id.widget_angle_value, "--°")
                views.setTextColor(R.id.widget_angle_value, Color.parseColor("#B0A8A0"))
                views.setTextViewText(R.id.widget_posture_label, context.getString(R.string.widget_status_no_device))
            } else if (!deviceState.isConnected) {
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.widget_status_offline))
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.ic_status_disconnected)
                views.setTextViewText(
                    R.id.widget_battery_text,
                    deviceState.batteryLevel?.let { "$it%" } ?: "--%",
                )
                views.setTextViewText(R.id.widget_angle_value, "--°")
                views.setTextColor(R.id.widget_angle_value, Color.parseColor("#B0A8A0"))
                views.setTextViewText(R.id.widget_posture_label, context.getString(R.string.widget_status_offline))
            } else {
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.widget_status_online))
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.ic_status_connected)
                views.setTextViewText(
                    R.id.widget_battery_text,
                    deviceState.batteryLevel?.let { "$it%" } ?: "--%",
                )

                if (currentAngle != null) {
                    val angle = currentAngle.toFloat()
                    val angleText = String.format(Locale.getDefault(), "%.1f°", angle)
                    views.setTextViewText(R.id.widget_angle_value, angleText)

                    val (colorHex, postureLabelRes) = when {
                        angle <= 5.0f -> Pair("#4CAF50", R.string.widget_posture_good)
                        angle <= 8.0f -> Pair("#FFA726", R.string.widget_posture_warning)
                        else -> Pair("#E74C3C", R.string.widget_posture_bad)
                    }

                    views.setTextColor(R.id.widget_angle_value, Color.parseColor(colorHex))
                    views.setTextViewText(R.id.widget_posture_label, context.getString(postureLabelRes))
                } else {
                    views.setTextViewText(R.id.widget_angle_value, "--°")
                    views.setTextColor(R.id.widget_angle_value, Color.parseColor("#4CAF50"))
                    views.setTextViewText(R.id.widget_posture_label, context.getString(R.string.widget_posture_waiting))
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
