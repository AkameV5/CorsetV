package com.akamev.corset.presentation.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.akamev.corset.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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
private const val CLOUD_SYNC_THROTTLE_MS = 5 * 60 * 1000L

data class HomeUiState(
    val firstName: String = "",
    val formattedDate: String = "",
    val dailyTip: String = "",
    val streak: Long = 0,
    val deviceState: DeviceState = DeviceState(),
    val goodPostureDurationMs: Long = 0L,
    val triggerCount: Int = 0,
    val averageDeviation: Float? = null,
    val bestPeriodLabel: String = "--",
    val worstPeriodLabel: String = "--",
    val summaryTextRes: Int = R.string.home_no_device_summary,
    val dynamicSummaryText: String? = null,
    val statusMessageRes: Int? = null,
    val isFocusSessionActive: Boolean = false,
    val selectedFocusSessionMinutes: Int = 45,
    val focusSessionRemainingMs: Long = 45 * 60_000L,
)

private data class TodayMetrics(
    val goodPostureDurationMs: Long,
    val triggerCount: Int,
    val averageDeviation: Float?,
    val bestPeriodLabel: String,
    val worstPeriodLabel: String,
    val summaryTextRes: Int = 0,
    val dynamicSummaryText: String? = null,
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
            val locale = Locale.getDefault()
            val today = SimpleDateFormat("EEEE, d MMMM", locale).format(Date())
            val formattedDate = today.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            val isRu = locale.language == "ru"
            val tips = if (isRu) {
                listOf(
                    "Держи экран на уровне глаз, а не коленей.",
                    "Раз в 30 минут мягко расправляй плечи и выдыхай глубже.",
                    "Пара минут ходьбы быстро снимает лишнюю нагрузку со спины.",
                    "Не зажимай шею, когда долго работаешь за столом.",
                    "Короткая разминка грудного отдела возвращает тонус быстрее, чем кажется.",
                )
            } else {
                listOf(
                    "Keep your screen at eye level rather than looking down.",
                    "Gently roll your shoulders back and take a deep breath every 30 minutes.",
                    "A quick 2-minute walk instantly relieves spinal pressure.",
                    "Avoid tucking your neck when working long hours at a desk.",
                    "A quick thoracic stretch restores alertness and relieves tension.",
                )
            }

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
            showStatusMessage(R.string.coach_device_disconnected_hint)
            return
        }

        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        showStatusMessage(R.string.coach_calibration_sent)
    }

    fun updateFocusSessionDuration(minutes: Int) {
        val snappedMinutes = snapFocusSessionMinutes(minutes)
        app.container.appPreferences.savePreferredFocusSessionDurationMinutes(snappedMinutes)
        refreshFocusSessionState()
    }

    fun startFocusSession() {
        if (!_uiState.value.deviceState.isConnected) {
            showStatusMessage(R.string.home_session_needs_device_msg)
            return
        }

        val durationMinutes = app.container.appPreferences.getPreferredFocusSessionDurationMinutes()
        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        app.container.appPreferences.startFocusSession(durationMinutes * 60_000L)
        app.ensureBluetoothServiceRunning()
        refreshFocusSessionState()
        showStatusMessage(R.string.home_session_started_msg)
    }

    fun stopFocusSession() {
        app.container.appPreferences.clearFocusSession()
        refreshFocusSessionState()
        showStatusMessage(R.string.home_session_stopped_msg)
    }

    fun onNotificationPermissionDenied() {
        showStatusMessage(R.string.home_permission_denied_msg)
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessageRes = null) }
    }

    private fun showStatusMessage(messageRes: Int) {
        _uiState.update { it.copy(statusMessageRes = messageRes) }
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

        if (!isActive) {
            _uiState.update {
                it.copy(
                    isFocusSessionActive = false,
                    selectedFocusSessionMinutes = preferredMinutes,
                    focusSessionRemainingMs = preferredMinutes * 60_000L,
                )
            }
            return
        }

        val remainingMs = app.container.appPreferences.getFocusSessionRemainingMs(now)
        _uiState.update {
            it.copy(
                isFocusSessionActive = true,
                selectedFocusSessionMinutes = preferredMinutes,
                focusSessionRemainingMs = remainingMs,
            )
        }
    }

    private var lastCloudSyncTimestamp: Long = 0L

    private fun refreshTodayMetrics() {
        viewModelScope.launch {
            val threshold = app.container.appPreferences.getResolvedAlertAngle()
            val history = app.container.postureRepository.loadHistory()
            val todayStart = startOfToday()
            val todayPoints = history.filter { it.timestamp >= todayStart }

            val metrics = buildTodayMetrics(
                history = history,
                threshold = threshold,
                deviceState = _uiState.value.deviceState,
            )
            _uiState.update {
                it.copy(
                    goodPostureDurationMs = metrics.goodPostureDurationMs,
                    triggerCount = metrics.triggerCount,
                    averageDeviation = metrics.averageDeviation,
                    bestPeriodLabel = metrics.bestPeriodLabel,
                    worstPeriodLabel = metrics.worstPeriodLabel,
                    summaryTextRes = metrics.summaryTextRes,
                    dynamicSummaryText = metrics.dynamicSummaryText,
                )
            }

            val now = System.currentTimeMillis()
            if (todayPoints.isNotEmpty() && (now - lastCloudSyncTimestamp >= CLOUD_SYNC_THROTTLE_MS)) {
                lastCloudSyncTimestamp = now
                val goodPostureMs = computeGoodPostureDuration(todayPoints, threshold)
                val goodMinutes = (goodPostureMs / 60_000L).coerceAtLeast(0L)
                val triggerCount = computeTriggerCount(todayPoints, threshold)
                val avgDev = todayPoints.map { it.angle }.average().toFloat()
                val goodCount = todayPoints.count { it.angle <= threshold }
                val score = (goodCount.toDouble() / todayPoints.size * 100).roundToInt().coerceIn(0, 100)

                app.container.userRepository.syncTodaySummary(
                    dateKey = app.container.appPreferences.todayKey(),
                    score = score,
                    goodPostureMinutes = goodMinutes,
                    triggerCount = triggerCount,
                    averageDeviation = avgDev,
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
            val summaryRes = when {
                !deviceState.hasSavedDevice -> R.string.home_no_device_summary
                deviceState.isConnected -> R.string.home_fresh_data_summary
                else -> R.string.home_offline_summary
            }
            return TodayMetrics(
                goodPostureDurationMs = 0L,
                triggerCount = 0,
                averageDeviation = null,
                bestPeriodLabel = "--",
                worstPeriodLabel = "--",
                summaryTextRes = summaryRes,
            )
        }

        val goodPostureMs = computeGoodPostureDuration(todayPoints, threshold)
        val triggerCount = computeTriggerCount(todayPoints, threshold)
        val averageDeviation = todayPoints.map { it.angle }.average().toFloat()
        val hourlyBuckets = todayPoints.groupBy { bucketStart(it.timestamp) }
        val bestPeriod = hourlyBuckets.maxByOrNull { (_, points) -> goodRatio(points, threshold) }?.key
        val worstPeriod = hourlyBuckets.minByOrNull { (_, points) -> goodRatio(points, threshold) }?.key

        val isRu = Locale.getDefault().language == "ru"
        val prefix = if (deviceState.isConnected) {
            if (isRu) "Корсет сейчас на связи." else "Corset is online."
        } else {
            if (isRu) "Сейчас устройство не на связи, но сводка за день сохранена." else "Device is offline, but daily summary is saved."
        }
        val formattedGoodTime = formatDurationString(app, goodPostureMs)
        val periodText = worstPeriod?.let(::formatPeriodLabel) ?: if (isRu) "без выраженного провала" else "no specific weak interval"
        val body = if (isRu) {
            "Хорошая осанка держалась ${formattedGoodTime.lowercase(Locale.getDefault())}, а чаще всего просадка встречалась в интервале $periodText."
        } else {
            "Good posture held for $formattedGoodTime, with most slouching occurring during $periodText."
        }

        return TodayMetrics(
            goodPostureDurationMs = goodPostureMs,
            triggerCount = triggerCount,
            averageDeviation = averageDeviation,
            bestPeriodLabel = bestPeriod?.let(::formatPeriodLabel) ?: "--",
            worstPeriodLabel = worstPeriod?.let(::formatPeriodLabel) ?: "--",
            dynamicSummaryText = "$prefix $body",
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
        state.deviceState.isConnected -> androidx.compose.ui.res.stringResource(R.string.home_status_connected)
        state.deviceState.hasSavedDevice -> androidx.compose.ui.res.stringResource(R.string.home_status_offline)
        else -> androidx.compose.ui.res.stringResource(R.string.home_status_not_added)
    }
    val batteryLabel = state.deviceState.batteryLevel?.let { "$it%" } ?: "--"

    val goodPostureLabel = if (state.goodPostureDurationMs > 0L) {
        formatDurationLocalized(context, state.goodPostureDurationMs)
    } else {
        androidx.compose.ui.res.stringResource(R.string.home_not_yet)
    }

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

    LaunchedEffect(state.statusMessageRes) {
        if (state.statusMessageRes != null) {
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
                    title = if (state.firstName.isBlank() || state.firstName == "Пользователь" || state.firstName == "User" || state.firstName == "Гость" || state.firstName == "Guest") {
                        androidx.compose.ui.res.stringResource(R.string.home_title_today)
                    } else {
                        androidx.compose.ui.res.stringResource(R.string.home_title_today_with_name, state.firstName)
                    },
                    subtitle = state.formattedDate.ifBlank { androidx.compose.ui.res.stringResource(R.string.home_default_subtitle) },
                )
                TwoColumnStats(
                    firstLabel = androidx.compose.ui.res.stringResource(R.string.home_stat_good_posture),
                    firstValue = goodPostureLabel,
                    secondLabel = androidx.compose.ui.res.stringResource(R.string.home_stat_triggers),
                    secondValue = state.triggerCount.toString(),
                )
                TwoColumnStats(
                    firstLabel = androidx.compose.ui.res.stringResource(R.string.home_stat_avg_deviation),
                    firstValue = state.averageDeviation?.let { formatAngle(it) } ?: "--",
                    secondLabel = androidx.compose.ui.res.stringResource(R.string.home_stat_battery),
                    secondValue = batteryLabel,
                )
                if (state.statusMessageRes != null) {
                    GlassCard {
                        Text(
                            text = androidx.compose.ui.res.stringResource(state.statusMessageRes!!),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.home_session_section),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (state.isFocusSessionActive)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (state.isFocusSessionActive)
                                        androidx.compose.ui.res.stringResource(R.string.home_session_active_badge)
                                    else
                                        androidx.compose.ui.res.stringResource(R.string.home_session_ready_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (state.isFocusSessionActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        if (state.isFocusSessionActive) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = formatRemainingLocalized(context, state.focusSessionRemainingMs),
                                    style = MaterialTheme.typography.displayLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.home_session_running_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = viewModel::stopFocusSession,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.home_session_btn_stop))
                            }
                        } else {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.home_session_picker_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(15, 30, 45, 60).forEach { minutes ->
                                    val isSelected = state.selectedFocusSessionMinutes == minutes
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                if (isSelected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            )
                                            .clickable { viewModel.updateFocusSessionDuration(minutes) }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = context.getString(R.string.time_minutes, minutes),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    if (hasNotificationPermission(context)) {
                                        viewModel.startFocusSession()
                                    } else {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.home_session_btn_start, state.selectedFocusSessionMinutes))
                            }
                        }
                    }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.home_daily_picture_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = state.dynamicSummaryText ?: androidx.compose.ui.res.stringResource(state.summaryTextRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeMiniCard(
                                title = androidx.compose.ui.res.stringResource(R.string.home_stat_best_period),
                                value = state.bestPeriodLabel,
                                modifier = Modifier.weight(1f),
                            )
                            HomeMiniCard(
                                title = androidx.compose.ui.res.stringResource(R.string.home_stat_worst_period),
                                value = state.worstPeriodLabel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeActionCard(
                        title = androidx.compose.ui.res.stringResource(R.string.home_action_monitoring_title),
                        subtitle = androidx.compose.ui.res.stringResource(R.string.home_action_monitoring_subtitle),
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Coach.route) }
                    HomeActionCard(
                        title = androidx.compose.ui.res.stringResource(R.string.home_action_device_title),
                        subtitle = context.getString(R.string.home_action_device_subtitle, deviceStatusLabel),
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Profile.route) }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.home_tip_title), style = MaterialTheme.typography.titleLarge)
                        Text(state.dailyTip, style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = viewModel::calibrate,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.home_calibrate_btn))
                        }
                        OutlinedButton(
                            onClick = { onNavigate(CorsetDestination.Coach.route) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.home_open_monitoring_btn))
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

private fun formatDurationString(context: Context, durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> context.getString(R.string.time_hours_minutes, hours, minutes)
        hours > 0 -> context.getString(R.string.time_hours, hours)
        else -> context.getString(R.string.time_minutes, minutes.coerceAtLeast(1))
    }
}

private fun formatDurationLocalized(context: Context, durationMs: Long): String {
    return formatDurationString(context, durationMs)
}

private fun formatRemainingLocalized(context: Context, remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> context.getString(R.string.time_hours_minutes, hours, minutes)
        hours > 0 -> context.getString(R.string.time_hours, hours)
        else -> context.getString(R.string.time_minutes, minutes)
    }
}

private fun formatMinutesLocalized(context: Context, minutes: Int): String {
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        hours > 0 && restMinutes > 0 -> context.getString(R.string.time_hours_minutes, hours, restMinutes)
        hours > 0 -> context.getString(R.string.time_hours, hours)
        else -> context.getString(R.string.time_minutes, restMinutes)
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
