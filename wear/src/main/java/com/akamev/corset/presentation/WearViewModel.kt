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
    val currentAngleLabel: String = "--",
    val summaryLabel: String = "Phone offline",
    val graphAngles: List<Float> = emptyList(),
    val sessionActive: Boolean = false,
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
                _uiState.update { it.copy(message = "Phone not reachable") }
            }
            refreshFromStore()
        }
    }

    fun startSession() {
        sendCommand(
            command = { sender.startSession() },
            successMessage = "Session started",
        )
    }

    fun stopSession() {
        sendCommand(
            command = { sender.stopSession() },
            successMessage = "Session stopped",
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
                it.copy(message = if (success) successMessage else "Command failed")
            }
            if (success) {
                delay(300L)
                requestState()
            }
        }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (isActive) {
                refreshFromStore()
                delay(WATCH_UI_REFRESH_MS)
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
            currentAngleLabel = snapshot.currentAngle?.let(::formatAngle) ?: "--",
            summaryLabel = buildSummary(snapshot, sessionActive, remainingMs),
            graphAngles = snapshot.graphAngles,
            sessionActive = sessionActive,
            message = _uiState.value.message,
        )
    }

    private fun buildSummary(
        snapshot: WatchStateSnapshot,
        sessionActive: Boolean,
        remainingMs: Long,
    ): String {
        return when {
            sessionActive && snapshot.isConnected -> "Session ${formatRemaining(remainingMs)} left"
            sessionActive -> "Session active, phone offline"
            snapshot.isConnected -> "Phone connected"
            snapshot.updatedAt > 0L -> "Showing last sync"
            else -> "Open phone nearby"
        }
    }

    companion object {
        private const val WATCH_UI_REFRESH_MS = 400L

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { WearViewModel(context) }
        }

        private fun formatAngle(value: Float): String = "${value.roundToInt()}°"

        private fun formatRemaining(remainingMs: Long): String {
            val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours > 0L && minutes > 0L -> String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
                hours > 0L -> String.format(Locale.getDefault(), "%dh", hours)
                else -> String.format(Locale.getDefault(), "%dm", minutes)
            }
        }
    }
}
