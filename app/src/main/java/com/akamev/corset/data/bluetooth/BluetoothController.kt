package com.akamev.corset.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.domain.model.Telemetry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothController(
    context: Context,
    private val appPreferences: AppPreferences,
) {

    private val appContext = context.applicationContext
    private val _deviceState = MutableStateFlow(
        DeviceState(
            hasSavedDevice = appPreferences.getSavedDeviceAddress() != null,
            batteryLevel = appPreferences.getLastBatteryLevel(),
            isBatteryStale = appPreferences.getLastBatteryLevel() != null,
        )
    )
    private val _telemetry = MutableSharedFlow<Telemetry>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false

    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    fun connectToSavedDevice() {
        if (checkSystemConnectedDevices()) return
        if (isConnected && bluetoothGatt != null) return

        val adapter = getBluetoothAdapter() ?: return
        val device = appPreferences.getSavedDeviceAddress()
            ?.let { address -> runCatching { adapter.getRemoteDevice(address) }.getOrNull() }
            ?: adapter.bondedDevices.firstOrNull { it.name == TARGET_DEVICE_NAME }

        device?.let(::connect)
        publishState()
    }

    fun disconnect() {
        closeGatt()
        isConnected = false
        commandCharacteristic = null
        publishState()
    }

    fun clearSavedDevice() {
        disconnect()
        appPreferences.clearSavedDeviceAddress()
        appPreferences.clearLastBatteryLevel()
        publishState()
    }

    fun hasSavedDevice(): Boolean = appPreferences.getSavedDeviceAddress() != null

    fun writeCommand(value: String) {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        if (!isConnected) return

        characteristic.setValue(value.toByteArray(StandardCharsets.UTF_8))
        gatt.writeCharacteristic(characteristic)
    }

    fun destroy() {
        disconnect()
    }

    private fun connect(device: BluetoothDevice) {
        closeGatt()
        commandCharacteristic = null
        appPreferences.saveDeviceAddress(device.address)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private fun checkSystemConnectedDevices(): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: return false
        val connectedDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
        connectedDevices.forEach { device ->
            if (device.name == TARGET_DEVICE_NAME || device.address == appPreferences.getSavedDeviceAddress()) {
                if (!isConnected) {
                    connect(device)
                }
                return true
            }
        }
        return false
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        return manager?.adapter
    }

    private fun closeGatt() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun publishState() {
        val batteryLevel = appPreferences.getLastBatteryLevel()
        val hasSavedDevice = appPreferences.getSavedDeviceAddress() != null
        _deviceState.value = DeviceState(
            hasSavedDevice = hasSavedDevice,
            isConnected = isConnected,
            batteryLevel = if (hasSavedDevice || isConnected) batteryLevel else null,
            isBatteryStale = batteryLevel != null && !isConnected,
        )
    }

    private fun parseTelemetry(payload: String?): Telemetry? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.trim().split(";")
        val angle = parts.firstOrNull()?.trim()?.replace(',', '.')?.toFloatOrNull() ?: return null
        val motor = parts.getOrNull(1)?.trim()?.let { value ->
            when {
                value.equals("1") || value.equals("true", ignoreCase = true) -> true
                value.equals("0") || value.equals("false", ignoreCase = true) -> false
                else -> null
            }
        }
        val battery = parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
        return Telemetry(angle = angle, motorOn = motor, batteryLevel = battery)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                publishState()
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(185)
                }
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        if (bluetoothGatt == gatt) {
                            gatt.discoverServices()
                        }
                    },
                    DISCOVER_SERVICES_DELAY_MS,
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                commandCharacteristic = null
                publishState()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: return
            commandCharacteristic = service.getCharacteristic(COMMAND_CHAR_UUID)
            val dataCharacteristic = service.getCharacteristic(DATA_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(dataCharacteristic, true)
            val descriptor = dataCharacteristic.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != DATA_CHAR_UUID) return

            val payload = characteristic.value?.toString(StandardCharsets.UTF_8)
            val telemetryData = parseTelemetry(payload) ?: return
            telemetryData.batteryLevel?.let(appPreferences::saveLastBatteryLevel)
            publishState()
            _telemetry.tryEmit(telemetryData)
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val DATA_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val COMMAND_CHAR_UUID: UUID = UUID.fromString("a2e88a38-36e1-4688-b7f5-ea07361b26a8")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_DEVICE_NAME = "МРК КВ - 1"
        private const val DISCOVER_SERVICES_DELAY_MS = 200L

        fun isBluetoothEnabled(): Boolean {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            return adapter != null && adapter.isEnabled
        }

        fun isLocationEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            val gpsEnabled = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            val networkEnabled = runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            return gpsEnabled || networkEnabled
        }
    }
}
