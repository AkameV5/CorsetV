package com.akamev.corset;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ConnectingActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TextView statusText;
    private Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            final String action = intent.getAction();

            if (BluetoothManager.ACTION_GATT_CONNECTED.equals(action)) {
                if (statusText != null) statusText.setText("Успешно!");
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                handler.postDelayed(() -> {
                    // ИСПРАВЛЕНИЕ: Пишем полный путь к константе
                    ConnectingActivity.this.setResult(android.app.Activity.RESULT_OK);
                    finish();
                }, 500);

            } else if (BluetoothManager.ACTION_GATT_DISCONNECTED.equals(action)) {
                if (statusText != null) statusText.setText("Попытка соединения...");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connecting);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.status_text);

        if (statusText != null) statusText.setText("Поиск сигнала...");

        BluetoothManager.getInstance().connectToSavedDevice();

        handler.postDelayed(() -> {
            if (!isFinishing() && !BluetoothManager.getInstance().isConnected()) {
                if (statusText != null) statusText.setText("Не удалось подключиться.\nПроверьте корсет.");
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }
        }, 15000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothManager.ACTION_GATT_CONNECTED);
        filter.addAction(BluetoothManager.ACTION_GATT_DISCONNECTED);
        LocalBroadcastManager.getInstance(this).registerReceiver(connectionReceiver, filter);

        if (BluetoothManager.getInstance().isConnected()) {
            // ИСПРАВЛЕНИЕ: Пишем полный путь к константе
            setResult(android.app.Activity.RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(connectionReceiver);
    }
}