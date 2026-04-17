package com.akamev.corset.data.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.akamev.corset.CorsetApplication
import com.akamev.corset.MainActivity
import com.akamev.corset.R

class BluetoothLeService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val app = application as CorsetApplication
        app.container.bluetoothController.connectToSavedDevice()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as CorsetApplication
        app.container.bluetoothController.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_connected)
            .setContentTitle("CorsetV работает")
            .setContentText("Поддерживаем соединение с устройством")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Corset Background Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "CorsetServiceChannel"
        const val NOTIFICATION_ID = 1
    }
}
