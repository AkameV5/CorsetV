package com.akamev.corset;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BluetoothManager {

    private static BluetoothManager instance;
    private Context context;

    public static final String ACTION_GATT_CONNECTED    = "com.akamev.corset.ACTION_GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED = "com.akamev.corset.ACTION_GATT_DISCONNECTED";
    public static final String ACTION_DATA_AVAILABLE    = "com.akamev.corset.ACTION_DATA_AVAILABLE";
    public static final String EXTRA_DATA               = "com.akamev.corset.EXTRA_DATA";

    // UUID сервиса и характеристик
    private static final UUID SERVICE_UUID      = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID DATA_CHAR_UUID    = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID COMMAND_CHAR_UUID = UUID.fromString("a2e88a38-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String PREFS_NAME        = "BluetoothPrefs";
    private static final String KEY_DEVICE_ADDRESS = "saved_device_address";
    private static final String TARGET_DEVICE_NAME = "МРК КВ - 1";

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private boolean isConnected = false;
    private static final long DISCOVER_SERVICES_DELAY_MS = 200L;

    // =====================================================================
    // Singleton
    // =====================================================================

    public static synchronized BluetoothManager getInstance() {
        if (instance == null) {
            instance = new BluetoothManager();
        }
        return instance;
    }

    private BluetoothManager() {}

    public void init(Context context) {
        this.context = context.getApplicationContext();
    }

    // =====================================================================
    // FIX 1: метод destroy() — именно его не хватало в BluetoothLeService
    // =====================================================================

    public void destroy() {
        closeGatt();
        commandCharacteristic = null;
        isConnected = false;
        // context не обнуляем — синглтон живёт дольше сервиса
    }

    // =====================================================================
    // Проверка системных подключений
    // =====================================================================

    public boolean checkSystemConnectedDevices() {
        if (context == null) return false;

        // FIX 2: получаем BluetoothAdapter через системный BluetoothManager,
        // а не через устаревший BluetoothAdapter.getDefaultAdapter()
        android.bluetooth.BluetoothManager btManager =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager == null) return false;

        List<BluetoothDevice> connectedDevices =
                btManager.getConnectedDevices(BluetoothProfile.GATT);

        for (BluetoothDevice device : connectedDevices) {
            if (TARGET_DEVICE_NAME.equals(device.getName()) ||
                    (getSavedAddress() != null && getSavedAddress().equals(device.getAddress()))) {

                if (!isConnected) {
                    connect(device);
                }
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Подключение
    // =====================================================================

    public void connectToSavedDevice() {
        if (context == null) return;

        // Сначала ищем в уже подключённых системой (быстрый путь)
        if (checkSystemConnectedDevices()) return;

        // Если уже подключены программно — ничего не делаем
        if (isConnected && bluetoothGatt != null) return;

        String address = getSavedAddress();
        // FIX 2 (продолжение): используем BluetoothManager для получения адаптера
        BluetoothAdapter adapter = getBluetoothAdapter();

        if (address != null && adapter != null && adapter.isEnabled()) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                connect(device);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void connect(BluetoothDevice device) {
        // FIX 3: закрываем старый GATT перед новым подключением, чтобы не утекали ресурсы
        closeGatt();
        commandCharacteristic = null; // сбрасываем ссылку — сервисы будут переоткрыты

        saveDevice(device);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    public void disconnect() {
        closeGatt();
        isConnected = false;
        commandCharacteristic = null;
    }

    public boolean hasSavedDevice() {
        return getSavedAddress() != null;
    }

    public void clearSavedDevice() {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_DEVICE_ADDRESS).apply();
    }

    // FIX 4: вынесли закрытие GATT в отдельный метод, чтобы не дублировать код
    private void closeGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    // =====================================================================
    // Запись команды на устройство
    // =====================================================================

    public void writeCharacteristic(String value) {
        // FIX 5: проверяем isConnected, а не только commandCharacteristic
        if (!isConnected || bluetoothGatt == null || commandCharacteristic == null) return;

        commandCharacteristic.setValue(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bluetoothGatt.writeCharacteristic(commandCharacteristic);
    }

    // =====================================================================
    // Геттеры / утилиты
    // =====================================================================

    public boolean isConnected() {
        return isConnected;
    }

    private String getSavedAddress() {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_DEVICE_ADDRESS, null);
    }

    public void saveDevice(BluetoothDevice device) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_ADDRESS, device.getAddress()).apply();
    }

    // FIX 2: единая точка получения BluetoothAdapter (без deprecated метода)
    private BluetoothAdapter getBluetoothAdapter() {
        android.bluetooth.BluetoothManager btManager =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return btManager != null ? btManager.getAdapter() : null;
    }

    public static boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public static boolean isLocationEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean gps = false, net = false;
        try { gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        try { net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        return gps || net;
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                broadcastUpdate(ACTION_GATT_CONNECTED);
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(185);
                }
                // Небольшая задержка перед discoverServices — рекомендация Google для стабильности
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (bluetoothGatt == gatt) {
                        gatt.discoverServices();
                    }
                }, DISCOVER_SERVICES_DELAY_MS);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                commandCharacteristic = null; // FIX 5: сбрасываем при разрыве
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    commandCharacteristic = service.getCharacteristic(COMMAND_CHAR_UUID);

                    BluetoothGattCharacteristic dataChar = service.getCharacteristic(DATA_CHAR_UUID);
                    if (dataChar != null) {
                        gatt.setCharacteristicNotification(dataChar, true);
                        BluetoothGattDescriptor descriptor = dataChar.getDescriptor(CCC_DESCRIPTOR_UUID);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (DATA_CHAR_UUID.equals(characteristic.getUuid())) {
                String data = new String(
                        characteristic.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                broadcastUpdate(ACTION_DATA_AVAILABLE, data);
            }
        }
    };
    private void broadcastUpdate(final String action) {
        if (context != null) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(action));
        }
    }

    private void broadcastUpdate(final String action, String data) {
        if (context != null) {
            Intent intent = new Intent(action);
            intent.putExtra(EXTRA_DATA, data);
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    }
}
