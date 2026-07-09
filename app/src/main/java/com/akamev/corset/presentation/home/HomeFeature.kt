package com.akamev.corset.presentation.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.domain.model.PosturePoint
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import com.akamev.corset.presentation.common.TwoColumnStats
import com.akamev.corset.presentation.navigation.CorsetDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val DEFAULT_SAMPLE_MS = 100L
private const val MAX_INTERVAL_MS = 1_000L
private const val TODAY_REFRESH_MS = 15_000L
private const val SESSION_TICK_MS = 1_000L
private const val MIN_FOCUS_SESSION_MINUTES = 5
private const val MAX_FOCUS_SESSION_MINUTES = 180
private const val FOCUS_SESSION_STEP_MINUTES = 5

data class HomeUiState(
    val firstName: String = "",
    val formattedDate: String = "",
    val dailyTip: String = "",
    val streak: Long = 0,
    val deviceState: DeviceState = DeviceState(),
    val goodPostureLabel: String = "Пока нет",
    val triggerCountLabel: String = "0",
    val averageDeviationLabel: String = "--",
    val bestPeriodLabel: String = "--",
    val worstPeriodLabel: String = "--",
    val summaryText: String = "Подключи корсет и начни мониторинг, чтобы собрать первую сводку дня.",
    val statusMessage: String? = null,
    val isFocusSessionActive: Boolean = false,
    val selectedFocusSessionMinutes: Int = 45,
    val focusSessionRemainingLabel: String = "45 мин",
    val focusSessionStatus: String = "Выбери время и запусти сессию. На старте корсет примет текущую позу как базовую.",
)

private data class TodayMetrics(
    val goodPostureLabel: String,
    val triggerCountLabel: String,
    val averageDeviationLabel: String,
    val bestPeriodLabel: String,
    val worstPeriodLabel: String,
    val summaryText: String,
)

class HomeViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeDeviceState()
        refresh()
        startPeriodicRefresh()
        startSessionTicker()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = app.container.userRepository.getCurrentUserProfile()
            val locale = Locale.forLanguageTag("ru")
            val today = SimpleDateFormat("EEEE, d MMMM", locale).format(Date())
            val formattedDate = today.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            val tips = listOf(
                "Держи экран на уровне глаз, а не коленей.",
                "Раз в 30 минут мягко расправляй плечи и выдыхай глубже.",
                "Пара минут ходьбы быстро снимает лишнюю нагрузку со спины.",
                "Не зажимай шею, когда долго работаешь за столом.",
                "Короткая разминка грудного отдела возвращает тонус быстрее, чем кажется.",
            )

            _uiState.update {
                it.copy(
                    firstName = profile?.firstName.orEmpty(),
                    formattedDate = formattedDate,
                    dailyTip = tips[formattedDate.hashCode().absoluteValue % tips.size],
                    streak = profile?.currentStreak ?: 0,
                    selectedFocusSessionMinutes = app.container.appPreferences.getPreferredFocusSessionDurationMinutes(),
                )
            }
            refreshTodayMetrics()
            refreshFocusSessionState()
        }
    }

    fun calibrate() {
        if (!_uiState.value.deviceState.isConnected) {
            showStatusMessage("Сначала подключи корсет, потом можно будет откалибровать его прямо отсюда.")
            return
        }

        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        showStatusMessage("Калибровка отправлена. Подержи ровную позу пару секунд.")
    }

    fun updateFocusSessionDuration(minutes: Int) {
        val snappedMinutes = snapFocusSessionMinutes(minutes)
        app.container.appPreferences.savePreferredFocusSessionDurationMinutes(snappedMinutes)
        refreshFocusSessionState()
    }

    fun startFocusSession() {
        if (!_uiState.value.deviceState.isConnected) {
            showStatusMessage("Чтобы сессия реально запустила корсет, сначала подключи его к телефону.")
            return
        }

        val durationMinutes = app.container.appPreferences.getPreferredFocusSessionDurationMinutes()
        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        app.container.appPreferences.startFocusSession(durationMinutes * 60_000L)
        app.ensureBluetoothServiceRunning()
        refreshFocusSessionState()
        showStatusMessage("Сессия запущена. Корсет принял текущую позу как базовую.")
    }

    fun stopFocusSession() {
        app.container.appPreferences.clearFocusSession()
        refreshFocusSessionState()
        showStatusMessage("Фокус-сессия остановлена.")
    }

    fun onNotificationPermissionDenied() {
        showStatusMessage("Без разрешения на уведомления сессия пойдёт, но напоминания могут не прийти.")
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun showStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun observeDeviceState() {
        viewModelScope.launch {
            app.container.bluetoothController.deviceState.collectLatest { state ->
                _uiState.update { it.copy(deviceState = state) }
                refreshTodayMetrics()
                refreshFocusSessionState()
            }
        }
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (isActive) {
                refreshTodayMetrics()
                delay(TODAY_REFRESH_MS)
            }
        }
    }

    private fun startSessionTicker() {
        viewModelScope.launch {
            while (isActive) {
                refreshFocusSessionState()
                delay(SESSION_TICK_MS)
            }
        }
    }

    private fun refreshFocusSessionState() {
        val now = System.currentTimeMillis()
        val isActive = app.container.appPreferences.isFocusSessionActive(now)
        val preferredMinutes = app.container.appPreferences.getPreferredFocusSessionDurationMinutes()
        val preferredLabel = formatMinutes(preferredMinutes)

        if (!isActive) {
            _uiState.update {
                it.copy(
                    isFocusSessionActive = false,
                    selectedFocusSessionMinutes = preferredMinutes,
                    focusSessionRemainingLabel = preferredLabel,
                    focusSessionStatus = if (it.deviceState.isConnected) {
                        "Корсет на связи. Выбери время, и на старте он примет текущую позу как базовую."
                    } else {
                        "Сначала подключи корсет. Теперь сессия сразу калибрует его под текущую позу."
                    },
                )
            }
            return
        }

        val remainingMs = app.container.appPreferences.getFocusSessionRemainingMs(now)
        _uiState.update {
            it.copy(
                isFocusSessionActive = true,
                selectedFocusSessionMinutes = preferredMinutes,
                focusSessionRemainingLabel = formatRemaining(remainingMs),
                focusSessionStatus = if (it.deviceState.isConnected) {
                    "Сессия идёт. Корсет уже ориентируется на текущую позу."
                } else {
                    "Сессия идёт, но связь с корсетом пропала. Подключение вернётся автоматически."
                },
            )
        }
    }

    private fun refreshTodayMetrics() {
        viewModelScope.launch {
            val threshold = app.container.appPreferences.getResolvedAlertAngle()
            val history = app.container.postureHistoryLocalDataSource.loadHistory()
            val metrics = buildTodayMetrics(
                history = history,
                threshold = threshold,
                deviceState = _uiState.value.deviceState,
            )
            _uiState.update {
                it.copy(
                    goodPostureLabel = metrics.goodPostureLabel,
                    triggerCountLabel = metrics.triggerCountLabel,
                    averageDeviationLabel = metrics.averageDeviationLabel,
                    bestPeriodLabel = metrics.bestPeriodLabel,
                    worstPeriodLabel = metrics.worstPeriodLabel,
                    summaryText = metrics.summaryText,
                )
            }
        }
    }

    private fun buildTodayMetrics(
        history: List<PosturePoint>,
        threshold: Float,
        deviceState: DeviceState,
    ): TodayMetrics {
        val todayStart = startOfToday()
        val todayPoints = history
            .filter { it.timestamp >= todayStart }
            .sortedBy { it.timestamp }

        if (todayPoints.isEmpty()) {
            val summary = when {
                !deviceState.hasSavedDevice -> "Корсет ещё не добавлен. Подключи устройство и запусти калибровку, чтобы видеть сводку дня."
                deviceState.isConnected -> "Данные за сегодня только начинают собираться. Оставь мониторинг включённым, и здесь появится картина дня."
                else -> "За сегодня пока нет телеметрии. Когда корсет снова выйдет на связь, экран начнёт собирать дневную сводку."
            }
            return TodayMetrics(
                goodPostureLabel = "Пока нет",
                triggerCountLabel = "0",
                averageDeviationLabel = "--",
                bestPeriodLabel = "--",
                worstPeriodLabel = "--",
                summaryText = summary,
            )
        }

        val goodPostureMs = computeGoodPostureDuration(todayPoints, threshold)
        val triggerCount = computeTriggerCount(todayPoints, threshold)
        val averageDeviation = todayPoints.map { it.angle }.average().toFloat()
        val hourlyBuckets = todayPoints.groupBy { bucketStart(it.timestamp) }
        val bestPeriod = hourlyBuckets.maxByOrNull { (_, points) -> goodRatio(points, threshold) }?.key
        val worstPeriod = hourlyBuckets.minByOrNull { (_, points) -> goodRatio(points, threshold) }?.key
        val currentStatus = if (deviceState.isConnected) {
            "Корсет сейчас на связи."
        } else {
            "Сейчас устройство не на связи, но сводка за день сохранена."
        }

        return TodayMetrics(
            goodPostureLabel = formatDuration(goodPostureMs),
            triggerCountLabel = triggerCount.toString(),
            averageDeviationLabel = formatAngle(averageDeviation),
            bestPeriodLabel = bestPeriod?.let(::formatPeriodLabel) ?: "--",
            worstPeriodLabel = worstPeriod?.let(::formatPeriodLabel) ?: "--",
            summaryText = "$currentStatus Хорошая осанка держалась ${formatDuration(goodPostureMs).lowercase(Locale.getDefault())}, а чаще всего просадка встречалась в интервале ${worstPeriod?.let(::formatPeriodLabel) ?: "без выраженного провала"}.",
        )
    }

    private fun computeGoodPostureDuration(
        points: List<PosturePoint>,
        threshold: Float,
    ): Long {
        if (points.isEmpty()) return 0L

        var total = 0L
        for (index in points.indices) {
            val current = points[index]
            val next = points.getOrNull(index + 1)
            val interval = if (next == null) {
                DEFAULT_SAMPLE_MS
            } else {
                (next.timestamp - current.timestamp).coerceIn(0L, MAX_INTERVAL_MS)
            }
            if (current.angle <= threshold) {
                total += interval
            }
        }
        return total
    }

    private fun computeTriggerCount(
        points: List<PosturePoint>,
        threshold: Float,
    ): Int {
        var triggers = 0
        var wasAboveThreshold = false

        points.forEach { point ->
            val isAboveThreshold = point.angle > threshold
            if (isAboveThreshold && !wasAboveThreshold) {
                triggers += 1
            }
            wasAboveThreshold = isAboveThreshold
        }

        return triggers
    }

    private fun goodRatio(
        points: List<PosturePoint>,
        threshold: Float,
    ): Float {
        if (points.isEmpty()) return 0f
        return points.count { it.angle <= threshold }.toFloat() / points.size.toFloat()
    }

    private fun startOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun bucketStart(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(app) }
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceStatusLabel = when {
        state.deviceState.isConnected -> "На связи"
        state.deviceState.hasSavedDevice -> "Оффлайн"
        else -> "Не добавлен"
    }
    val batteryLabel = state.deviceState.batteryLevel?.let { "$it%" } ?: "--"

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startFocusSession()
        } else {
            viewModel.onNotificationPermissionDenied()
            viewModel.startFocusSession()
        }
    }

    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            delay(2500)
            viewModel.consumeStatusMessage()
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
                    title = if (state.firstName.isBlank()) "Сегодня" else "Сегодня, ${state.firstName}",
                    subtitle = state.formattedDate.ifBlank { "Сводка дня появится здесь, как только корсет начнёт собирать телеметрию." },
                )
                TwoColumnStats(
                    firstLabel = "Хорошая осанка",
                    firstValue = state.goodPostureLabel,
                    secondLabel = "Срабатывания",
                    secondValue = state.triggerCountLabel,
                )
                TwoColumnStats(
                    firstLabel = "Среднее отклонение",
                    firstValue = state.averageDeviationLabel,
                    secondLabel = "Батарея",
                    secondValue = batteryLabel,
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
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Фокус-сессия", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.isFocusSessionActive) {
                                "Осталось ${state.focusSessionRemainingLabel}"
                            } else {
                                "Старт на ${formatMinutes(state.selectedFocusSessionMinutes)}"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = state.focusSessionStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.isFocusSessionActive) {
                            OutlinedButton(
                                onClick = viewModel::stopFocusSession,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Остановить сессию")
                            }
                        } else {
                            Slider(
                                value = state.selectedFocusSessionMinutes.toFloat(),
                                onValueChange = { value -> viewModel.updateFocusSessionDuration(value.roundToInt()) },
                                valueRange = MIN_FOCUS_SESSION_MINUTES.toFloat()..MAX_FOCUS_SESSION_MINUTES.toFloat(),
                                steps = ((MAX_FOCUS_SESSION_MINUTES - MIN_FOCUS_SESSION_MINUTES) / FOCUS_SESSION_STEP_MINUTES) - 1,
                            )
                            Text(
                                text = "От 5 минут до 3 часов",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    if (hasNotificationPermission(context)) {
                                        viewModel.startFocusSession()
                                    } else {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Начать сессию на ${formatMinutes(state.selectedFocusSessionMinutes)}")
                            }
                        }
                    }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Картина дня", style = MaterialTheme.typography.titleLarge)
                        Text(state.summaryText, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeMiniCard(
                                title = "Лучший период",
                                value = state.bestPeriodLabel,
                                modifier = Modifier.weight(1f),
                            )
                            HomeMiniCard(
                                title = "Слабый период",
                                value = state.worstPeriodLabel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeActionCard(
                        title = "Мониторинг",
                        subtitle = "Живой угол, режимы напоминаний и график осанки",
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Coach.route) }
                    HomeActionCard(
                        title = "Устройство",
                        subtitle = "Статус: $deviceStatusLabel",
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Profile.route) }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Совет дня", style = MaterialTheme.typography.titleLarge)
                        Text(state.dailyTip, style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::calibrate,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Откалибровать")
                        }
                        OutlinedButton(
                            onClick = { onNavigate(CorsetDestination.Coach.route) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Открыть мониторинг")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassCard(modifier = modifier.clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HomeMiniCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
        hours > 0 -> "$hours ч"
        else -> "${minutes.coerceAtLeast(1)} мин"
    }
}

private fun formatRemaining(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours ч $minutes мин"
        hours > 0L -> "$hours ч"
        else -> "$minutes мин"
    }
}

private fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        hours > 0 && restMinutes > 0 -> "$hours ч $restMinutes мин"
        hours > 0 -> "$hours ч"
        else -> "$minutes мин"
    }
}

private fun snapFocusSessionMinutes(minutes: Int): Int {
    val clamped = minutes.coerceIn(MIN_FOCUS_SESSION_MINUTES, MAX_FOCUS_SESSION_MINUTES)
    val relative = clamped - MIN_FOCUS_SESSION_MINUTES
    val snapped = ((relative + FOCUS_SESSION_STEP_MINUTES / 2) / FOCUS_SESSION_STEP_MINUTES) * FOCUS_SESSION_STEP_MINUTES
    return (MIN_FOCUS_SESSION_MINUTES + snapped).coerceIn(MIN_FOCUS_SESSION_MINUTES, MAX_FOCUS_SESSION_MINUTES)
}

private fun formatAngle(value: Float): String = "${value.roundToInt()}°"

private fun formatPeriodLabel(bucketStart: Long): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = bucketStart
    val startHour = calendar.get(Calendar.HOUR_OF_DAY)
    val endHour = (startHour + 1) % 24
    return String.format(Locale.getDefault(), "%02d:00-%02d:00", startHour, endHour)
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
