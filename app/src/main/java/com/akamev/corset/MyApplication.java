package com.akamev.corset;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Запускаем Фоновый Сервис вместо простой инициализации
        Intent serviceIntent = new Intent(this, BluetoothLeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}