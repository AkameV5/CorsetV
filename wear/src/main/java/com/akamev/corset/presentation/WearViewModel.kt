package com.akamev.corset.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.data.PhoneCommandSender
import com.akamev.corset.data.WatchStateSnapshot
import com.akamev.corset.data.WatchStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class WearUiState(
    val isConnected: Boolean = false,
    val batteryLabel: String = "--",
    val currentAngleLabel: String = "--",
    val graphAngles: List<Float> = emptyList(),
    val sessionActive: Boolean = false,
    val sessionLabel: String = "45 мин",
    val statusText: String = "Ждём данные с телефона...",
    val message: String? = null,
)

class WearViewModel(
    context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val store = WatchStateStore(appContext)
    private val sender = PhoneCommandSender(appContext)

    private val _uiState = MutableStateFlow(WearUiState())
    val uiState: StateFlow<WearUiState> = _uiState.asStateFlow()

    init {
        refreshFromStore()
        requestState()
        startTicker()
    }

    fun requestState() {
        viewModelScope.launch {
            val success = runCatching { sender.requestState() }.getOrDefault(false)
            if (!success) {
                _uiState.update { it.copy(message = "Телефон не найден рядом") }
            }
            refreshFromStore()
        }
    }

    fun calibrate() {
        sendCommand(
            command = { sender.sendCalibrate() },
            successMessage = "Калибровка отправлена",
        )
    }

    fun startSession() {
        sendCommand(
            command = { sender.startSession() },
            successMessage = "Сессия запущена",
        )
    }

    fun stopSession() {
        sendCommand(
            command = { sender.stopSession() },
            successMessage = "Сессия остановлена",
        )
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun sendCommand(
        command: suspend () -> Boolean,
        successMessage: String,
    ) {
        viewModelScope.launch {
            val success = runCatching { command() }.getOrDefault(false)
            _uiState.update {
                it.copy(message = if (success) successMessage else "Не удалось связаться с телефоном")
            }
            if (success) {
                delay(400)
                requestState()
            }
        }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (isActive) {
                refreshFromStore()
                delay(1_000L)
            }
        }
    }

    private fun refreshFromStore() {
        val snapshot = store.load()
        val remainingMs = if (snapshot.sessionActive && snapshot.updatedAt > 0L) {
            (snapshot.sessionRemainingMs - (System.currentTimeMillis() - snapshot.updatedAt)).coerceAtLeast(0L)
        } else {
            0L
        }
        val sessionActive = snapshot.sessionActive && remainingMs > 0L

        _uiState.value = WearUiState(
            isConnected = snapshot.isConnected,
            batteryLabel = snapshot.batteryLevel?.let { "$it%" } ?: "--",
            currentAngleLabel = snapshot.currentAngle?.let(::formatAngle) ?: "--",
            graphAngles = snapshot.graphAngles,
            sessionActive = sessionActive,
            sessionLabel = if (sessionActive) formatRemaining(remainingMs) else "45 мин",
            statusText = buildStatusText(snapshot, sessionActive, remainingMs),
            message = _uiState.value.message,
        )
    }

    private fun buildStatusText(
        snapshot: WatchStateSnapshot,
        sessionActive: Boolean,
        remainingMs: Long,
    ): String {
        return when {
            sessionActive && snapshot.isConnected ->
                "Сессия активна, осталось ${formatRemaining(remainingMs)}"

            sessionActive ->
                "Сессия идёт, но телефон или корсет сейчас вне связи"

            snapshot.isConnected ->
                "Телефон и корсет на связи"

            snapshot.updatedAt > 0L ->
                "Показываем последние данные, ждём связь"

            else ->
                "Открой телефон рядом и нажми обновить"
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { WearViewModel(context) }
        }

        private fun formatAngle(value: Float): String = "${value.roundToInt()}°"

        private fun formatRemaining(remainingMs: Long): String {
            val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours > 0L && minutes > 0L -> String.format(Locale.getDefault(), "%dч %dм", hours, minutes)
                hours > 0L -> String.format(Locale.getDefault(), "%dч", hours)
                else -> String.format(Locale.getDefault(), "%dм", minutes)
            }
        }
    }
}
