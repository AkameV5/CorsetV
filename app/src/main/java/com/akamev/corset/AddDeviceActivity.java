package com.akamev.corset;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class AddDeviceActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> enableBluetoothLauncher;
    private ActivityResultLauncher<Intent> enableLocationLauncher;
    private ActivityResultLauncher<Intent> connectingLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_device);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (BluetoothManager.isBluetoothEnabled()) {
                        checkLocationAndProceed();
                    } else {
                        Toast.makeText(this, "Bluetooth необходим для подключения", Toast.LENGTH_SHORT).show();
                    }
                });

        enableLocationLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (BluetoothManager.isLocationEnabled(this)) {
                        startConnectingActivity();
                    } else {
                        Toast.makeText(this, "Геолокация необходима для поиска устройств", Toast.LENGTH_SHORT).show();
                    }
                });

        connectingLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    setResult(result.getResultCode());
                    finish();
                });

        View corsetButton = findViewById(R.id.corset_button);
        corsetButton.setOnClickListener(v -> checkBluetoothAndProceed());
    }

    private void checkBluetoothAndProceed() {
        if (!BluetoothManager.isBluetoothEnabled()) {
            // Показываем диалог, но не переходим никуда, пока не будет ОК
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            checkLocationAndProceed();
        }
    }

    private void checkLocationAndProceed() {
        if (!BluetoothManager.isLocationEnabled(this)) {
            showEnableDialog(
                    "Требуется геолокация",
                    "Для поиска Bluetooth-устройств Android требует включить службы геолокации.",
                    () -> {
                        Intent enableGpsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        enableLocationLauncher.launch(enableGpsIntent);
                    }
            );
        } else {
            startConnectingActivity();
        }
    }

    private void startConnectingActivity() {
        Intent intent = new Intent(AddDeviceActivity.this, ConnectingActivity.class);
        connectingLauncher.launch(intent);
    }

    private void showEnableDialog(String title, String message, Runnable positiveAction) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Включить", (dialog, which) -> positiveAction.run())
                .setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }
}