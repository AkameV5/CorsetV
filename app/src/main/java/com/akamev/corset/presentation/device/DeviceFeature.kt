package com.akamev.corset.presentation.device

import android.Manifest
import com.akamev.corset.R
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
    val statusTextRes: Int = R.string.device_scanning_title,
    val isScanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
)

data class DeviceConnectionUiState(
    val statusTextRes: Int = R.string.device_connecting_subtitle,
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
                    statusTextRes = R.string.device_connection_failed,
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
                        statusTextRes = buildStatusTextRes(isScanning = current.isScanning, devices = devices),
                    )
                }
            }
        }
        viewModelScope.launch {
            app.container.bluetoothController.isScanning.collectLatest { isScanning ->
                _uiState.update { current ->
                    current.copy(
                        isScanning = isScanning,
                        statusTextRes = buildStatusTextRes(isScanning = isScanning, devices = current.devices),
                    )
                }
            }
        }
    }

    private fun buildStatusTextRes(
        isScanning: Boolean,
        devices: List<ScannedDevice>,
    ): Int {
        return when {
            isScanning && devices.isEmpty() -> R.string.device_scanning_title
            isScanning -> R.string.device_available_list
            devices.isNotEmpty() -> R.string.device_available_list
            else -> R.string.device_no_found_subtitle
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
            statusTextRes = R.string.device_connecting_subtitle,
        )

        val started = if (deviceAddress.isNullOrBlank()) {
            app.container.bluetoothController.connectToSavedDevice()
        } else {
            app.container.bluetoothController.connectToDevice(deviceAddress)
        }

        if (!started) {
            _uiState.update {
                it.copy(
                    statusTextRes = R.string.device_connection_failed,
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
                        statusTextRes = R.string.device_connection_failed,
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
                        statusTextRes = R.string.device_connected_success,
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
            Toast.makeText(context, context.getString(R.string.device_bluetooth_needed), Toast.LENGTH_SHORT).show()
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = requiredBluetoothPermissions().all { permission ->
            result[permission] == true || hasPermission(context, permission)
        }
        if (allGranted) {
            if (BluetoothController.isBluetoothEnabled()) {
                requestPermissionsAndStartScan(context, viewModel) { showLocationDialog = true }
            } else {
                bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            Toast.makeText(context, context.getString(R.string.device_bluetooth_perms_needed), Toast.LENGTH_SHORT).show()
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (BluetoothController.isLocationEnabled(context)) {
            viewModel.startScan()
        } else {
            Toast.makeText(context, context.getString(R.string.device_location_needed), Toast.LENGTH_SHORT).show()
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
        topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(R.string.device_add_title)) }) },
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
                    title = androidx.compose.ui.res.stringResource(R.string.device_add_title),
                    subtitle = androidx.compose.ui.res.stringResource(R.string.device_add_subtitle),
                )
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.device_how_it_works), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = androidx.compose.ui.res.stringResource(state.statusTextRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (state.isScanning) {
                            CircularProgressIndicator()
                        }
                        Button(
                            onClick = {
                                when {
                                    !hasBluetoothPermissions(context) -> {
                                        permissionsLauncher.launch(requiredBluetoothPermissions())
                                    }

                                    !BluetoothController.isBluetoothEnabled() -> {
                                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    }

                                    requiresLocationForScan() && !BluetoothController.isLocationEnabled(context) -> {
                                        showLocationDialog = true
                                    }

                                    else -> viewModel.startScan()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.isScanning)
                                    androidx.compose.ui.res.stringResource(R.string.device_scanning_title)
                                else
                                    androidx.compose.ui.res.stringResource(R.string.device_start_scan_btn)
                            )
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text(androidx.compose.ui.res.stringResource(R.string.device_back_btn))
                        }
                    }
                }

                if (state.devices.isEmpty() && !state.isScanning) {
                    EmptyState(
                        title = androidx.compose.ui.res.stringResource(R.string.device_no_found_title),
                        subtitle = androidx.compose.ui.res.stringResource(R.string.device_no_found_subtitle),
                    )
                }

                if (state.devices.isNotEmpty()) {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.device_available_list), style = MaterialTheme.typography.titleLarge)
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
                                            text = device.name ?: androidx.compose.ui.res.stringResource(R.string.device_unnamed),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = buildDeviceSubtitle(context, device),
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
            title = { Text(androidx.compose.ui.res.stringResource(R.string.device_location_dialog_title)) },
            text = {
                Text(androidx.compose.ui.res.stringResource(R.string.device_location_dialog_desc))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationDialog = false
                        locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.device_open_settings_btn))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLocationDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.device_cancel_btn))
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
                title = androidx.compose.ui.res.stringResource(R.string.device_connecting_title),
                subtitle = androidx.compose.ui.res.stringResource(R.string.device_connecting_subtitle),
            )
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    }
                    Text(androidx.compose.ui.res.stringResource(state.statusTextRes), style = MaterialTheme.typography.bodyLarge)
                    if (!state.isConnected && !state.isLoading) {
                        OutlinedButton(
                            onClick = { viewModel.start(deviceAddress) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.device_retry_btn))
                        }
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(R.string.device_back_btn))
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

private fun buildDeviceSubtitle(context: Context, device: ScannedDevice): String {
    val parts = mutableListOf(device.address)
    if (device.isSaved) {
        parts += context.getString(R.string.device_saved_previously)
    }
    if (device.rssi != Int.MIN_VALUE) {
        parts += "${device.rssi} dBm"
    }
    return parts.joinToString(" • ")
}
