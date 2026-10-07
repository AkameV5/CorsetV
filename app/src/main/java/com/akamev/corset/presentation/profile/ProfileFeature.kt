package com.akamev.corset.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.R
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.EmptyState
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import com.akamev.corset.presentation.common.TwoColumnStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.akamev.corset.presentation.common.InteractiveCalibrationDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val fullName: String = "",
    val avatar: String = "C",
    val isGuest: Boolean = false,
    val email: String = "",
    val deviceState: DeviceState = DeviceState(),
    val currentAngle: Float? = null,
    val isCalibrationDialogOpen: Boolean = false,
    val statusMessage: String? = null,
)

class ProfileViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeDeviceState()
        observeTelemetry()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = app.container.userRepository.getCurrentUserProfile()
            val isGuest = profile?.isGuest == true || app.container.appPreferences.isGuestMode()
            val name = if (isGuest) {
                val savedName = listOf(profile?.firstName, profile?.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                if (savedName.isBlank() || savedName == "Пользователь" || savedName == "User") {
                    app.getString(R.string.profile_guest_name)
                } else savedName
            } else {
                val n = listOf(profile?.firstName, profile?.lastName)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                n.ifBlank { app.getString(R.string.profile_default_user_name) }
            }
            _uiState.update {
                it.copy(
                    fullName = name,
                    avatar = if (isGuest) "G" else (profile?.firstName?.firstOrNull()?.uppercaseChar()?.toString() ?: "C"),
                    isGuest = isGuest,
                    email = profile?.email.orEmpty(),
                )
            }
        }
    }

    fun openCalibrationDialog() {
        if (!_uiState.value.deviceState.isConnected) {
            _uiState.update { it.copy(statusMessage = app.getString(R.string.coach_device_disconnected_hint)) }
            return
        }
        _uiState.update { it.copy(isCalibrationDialogOpen = true) }
    }

    fun closeCalibrationDialog() {
        _uiState.update { it.copy(isCalibrationDialogOpen = false) }
    }

    fun onCalibrationConfirmed() {
        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        _uiState.update { it.copy(statusMessage = app.getString(R.string.profile_calibrated_msg)) }
    }

    fun calibrate() {
        openCalibrationDialog()
    }

    fun deleteDevice() {
        app.container.bluetoothController.clearSavedDevice()
        app.stopBluetoothService()
        _uiState.update { it.copy(statusMessage = app.getString(R.string.profile_device_deleted_msg)) }
    }

    fun logout() {
        app.stopBluetoothService()
        app.container.appPreferences.setGuestMode(false)
        app.container.authRepository.signOut()
    }

    fun consumeMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun observeDeviceState() {
        viewModelScope.launch {
            app.container.bluetoothController.deviceState.collectLatest { state ->
                _uiState.update { it.copy(deviceState = state) }
            }
        }
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            app.container.bluetoothController.telemetry.collectLatest { telemetry ->
                _uiState.update { it.copy(currentAngle = telemetry.angle) }
            }
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(app) }
        }
    }
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenAddDevice: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            delay(2500)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        bottomBar = { CorsetBottomBar(currentRoute = currentRoute, onNavigate = onNavigate) },
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
                    title = state.fullName.ifBlank { stringResource(R.string.profile_title) },
                    subtitle = stringResource(R.string.profile_subtitle),
                )
                TwoColumnStats(
                    firstLabel = stringResource(R.string.coach_title),
                    firstValue = if (state.deviceState.isConnected) stringResource(R.string.profile_status_online) else stringResource(R.string.profile_status_offline),
                    secondLabel = stringResource(R.string.home_stat_battery),
                    secondValue = state.deviceState.batteryLevel?.let { "$it%" } ?: "--",
                )
                if (state.statusMessage != null) {
                    GlassCard {
                        Text(
                            text = state.statusMessage.orEmpty(),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (!state.deviceState.hasSavedDevice && !state.deviceState.isConnected) {
                    EmptyState(
                        title = stringResource(R.string.profile_device_empty_title),
                        subtitle = stringResource(R.string.profile_device_empty_subtitle),
                    )
                    Button(onClick = onOpenAddDevice, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.profile_add_device_btn))
                    }
                } else {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.profile_device_card_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = if (state.deviceState.isConnected) {
                                    stringResource(R.string.profile_device_connected_desc)
                                } else {
                                    stringResource(R.string.profile_device_saved_desc)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (state.deviceState.isBatteryStale) {
                                Text(
                                    text = stringResource(R.string.notif_text_waiting_data),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = viewModel::calibrate, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.profile_calibrate_btn))
                            }
                            OutlinedButton(onClick = viewModel::deleteDevice, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.profile_delete_device_btn))
                            }
                        }
                    }
                }
                if (state.isGuest) {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(stringResource(R.string.profile_guest_badge), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(R.string.profile_guest_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    viewModel.logout()
                                    onLoggedOut()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.profile_login_or_register_btn))
                            }
                        }
                    }
                } else {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(stringResource(R.string.profile_cloud_sync_title), style = MaterialTheme.typography.titleMedium)
                            if (state.email.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.profile_cloud_sync_account, state.email),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                text = stringResource(R.string.profile_cloud_sync_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        viewModel.logout()
                        onLoggedOut()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (state.isGuest) R.string.profile_reset_guest_btn else R.string.profile_logout_btn))
                }
            }
        }
    }

    if (state.isCalibrationDialogOpen) {
        InteractiveCalibrationDialog(
            currentAngle = state.currentAngle,
            isConnected = state.deviceState.isConnected,
            onDismissRequest = viewModel::closeCalibrationDialog,
            onCalibrateConfirmed = viewModel::onCalibrationConfirmed,
        )
    }
}
