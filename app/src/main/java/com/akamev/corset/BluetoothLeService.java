package com.akamev.corset;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class BluetoothLeService extends Service {

    private static final String CHANNEL_ID = "CorsetServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Инициализируем менеджер, используя контекст сервиса
        BluetoothManager.getInstance().init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Создаем уведомление, чтобы сервис не убивала система
        Intent notificationIntent = new Intent(this, ProfileActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Корсет работает")
                .setContentText("Поддержание связи с устройством...")
                .setSmallIcon(R.drawable.ic_status_connected) // Убедись, что иконка существует
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // Запускаем авто-подключение в менеджере
        BluetoothManager.getInstance().connectToSavedDevice();

        return START_STICKY; // Если сервис убьют, Android перезапустит его
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        BluetoothManager.getInstance().destroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Corset Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}