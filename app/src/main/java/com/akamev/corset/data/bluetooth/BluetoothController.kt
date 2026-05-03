package com.akamev.corset.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.domain.model.ScannedDevice
import com.akamev.corset.domain.model.Telemetry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothController(
    context: Context,
    private val appPreferences: AppPreferences,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredDevices = linkedMapOf<String, ScannedDevice>()

    private val _deviceState = MutableStateFlow(
        DeviceState(
            hasSavedDevice = appPreferences.getSavedDeviceAddress() != null,
            batteryLevel = appPreferences.getLastBatteryLevel(),
            isBatteryStale = appPreferences.getLastBatteryLevel() != null,
        ),
    )
    private val _telemetry = MutableSharedFlow<Telemetry>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val _isScanning = MutableStateFlow(false)

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var reconnectRunnable: Runnable? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var isConnected = false
    private var isManualDisconnect = false
    private var reconnectAttempts = 0
    private var lastRequestedDeviceAddress: String? = appPreferences.getSavedDeviceAddress()

    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan(): Boolean {
        val adapter = getBluetoothAdapter() ?: return false
        if (!adapter.isEnabled) return false

        val scanner = adapter.bluetoothLeScanner ?: return false
        stopScan()
        discoveredDevices.clear()
        _scanResults.value = emptyList()
        _isScanning.value = true

        emitSavedDevicePreview(adapter)

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build(),
        )

        val callback = scanCallback ?: return false
        val started = runCatching { scanner.startScan(filters, settings, callback) }.isSuccess
        if (!started) {
            stopScan()
            return false
        }
        scanTimeoutRunnable = Runnable { stopScan() }.also {
            mainHandler.postDelayed(it, SCAN_DURATION_MS)
        }
        return true
    }

    fun stopScan() {
        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)
        scanTimeoutRunnable = null

        val scanner = getBluetoothScanner()
        val callback = scanCallback
        if (scanner != null && callback != null) {
            runCatching { scanner.stopScan(callback) }
        }
        scanCallback = null
        _isScanning.value = false
    }

    fun connectToSavedDevice(): Boolean {
        if (checkSystemConnectedDevices()) return true
        if (isConnected && bluetoothGatt != null) return true

        val adapter = getBluetoothAdapter() ?: return false
        val savedAddress = appPreferences.getSavedDeviceAddress()
        val device = savedAddress
            ?.let { address -> runCatching { adapter.getRemoteDevice(address) }.getOrNull() }
            ?: runCatching {
                adapter.bondedDevices.firstOrNull { device ->
                    TARGET_DEVICE_NAMES.contains(device.safeName())
                }
            }.getOrNull()
            ?: return false

        return connect(device)
    }

    fun connectToDevice(address: String): Boolean {
        val adapter = getBluetoothAdapter() ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        return connect(device)
    }

    fun disconnect() {
        isManualDisconnect = true
        cancelReconnect()
        stopScan()
        commandCharacteristic = null
        isConnected = false
        closeGatt()
        publishState()
    }

    fun clearSavedDevice() {
        disconnect()
        lastRequestedDeviceAddress = null
        appPreferences.clearSavedDeviceAddress()
        appPreferences.clearLastBatteryLevel()
        publishState()
    }

    fun hasSavedDevice(): Boolean = appPreferences.getSavedDeviceAddress() != null

    fun writeCommand(value: String) {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        if (!isConnected) return

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value.toByteArray(StandardCharsets.UTF_8)
        gatt.writeCharacteristic(characteristic)
    }

    fun updateAlertThreshold(angle: Float) {
        if (!isConnected) return
        writeCommand(buildThresholdCommand(angle))
    }

    fun destroy() {
        disconnect()
    }

    private fun connect(device: BluetoothDevice): Boolean {
        stopScan()
        cancelReconnect()
        isManualDisconnect = false
        reconnectAttempts = 0
        return openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice): Boolean {
        closeGatt()
        commandCharacteristic = null
        isConnected = false
        lastRequestedDeviceAddress = device.address
        appPreferences.saveDeviceAddress(device.address)
        publishState()

        bluetoothGatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
        }.getOrNull()
        return bluetoothGatt != null
    }

    private fun checkSystemConnectedDevices(): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val savedAddress = appPreferences.getSavedDeviceAddress()
        val connectedDevice = runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).firstOrNull { device ->
                device.address == savedAddress || TARGET_DEVICE_NAMES.contains(device.safeName())
            }
        }.getOrNull() ?: return false

        if (!isConnected) {
            return connect(connectedDevice)
        }
        return true
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? = bluetoothManager()?.adapter

    private fun getBluetoothScanner(): BluetoothLeScanner? = getBluetoothAdapter()?.bluetoothLeScanner

    private fun bluetoothManager(): BluetoothManager? {
        return appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private fun closeGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
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

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val savedAddress = appPreferences.getSavedDeviceAddress()
        val name = device.safeName()?.trim().takeUnless { it.isNullOrEmpty() }
        val hasTargetService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
        val hasTargetName = name != null && TARGET_DEVICE_NAMES.contains(name)
        val shouldInclude = hasTargetService || hasTargetName || device.address == savedAddress
        if (!shouldInclude) return

        discoveredDevices[device.address] = ScannedDevice(
            address = device.address,
            name = name,
            rssi = result.rssi,
            isSaved = device.address == savedAddress,
        )
        _scanResults.value = discoveredDevices.values
            .sortedWith(
                compareByDescending<ScannedDevice> { it.isSaved }
                    .thenByDescending { it.rssi }
                    .thenBy { it.name ?: it.address },
            )
    }

    private fun emitSavedDevicePreview(adapter: BluetoothAdapter) {
        val savedAddress = appPreferences.getSavedDeviceAddress() ?: return
        val savedDevice = runCatching { adapter.getRemoteDevice(savedAddress) }.getOrNull() ?: return
        discoveredDevices[savedAddress] = ScannedDevice(
            address = savedAddress,
            name = savedDevice.safeName(),
            rssi = Int.MIN_VALUE,
            isSaved = true,
        )
        _scanResults.value = discoveredDevices.values.toList()
    }

    private fun scheduleServiceDiscovery(gatt: BluetoothGatt) {
        mainHandler.postDelayed(
            {
                if (bluetoothGatt == gatt) {
                    gatt.discoverServices()
                }
            },
            DISCOVER_SERVICES_DELAY_MS,
        )
    }

    private fun scheduleReconnect() {
        val address = lastRequestedDeviceAddress ?: appPreferences.getSavedDeviceAddress() ?: return
        if (isManualDisconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return

        cancelReconnect()
        reconnectAttempts += 1
        reconnectRunnable = Runnable {
            val adapter = getBluetoothAdapter() ?: return@Runnable
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@Runnable
            openGatt(device)
        }.also {
            mainHandler.postDelayed(it, RECONNECT_DELAY_MS)
        }
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun parseTelemetry(payload: String?): Telemetry? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.trim().split(";")
        val angle = parts.firstOrNull()?.trim()?.replace(',', '.')?.toFloatOrNull() ?: return null
        val motor = parts.getOrNull(1)?.trim()?.let { value ->
            when {
                value == "1" || value.equals("true", ignoreCase = true) -> true
                value == "0" || value.equals("false", ignoreCase = true) -> false
                else -> null
            }
        }
        val battery = parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
        return Telemetry(angle = angle, motorOn = motor, batteryLevel = battery)
    }

    private fun BluetoothDevice.safeName(): String? = runCatching { name }.getOrNull()

    private fun syncAlertThreshold() {
        val threshold = appPreferences.getResolvedAlertAngle()
        writeCommand(buildThresholdCommand(threshold))
    }

    private fun buildThresholdCommand(angle: Float): String {
        return "THR:${String.format(Locale.US, "%.1f", angle)}"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != bluetoothGatt) {
                runCatching { gatt.close() }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0
                    cancelReconnect()
                    publishState()

                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val mtuRequested = runCatching { gatt.requestMtu(DESIRED_MTU) }.getOrDefault(false)
                        if (!mtuRequested) {
                            scheduleServiceDiscovery(gatt)
                        }
                    } else {
                        scheduleServiceDiscovery(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    commandCharacteristic = null
                    bluetoothGatt = null
                    runCatching { gatt.close() }
                    publishState()

                    if (!isManualDisconnect) {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt != bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scheduleServiceDiscovery(gatt)
            } else {
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != bluetoothGatt || status != BluetoothGatt.GATT_SUCCESS) {
                if (gatt == bluetoothGatt) {
                    scheduleReconnect()
                }
                return
            }

            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                scheduleReconnect()
                return
            }
            commandCharacteristic = service.getCharacteristic(COMMAND_CHAR_UUID) ?: run {
                scheduleReconnect()
                return
            }
            val dataCharacteristic = service.getCharacteristic(DATA_CHAR_UUID) ?: run {
                scheduleReconnect()
                return
            }

            gatt.setCharacteristicNotification(dataCharacteristic, true)
            val descriptor = dataCharacteristic.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt != bluetoothGatt || status != BluetoothGatt.GATT_SUCCESS) return
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                syncAlertThreshold()
            }
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val DATA_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val COMMAND_CHAR_UUID: UUID = UUID.fromString("a2e88a38-36e1-4688-b7f5-ea07361b26a8")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val TARGET_DEVICE_NAMES = setOf(
            "CorsetV",
            "МРК КВ - 1",
            "РњР Рљ РљР’ - 1",
            "Р СљР В Р С™ Р С™Р вЂ™ - 1",
        )
        private const val DESIRED_MTU = 185
        private const val DISCOVER_SERVICES_DELAY_MS = 200L
        private const val RECONNECT_DELAY_MS = 1200L
        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val SCAN_DURATION_MS = 12_000L

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
