package com.akamev.corset.data.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.akamev.corset.CorsetApplication
import com.akamev.corset.MainActivity
import com.akamev.corset.R
import com.akamev.corset.data.wear.WearStateSnapshot
import com.akamev.corset.data.wear.WearSyncController
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.domain.model.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class BluetoothLeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: CorsetApplication

    private var observersStarted = false
    private var currentDeviceState = DeviceState()
    private var lastTelemetryAt: Long? = null
    private var badPostureStartedAt: Long? = null
    private var lastPostureAlertAt: Long? = null
    private var lastCompletedSessionEndAt: Long? = null
    private var latestDeviation: Float? = null
    private val recentAngles = ArrayDeque<Float>()
    private lateinit var wearSyncController: WearSyncController

    override fun onCreate() {
        super.onCreate()
        app = application as CorsetApplication
        wearSyncController = WearSyncController(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!app.container.bluetoothController.hasSavedDevice()) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentDeviceState = app.container.bluetoothController.deviceState.value
        if (!shouldKeepServiceAlive()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (!observersStarted) {
            observeBluetoothState()
            observeSessionClock()
            observersStarted = true
        }

        app.container.bluetoothController.connectToSavedDevice()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        app.container.bluetoothController.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeBluetoothState() {
        serviceScope.launch {
            app.container.bluetoothController.deviceState.collectLatest { state ->
                currentDeviceState = state
                if (!shouldKeepServiceAlive()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                syncWatchState()
                refreshNotification()
            }
        }
        serviceScope.launch {
            app.container.bluetoothController.telemetry.collectLatest { telemetry ->
                lastTelemetryAt = System.currentTimeMillis()
                persistTelemetry(telemetry)
                processSessionTelemetry(telemetry)
                syncWatchState()
                refreshNotification()
            }
        }
    }

    private fun observeSessionClock() {
        serviceScope.launch {
            while (isActive) {
                checkSessionExpiry()
                if (!shouldKeepServiceAlive()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                syncWatchState()
                refreshNotification()
                delay(SESSION_WATCH_INTERVAL_MS)
            }
        }
    }

    private fun refreshNotification() {
        if (!shouldKeepServiceAlive()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val isTelemetryFresh = lastTelemetryAt?.let { System.currentTimeMillis() - it <= TELEMETRY_FRESH_MS } == true
        val sessionRemainingMs = app.container.appPreferences.getFocusSessionRemainingMs()
        val sessionActive = sessionRemainingMs > 0L

        val title = when {
            sessionActive && currentDeviceState.isConnected -> getString(R.string.notif_title_session_active)
            sessionActive -> getString(R.string.notif_title_session_paused)
            currentDeviceState.isConnected -> getString(R.string.notif_title_connected)
            currentDeviceState.hasSavedDevice -> getString(R.string.notif_title_searching)
            else -> getString(R.string.notif_title_disconnected)
        }
        val text = when {
            sessionActive && currentDeviceState.isConnected ->
                getString(R.string.notif_text_session_running, formatRemaining(sessionRemainingMs))

            sessionActive ->
                getString(R.string.notif_text_session_no_link)

            currentDeviceState.isConnected && isTelemetryFresh ->
                getString(R.string.notif_text_monitoring)

            currentDeviceState.isConnected ->
                getString(R.string.notif_text_waiting_data)

            currentDeviceState.hasSavedDevice ->
                getString(R.string.notif_text_reconnecting)

            else ->
                getString(R.string.notif_text_add_device_prompt)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (currentDeviceState.isConnected) R.drawable.ic_status_connected else R.drawable.ic_status_disconnected)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun persistTelemetry(telemetry: Telemetry) {
        val deviation = resolveDeviation(telemetry) ?: return
        latestDeviation = deviation
        rememberAngle(deviation)

        serviceScope.launch(Dispatchers.IO) {
            app.container.postureRepository.savePoint(
                timestamp = System.currentTimeMillis(),
                angle = deviation,
            )
        }
    }

    private fun processSessionTelemetry(telemetry: Telemetry) {
        val now = System.currentTimeMillis()
        if (!app.container.appPreferences.isFocusSessionActive(now)) {
            badPostureStartedAt = null
            return
        }

        val deviation = resolveDeviation(telemetry) ?: return
        val threshold = app.container.appPreferences.getResolvedAlertAngle()
        if (deviation <= threshold) {
            badPostureStartedAt = null
            return
        }

        val startedAt = badPostureStartedAt ?: now.also { badPostureStartedAt = it }
        val duration = now - startedAt
        val shouldNotify = duration >= BAD_POSTURE_ALERT_MS &&
            (lastPostureAlertAt == null || now - (lastPostureAlertAt ?: 0L) >= POSTURE_ALERT_COOLDOWN_MS)

        if (shouldNotify) {
            notifyAlert(
                notificationId = POSTURE_ALERT_NOTIFICATION_ID,
                title = getString(R.string.notif_alert_bad_posture_title),
                text = getString(R.string.notif_alert_bad_posture_text, formatDuration(duration)),
            )
            lastPostureAlertAt = now
        }
    }

    private fun resolveDeviation(telemetry: Telemetry): Float? {
        if (!app.container.appPreferences.isCalibrationDone()) return null

        val rawAngle = telemetry.angle
        val baseline = app.container.appPreferences.getBaselineAngle() ?: rawAngle.also {
            app.container.appPreferences.saveBaselineAngle(it)
        }
        return abs(rawAngle - baseline)
    }

    private fun checkSessionExpiry() {
        val endAt = app.container.appPreferences.getFocusSessionEndAt() ?: return
        val now = System.currentTimeMillis()
        if (endAt > now) return
        if (lastCompletedSessionEndAt == endAt) return

        lastCompletedSessionEndAt = endAt
        app.container.appPreferences.clearFocusSession()
        badPostureStartedAt = null
        lastPostureAlertAt = null

        notifyAlert(
            notificationId = SESSION_ALERT_NOTIFICATION_ID,
            title = getString(R.string.notif_alert_session_end_title),
            text = getString(R.string.notif_alert_session_end_text),
        )
    }

    private fun notifyAlert(
        notificationId: Int,
        title: String,
        text: String,
    ) {
        if (!canPostAlertNotifications()) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_connected)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(notificationId, notification)
    }

    private fun canPostAlertNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
        manager?.createNotificationChannel(alertsChannel)
    }

    private fun rememberAngle(angle: Float) {
        recentAngles.addLast(angle)
        while (recentAngles.size > MAX_GRAPH_POINTS) {
            recentAngles.removeFirst()
        }
    }

    private fun syncWatchState() {
        wearSyncController.pushState(
            WearStateSnapshot(
                isConnected = currentDeviceState.isConnected,
                batteryLevel = currentDeviceState.batteryLevel,
                currentAngle = latestDeviation,
                graphAngles = recentAngles.toList(),
                sessionActive = app.container.appPreferences.isFocusSessionActive(),
                sessionRemainingMs = app.container.appPreferences.getFocusSessionRemainingMs(),
            ),
        )
    }

    private fun shouldKeepServiceAlive(): Boolean {
        return currentDeviceState.isConnected || app.container.appPreferences.isFocusSessionActive()
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> getString(R.string.time_hours_minutes, hours, minutes)
            hours > 0L -> getString(R.string.time_hours, hours)
            else -> getString(R.string.time_minutes, minutes)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) {
            getString(R.string.time_hours_minutes, hours, minutes)
        } else {
            getString(R.string.time_minutes, totalMinutes)
        }
    }

    private companion object {
        const val CHANNEL_ID = "CorsetServiceChannel"
        const val ALERTS_CHANNEL_ID = "CorsetAlertsChannel"
        const val NOTIFICATION_ID = 1
        const val POSTURE_ALERT_NOTIFICATION_ID = 2
        const val SESSION_ALERT_NOTIFICATION_ID = 3
        const val TELEMETRY_FRESH_MS = 15_000L
        const val SESSION_WATCH_INTERVAL_MS = 15_000L
        const val BAD_POSTURE_ALERT_MS = 90_000L
        const val POSTURE_ALERT_COOLDOWN_MS = 5 * 60 * 1000L
        const val MAX_GRAPH_POINTS = 24
    }
}
