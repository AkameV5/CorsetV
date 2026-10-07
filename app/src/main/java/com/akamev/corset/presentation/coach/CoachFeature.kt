package com.akamev.corset.presentation.coach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.domain.model.CoachFilter
import com.akamev.corset.domain.model.PostureAlertMode
import com.akamev.corset.domain.model.PosturePoint
import com.akamev.corset.domain.model.Telemetry
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.EmptyState
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import com.akamev.corset.presentation.common.TwoColumnStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.presentation.common.InteractiveCalibrationDialog
import com.akamev.corset.presentation.common.SpineVisualizer

private const val DEFAULT_THRESHOLD = 5f
private const val MIN_CUSTOM_THRESHOLD = 3f
private const val MAX_CUSTOM_THRESHOLD = 12f

data class CoachUiState(
    val isMonitoringStarted: Boolean = false,
    val score: Int = 100,
    val currentAngle: Float? = null,
    val thresholdAngle: Float = DEFAULT_THRESHOLD,
    val alertMode: PostureAlertMode = PostureAlertMode.Precise,
    val customAlertAngle: Float = 7f,
    val aiAdvice: String = "",
    val isAiLoading: Boolean = false,
    val selectedFilter: CoachFilter = CoachFilter.Live,
    val chartPoints: List<PosturePoint> = emptyList(),
    val deviceState: DeviceState = DeviceState(),
    val isCalibrationDialogOpen: Boolean = false,
)

class CoachViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoachUiState())
    val uiState: StateFlow<CoachUiState> = _uiState.asStateFlow()

    private val historyPoints = mutableListOf<PosturePoint>()
    private var baselineAngle = app.container.appPreferences.getBaselineAngle()
    private var alertMode = app.container.appPreferences.getAlertMode()
    private var customAlertAngle = app.container.appPreferences.getCustomAlertAngle()
    private var isAiBusy = false

    init {
        loadHistory()
        refreshAlertSettings(pushToDevice = false)
        observeTelemetry()
        observeDeviceState()
        refreshMonitoringState()
    }

    fun openCalibrationDialog() {
        _uiState.update { it.copy(isCalibrationDialogOpen = true) }
    }

    fun closeCalibrationDialog() {
        _uiState.update { it.copy(isCalibrationDialogOpen = false) }
    }

    fun onCalibrationConfirmed() {
        app.container.bluetoothController.writeCommand("SET")
        app.container.appPreferences.setCalibrationDone(true)
        app.container.appPreferences.clearBaselineAngle()
        com.akamev.corset.presentation.widget.CorsetAppWidgetProvider.updateAllWidgets(app)
        refreshMonitoringState()
    }

    private fun observeDeviceState() {
        viewModelScope.launch {
            app.container.bluetoothController.deviceState.collectLatest { deviceState ->
                _uiState.update { it.copy(deviceState = deviceState) }
            }
        }
    }

    fun selectFilter(filter: CoachFilter) {
        _uiState.update { it.copy(selectedFilter = filter, chartPoints = computeChartPoints(filter)) }
    }

    fun selectAlertMode(mode: PostureAlertMode) {
        app.container.appPreferences.saveAlertMode(mode)
        refreshAlertSettings()
    }

    fun updateCustomAlertAngle(value: Float) {
        val normalized = ((value * 2f).roundToInt() / 2f).coerceIn(MIN_CUSTOM_THRESHOLD, MAX_CUSTOM_THRESHOLD)
        app.container.appPreferences.saveCustomAlertAngle(normalized)
        refreshAlertSettings()
    }

    private fun refreshMonitoringState() {
        val isMonitoringStarted = app.container.appPreferences.isCalibrationDone()
        baselineAngle = app.container.appPreferences.getBaselineAngle()
        _uiState.update {
            it.copy(
                isMonitoringStarted = isMonitoringStarted,
                aiAdvice = if (isMonitoringStarted) {
                    if (baselineAngle == null) {
                        "Мониторинг запущен. Ждём первые данные, чтобы зафиксировать опорное положение."
                    } else {
                        "Опорный угол: ${formatAngle(baselineAngle ?: 0f)}"
                    }
                } else {
                    "Нажмите «Откалибровать» в профиле, чтобы начать отслеживание."
                },
            )
        }
    }

    private fun refreshAlertSettings(pushToDevice: Boolean = true) {
        alertMode = app.container.appPreferences.getAlertMode()
        customAlertAngle = app.container.appPreferences.getCustomAlertAngle()
        val threshold = currentAlertThreshold()
        _uiState.update {
            it.copy(
                alertMode = alertMode,
                customAlertAngle = customAlertAngle,
                thresholdAngle = threshold,
            )
        }
        if (pushToDevice) {
            app.container.bluetoothController.updateAlertThreshold(threshold)
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            historyPoints.clear()
            historyPoints.addAll(app.container.postureHistoryLocalDataSource.loadHistory())
            _uiState.update { it.copy(chartPoints = computeChartPoints(it.selectedFilter)) }
        }
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            app.container.bluetoothController.telemetry.collectLatest(::processTelemetry)
        }
    }

    private suspend fun processTelemetry(telemetry: Telemetry) {
        refreshMonitoringState()
        if (!_uiState.value.isMonitoringStarted) return

        val rawAngle = telemetry.angle
        if (baselineAngle == null) {
            baselineAngle = rawAngle
            app.container.appPreferences.saveBaselineAngle(rawAngle)
        }

        val deviation = abs(rawAngle - (baselineAngle ?: rawAngle))
        val now = System.currentTimeMillis()
        val point = PosturePoint(timestamp = now, angle = deviation)

        historyPoints.add(point)
        if (historyPoints.size > 10_000) {
            historyPoints.removeAt(0)
        }

        val threshold = currentAlertThreshold()
        val score = computeScore(threshold)
        val todayPointsCount = todayPoints().size

        _uiState.update {
            it.copy(
                score = score,
                currentAngle = deviation,
                thresholdAngle = threshold,
                chartPoints = computeChartPoints(it.selectedFilter),
            )
        }

        if (!isAiBusy && (todayPointsCount == 10 || (todayPointsCount > 10 && todayPointsCount % 200 == 0))) {
            requestAdvice(score = score, averageAngle = averageAngle(20 * 60 * 1000L))
        }
    }

    private fun computeChartPoints(filter: CoachFilter): List<PosturePoint> {
        if (historyPoints.isEmpty()) return emptyList()
        return if (filter == CoachFilter.Live) {
            historyPoints.takeLast(60)
        } else {
            val threshold = System.currentTimeMillis() - (filter.periodMs ?: 0L)
            historyPoints.filter { it.timestamp >= threshold }
        }
    }

    private fun averageAngle(periodMs: Long): Float {
        val threshold = System.currentTimeMillis() - periodMs
        val slice = historyPoints.filter { it.timestamp >= threshold }
        if (slice.isEmpty()) return 0f
        return slice.sumOf { it.angle.toDouble() }.toFloat() / slice.size
    }

    private fun todayPoints(): List<PosturePoint> {
        val startOfDay = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        return historyPoints.filter { it.timestamp >= startOfDay }
    }

    private fun computeScore(threshold: Float): Int {
        val points = todayPoints()
        if (points.isEmpty()) return 100
        val goodPoints = points.count { it.angle <= threshold }
        return ((goodPoints.toFloat() / points.size.toFloat()) * 100f).roundToInt()
    }

    private fun currentAlertThreshold(): Float = alertMode.resolveAngle(customAlertAngle)

    private fun requestAdvice(score: Int, averageAngle: Float) {
        isAiBusy = true
        _uiState.update { it.copy(isAiLoading = true) }
        viewModelScope.launch {
            val prompt = "Анализ осанки. Среднее отклонение за 20 минут: ${
                String.format(Locale.US, "%.1f", averageAngle)
            } градусов. Порог реакции: ${String.format(Locale.US, "%.1f", currentAlertThreshold())} градусов. Оценка: $score%. Дай один короткий совет на русском языке."
            val advice = runCatching {
                app.container.aiRemoteDataSource.requestText(prompt)
            }.getOrElse {
                fallbackAdvice(averageAngle)
            }
            _uiState.update { it.copy(aiAdvice = advice, isAiLoading = false) }
            isAiBusy = false
        }
    }

    private fun fallbackAdvice(averageAngle: Float): String {
        return when {
            averageAngle < 3f -> "Положение ровное. Сохраняй этот темп."
            averageAngle < 7f -> "Есть небольшой уход от базы. Раскрой грудной отдел и выровняй плечи."
            else -> "Отклонение устойчивое. Выпрямись и сделай короткую паузу."
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { CoachViewModel(app) }
        }
    }
}

@Composable
fun CoachScreen(
    viewModel: CoachViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                    title = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_title),
                    subtitle = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_subtitle),
                )
                GlassCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_alert_mode_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "${androidx.compose.ui.res.stringResource(state.alertMode.titleRes)} • ${formatAngle(state.thresholdAngle)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(state.alertMode.descriptionRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PostureAlertMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.alertMode == mode,
                                    onClick = { viewModel.selectAlertMode(mode) },
                                    label = { Text(androidx.compose.ui.res.stringResource(mode.titleRes)) },
                                )
                            }
                        }
                        if (state.alertMode == PostureAlertMode.Custom) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_custom_slider_label, formatAngle(state.customAlertAngle)),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Slider(
                                    value = state.customAlertAngle,
                                    onValueChange = viewModel::updateCustomAlertAngle,
                                    valueRange = MIN_CUSTOM_THRESHOLD..MAX_CUSTOM_THRESHOLD,
                                )
                                Text(
                                    text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_custom_slider_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (!state.isMonitoringStarted) {
                    EmptyState(
                        title = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_not_started_title),
                        subtitle = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_not_started_subtitle),
                    )
                    androidx.compose.material3.Button(
                        onClick = viewModel::openCalibrationDialog,
                        enabled = state.deviceState.isConnected,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_calibrate_btn))
                    }
                } else {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.spine_visualizer_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                androidx.compose.material3.OutlinedButton(
                                    onClick = viewModel::openCalibrationDialog,
                                    enabled = state.deviceState.isConnected,
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_calibrate_btn))
                                }
                            }
                            SpineVisualizer(
                                deviationAngle = state.currentAngle ?: 0f,
                                thresholdAngle = state.thresholdAngle,
                                height = 190.dp,
                            )
                        }
                    }

                    TwoColumnStats(
                        firstLabel = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_score_label),
                        firstValue = "${state.score}%",
                        secondLabel = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_angle_label),
                        secondValue = state.currentAngle?.let { formatWholeAngle(it) } ?: "--",
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CoachFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.selectedFilter == filter,
                                onClick = { viewModel.selectFilter(filter) },
                                label = { Text(androidx.compose.ui.res.stringResource(filter.titleRes)) },
                            )
                        }
                    }
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_chart_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_chart_desc, formatAngle(state.thresholdAngle)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CoachChart(
                                points = state.chartPoints,
                                thresholdAngle = state.thresholdAngle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp),
                            )
                        }
                    }
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_recommendation_title), style = MaterialTheme.typography.titleLarge)
                            if (state.isAiLoading) {
                                Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.coach_recommendation_loading), style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(state.aiAdvice, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
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

@Composable
private fun CoachChart(
    points: List<PosturePoint>,
    thresholdAngle: Float,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(20.dp),
        ) {
            Text("Данных для выбранного периода пока нет.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Canvas(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    ),
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(16.dp),
    ) {
        val maxAngle = maxOf(15f, thresholdAngle + 2f, points.maxOf { it.angle })
        val widthStep = size.width / (points.size - 1).coerceAtLeast(1)
        val thresholdY = size.height - (thresholdAngle / maxAngle) * size.height

        drawLine(
            color = Color(0xFF4CAF50),
            start = Offset(0f, thresholdY),
            end = Offset(size.width, thresholdY),
            strokeWidth = 3f,
        )

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = widthStep * index
            val y = size.height - (point.angle / maxAngle) * size.height
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 5f),
        )
    }
}

private fun formatAngle(value: Float): String = String.format(Locale.getDefault(), "%.1f°", value)

private fun formatWholeAngle(value: Float): String = "${value.roundToInt()}°"
