package com.akamev.corset.presentation.device

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.data.bluetooth.BluetoothController
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceConnectionUiState(
    val statusText: String = "Ищем сохраненное устройство...",
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
)

class DeviceConnectionViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceConnectionUiState())
    val uiState: StateFlow<DeviceConnectionUiState> = _uiState.asStateFlow()
    private var hasStarted = false

    init {
        observeConnection()
    }

    fun start() {
        if (hasStarted && _uiState.value.isLoading) return
        hasStarted = true
        _uiState.value = DeviceConnectionUiState()
        app.container.bluetoothController.connectToSavedDevice()
        viewModelScope.launch {
            delay(15_000)
            if (!_uiState.value.isConnected) {
                _uiState.update {
                    it.copy(
                        statusText = "Не удалось подключиться. Проверь, включен ли корсет и доступно ли устройство поблизости.",
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
                    _uiState.value = DeviceConnectionUiState(
                        statusText = "Соединение установлено.",
                        isLoading = false,
                        isConnected = true,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeviceConnectionViewModel(app) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    onBack: () -> Unit,
    onStartConnecting: () -> Unit,
) {
    val context = LocalContext.current
    var showLocationDialog by remember { mutableStateOf(false) }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothController.isBluetoothEnabled()) {
            if (BluetoothController.isLocationEnabled(context)) {
                onStartConnecting()
            } else {
                showLocationDialog = true
            }
        } else {
            Toast.makeText(context, "Bluetooth нужен для подключения.", Toast.LENGTH_SHORT).show()
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothController.isLocationEnabled(context)) {
            onStartConnecting()
        } else {
            Toast.makeText(context, "Геолокация нужна для поиска устройства.", Toast.LENGTH_SHORT).show()
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
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroHeader(
                    title = "Подключим корсет",
                    subtitle = "Проверим Bluetooth и геолокацию, а затем запустим поиск устройства.",
                )
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Что важно", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Приложение сохранит устройство и дальше будет само пытаться восстанавливать соединение в фоне.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(
                            onClick = {
                                when {
                                    !BluetoothController.isBluetoothEnabled() -> {
                                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    }

                                    !BluetoothController.isLocationEnabled(context) -> {
                                        showLocationDialog = true
                                    }

                                    else -> onStartConnecting()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Начать подключение")
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("Назад")
                        }
                    }
                }
            }
        }
    }

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("Нужна геолокация") },
            text = { Text("Android требует включить геолокацию для поиска Bluetooth-устройств.") },
            confirmButton = {
                Button(onClick = {
                    showLocationDialog = false
                    locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                    Text("Включить")
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
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.start()
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
                subtitle = "Устанавливаем связь с корсетом и готовим телеметрию для мониторинга.",
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
                        OutlinedButton(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
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
