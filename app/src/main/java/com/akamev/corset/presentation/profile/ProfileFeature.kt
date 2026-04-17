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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val fullName: String = "",
    val avatar: String = "C",
    val deviceState: DeviceState = DeviceState(),
    val statusMessage: String? = null,
)

class ProfileViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeDeviceState()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = app.container.userRepository.getCurrentUserProfile()
            val name = listOf(profile?.firstName, profile?.lastName)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            _uiState.update {
                it.copy(
                    fullName = name,
                    avatar = profile?.firstName?.firstOrNull()?.uppercaseChar()?.toString() ?: "C",
                )
            }
        }
    }

    fun calibrate() {
        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        _uiState.update { it.copy(statusMessage = "Положение зафиксировано. Можно начинать мониторинг.") }
    }

    fun deleteDevice() {
        app.container.bluetoothController.clearSavedDevice()
        _uiState.update { it.copy(statusMessage = "Устройство удалено.") }
    }

    fun logout() {
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
                    title = state.fullName.ifBlank { "Профиль" },
                    subtitle = "Управление устройством, калибровкой и состоянием подключения.",
                )
                TwoColumnStats(
                    firstLabel = "Статус",
                    firstValue = if (state.deviceState.isConnected) "Онлайн" else "Оффлайн",
                    secondLabel = "Батарея",
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
                        title = "Устройство пока не добавлено",
                        subtitle = "Подключи корсет, сохрани устройство и затем переходи к калибровке.",
                    )
                    Button(onClick = onOpenAddDevice, modifier = Modifier.fillMaxWidth()) {
                        Text("Добавить устройство")
                    }
                } else {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Текущее устройство", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = if (state.deviceState.isConnected) {
                                    "Корсет подключен и готов к передаче данных."
                                } else {
                                    "Адрес сохранен. Приложение будет пытаться восстановить соединение автоматически."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (state.deviceState.isBatteryStale) {
                                Text(
                                    text = "Заряд показан по последним полученным данным.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = viewModel::calibrate, modifier = Modifier.fillMaxWidth()) {
                                Text("Откалибровать")
                            }
                            OutlinedButton(onClick = viewModel::deleteDevice, modifier = Modifier.fillMaxWidth()) {
                                Text("Удалить устройство")
                            }
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
                    Text("Выйти из аккаунта")
                }
            }
        }
    }
}
