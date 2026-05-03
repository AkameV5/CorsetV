package com.akamev.corset.presentation.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.data.bluetooth.BluetoothController
import com.akamev.corset.domain.model.ScannedDevice
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.EmptyState
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddDeviceUiState(
    val statusText: String = "Подготовь корсет и запусти поиск рядом с телефоном.",
    val isScanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
)

data class DeviceConnectionUiState(
    val statusText: String = "Подключаемся к устройству...",
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
)

class AddDeviceViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddDeviceUiState())
    val uiState: StateFlow<AddDeviceUiState> = _uiState.asStateFlow()

    init {
        observeScan()
    }

    fun startScan() {
        val started = app.container.bluetoothController.startScan()
        if (!started) {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    statusText = "Не удалось запустить поиск. Проверь Bluetooth и разрешения.",
                )
            }
        }
    }

    fun stopScan() {
        app.container.bluetoothController.stopScan()
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    private fun observeScan() {
        viewModelScope.launch {
            app.container.bluetoothController.scanResults.collectLatest { devices ->
                _uiState.update { current ->
                    current.copy(
                        devices = devices,
                        statusText = buildStatusText(isScanning = current.isScanning, devices = devices),
                    )
                }
            }
        }
        viewModelScope.launch {
            app.container.bluetoothController.isScanning.collectLatest { isScanning ->
                _uiState.update { current ->
                    current.copy(
                        isScanning = isScanning,
                        statusText = buildStatusText(isScanning = isScanning, devices = current.devices),
                    )
                }
            }
        }
    }

    private fun buildStatusText(
        isScanning: Boolean,
        devices: List<ScannedDevice>,
    ): String {
        return when {
            isScanning && devices.isEmpty() -> "Ищем BLE-устройства рядом..."
            isScanning -> "Поиск идет. Можно выбрать устройство сразу из списка."
            devices.isNotEmpty() -> "Выбери нужное устройство и подключись прямо из приложения."
            else -> "Устройства не найдены. Поднеси корсет ближе и повтори поиск."
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddDeviceViewModel(app) }
        }
    }
}

class DeviceConnectionViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceConnectionUiState())
    val uiState: StateFlow<DeviceConnectionUiState> = _uiState.asStateFlow()

    private var hasStarted = false
    private var activeAddress: String? = null
    private var timeoutJob: Job? = null

    init {
        observeConnection()
    }

    fun start(deviceAddress: String?) {
        if (hasStarted && _uiState.value.isLoading && activeAddress == deviceAddress) return

        hasStarted = true
        activeAddress = deviceAddress
        timeoutJob?.cancel()
        _uiState.value = DeviceConnectionUiState(
            statusText = if (deviceAddress.isNullOrBlank()) {
                "Подключаемся к сохраненному устройству..."
            } else {
                "Подключаемся к выбранному устройству..."
            }
        )

        val started = if (deviceAddress.isNullOrBlank()) {
            app.container.bluetoothController.connectToSavedDevice()
        } else {
            app.container.bluetoothController.connectToDevice(deviceAddress)
        }

        if (!started) {
            _uiState.update {
                it.copy(
                    statusText = "Не удалось начать подключение. Проверь, что устройство включено и находится рядом.",
                    isLoading = false,
                )
            }
            return
        }

        timeoutJob = viewModelScope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (!_uiState.value.isConnected) {
                _uiState.update {
                    it.copy(
                        statusText = "Подключение заняло слишком много времени. Попробуй еще раз ближе к устройству.",
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            app.container.bluetoothController.deviceState.collectLatest { deviceState ->
                if (deviceState.isConnected) {
                    app.ensureBluetoothServiceRunning()
                    timeoutJob?.cancel()
                    _uiState.value = DeviceConnectionUiState(
                        statusText = "Соединение установлено.",
                        isLoading = false,
                        isConnected = true,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        timeoutJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L

        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeviceConnectionViewModel(app) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    viewModel: AddDeviceViewModel,
    onBack: () -> Unit,
    onStartConnecting: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLocationDialog by remember { mutableStateOf(false) }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothController.isBluetoothEnabled()) {
            requestPermissionsAndStartScan(context, viewModel) { showLocationDialog = true }
        } else {
            Toast.makeText(context, "Bluetooth нужен для подключения.", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = requiredBluetoothPermissions().all { permission ->
            result[permission] == true || hasPermission(context, permission)
        }
        if (allGranted) {
            requestPermissionsAndStartScan(context, viewModel) { showLocationDialog = true }
        } else {
            Toast.makeText(context, "Нужны Bluetooth-разрешения для поиска устройства.", Toast.LENGTH_SHORT).show()
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothController.isLocationEnabled(context)) {
            viewModel.startScan()
        } else {
            Toast.makeText(context, "Для Android 11 и ниже нужно включить геолокацию.", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    LaunchedEffect(Unit) {
        if (
            BluetoothController.isBluetoothEnabled() &&
            hasBluetoothPermissions(context) &&
            (!requiresLocationForScan() || BluetoothController.isLocationEnabled(context))
        ) {
            viewModel.startScan()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Добавление устройства") }) },
    ) { paddingValues ->
        CorsetBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroHeader(
                    title = "Подключим корсет",
                    subtitle = "Поиск и подключение теперь запускаются прямо внутри приложения.",
                )
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Как это работает", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (state.isScanning) {
                            CircularProgressIndicator()
                        }
                        Button(
                            onClick = {
                                when {
                                    !BluetoothController.isBluetoothEnabled() -> {
                                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    }

                                    !hasBluetoothPermissions(context) -> {
                                        permissionsLauncher.launch(requiredBluetoothPermissions())
                                    }

                                    requiresLocationForScan() && !BluetoothController.isLocationEnabled(context) -> {
                                        showLocationDialog = true
                                    }

                                    else -> viewModel.startScan()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.isScanning) "Идет поиск..." else "Найти устройства")
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("Назад")
                        }
                    }
                }

                if (state.devices.isEmpty() && !state.isScanning) {
                    EmptyState(
                        title = "Пока ничего не найдено",
                        subtitle = "Включи корсет, поднеси его ближе к телефону и повтори поиск.",
                    )
                }

                if (state.devices.isNotEmpty()) {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Доступные устройства", style = MaterialTheme.typography.titleLarge)
                            state.devices.forEach { device ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.stopScan()
                                        onStartConnecting(device.address)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = device.name ?: "Без имени",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = buildDeviceSubtitle(device),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("Нужно включить геолокацию") },
            text = {
                Text("На Android 11 и ниже система требует включенную геолокацию для BLE-сканирования.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationDialog = false
                        locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                ) {
                    Text("Открыть настройки")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLocationDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
fun ConnectingScreen(
    viewModel: DeviceConnectionViewModel,
    deviceAddress: String?,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(deviceAddress) {
        viewModel.start(deviceAddress)
    }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            delay(500)
            onConnected()
        }
    }

    CorsetBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroHeader(
                title = "Подключение",
                subtitle = "Устанавливаем BLE-соединение и готовим телеметрию для мониторинга.",
            )
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    }
                    Text(state.statusText, style = MaterialTheme.typography.bodyLarge)
                    if (!state.isConnected && !state.isLoading) {
                        OutlinedButton(
                            onClick = { viewModel.start(deviceAddress) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Повторить")
                        }
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Назад")
                    }
                }
            }
        }
    }
}

private fun requestPermissionsAndStartScan(
    context: Context,
    viewModel: AddDeviceViewModel,
    onLocationRequired: () -> Unit,
) {
    when {
        !hasBluetoothPermissions(context) -> Unit
        requiresLocationForScan() && !BluetoothController.isLocationEnabled(context) -> onLocationRequired()
        else -> viewModel.startScan()
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun requiresLocationForScan(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

private fun hasBluetoothPermissions(context: Context): Boolean {
    return requiredBluetoothPermissions().all { permission -> hasPermission(context, permission) }
}

private fun hasPermission(
    context: Context,
    permission: String,
): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun buildDeviceSubtitle(device: ScannedDevice): String {
    val parts = mutableListOf(device.address)
    if (device.isSaved) {
        parts += "сохранено ранее"
    }
    if (device.rssi != Int.MIN_VALUE) {
        parts += "${device.rssi} dBm"
    }
    return parts.joinToString(" • ")
}
