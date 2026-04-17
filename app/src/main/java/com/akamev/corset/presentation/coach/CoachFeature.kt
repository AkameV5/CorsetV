package com.akamev.corset.presentation.coach

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.akamev.corset.domain.model.DailyStats
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

private const val MOTOR_THRESHOLD = 5f

data class CoachUiState(
    val isMonitoringStarted: Boolean = false,
    val score: Int = 100,
    val currentAngle: Float? = null,
    val thresholdAngle: Float = MOTOR_THRESHOLD,
    val aiAdvice: String = "Нажмите «Откалибровать» в профиле, чтобы начать отслеживание.",
    val isAiLoading: Boolean = false,
    val selectedFilter: CoachFilter = CoachFilter.Live,
    val chartPoints: List<PosturePoint> = emptyList(),
)

class CoachViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoachUiState())
    val uiState: StateFlow<CoachUiState> = _uiState.asStateFlow()

    private val historyPoints = mutableListOf<PosturePoint>()
    private var dailyStats = app.container.appPreferences.loadDailyStats()
    private var baselineAngle = app.container.appPreferences.getBaselineAngle()
    private var isAiBusy = false

    init {
        loadHistory()
        observeTelemetry()
        refreshMonitoringState()
    }

    fun selectFilter(filter: CoachFilter) {
        _uiState.update { it.copy(selectedFilter = filter, chartPoints = computeChartPoints(filter)) }
    }

    private fun refreshMonitoringState() {
        val isMonitoringStarted = app.container.appPreferences.isCalibrationDone()
        baselineAngle = app.container.appPreferences.getBaselineAngle()
        _uiState.update {
            it.copy(
                isMonitoringStarted = isMonitoringStarted,
                aiAdvice = if (isMonitoringStarted) {
                    if (baselineAngle == null) {
                        "Мониторинг запущен. Ждем первые данные, чтобы зафиксировать опорное положение."
                    } else {
                        "Опорный угол: ${String.format(Locale.getDefault(), "%.1f°", baselineAngle)}"
                    }
                } else {
                    "Нажмите «Откалибровать» в профиле, чтобы начать отслеживание."
                },
            )
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
        app.container.postureHistoryLocalDataSource.savePoint(now, deviation)

        val isComfortable = telemetry.motorOn?.not() ?: (deviation < MOTOR_THRESHOLD)
        val todayKey = app.container.appPreferences.todayKey()
        dailyStats = if (dailyStats.dateKey == todayKey) {
            dailyStats.copy(
                goodFrames = dailyStats.goodFrames + if (isComfortable) 1 else 0,
                totalFrames = dailyStats.totalFrames + 1,
            )
        } else {
            DailyStats(
                dateKey = todayKey,
                goodFrames = if (isComfortable) 1 else 0,
                totalFrames = 1,
            )
        }

        if (dailyStats.totalFrames % 20L == 0L) {
            app.container.appPreferences.saveDailyStats(dailyStats)
        }

        val score = if (dailyStats.totalFrames == 0L) {
            100
        } else {
            ((dailyStats.goodFrames.toFloat() / dailyStats.totalFrames.toFloat()) * 100f).toInt()
        }

        _uiState.update {
            it.copy(
                score = score,
                currentAngle = deviation,
                thresholdAngle = MOTOR_THRESHOLD,
                chartPoints = computeChartPoints(it.selectedFilter),
            )
        }

        if (!isAiBusy && (dailyStats.totalFrames == 10L || (dailyStats.totalFrames > 10L && dailyStats.totalFrames % 200L == 0L))) {
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

    private fun requestAdvice(score: Int, averageAngle: Float) {
        isAiBusy = true
        _uiState.update { it.copy(isAiLoading = true) }
        viewModelScope.launch {
            val prompt = "Анализ осанки. Среднее отклонение за 20 минут: ${
                String.format(Locale.US, "%.1f", averageAngle)
            } градусов. Оценка: $score%. Дай один короткий совет на русском языке."
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
                    title = "Мониторинг",
                    subtitle = "Текущий угол отклонения, история за выбранный период и краткие рекомендации по осанке.",
                )
                if (!state.isMonitoringStarted) {
                    EmptyState(
                        title = "Мониторинг пока не активирован",
                        subtitle = "Открой профиль и нажми «Откалибровать», чтобы зафиксировать исходную позу.",
                    )
                } else {
                    TwoColumnStats(
                        firstLabel = "Score",
                        firstValue = "${state.score}%",
                        secondLabel = "Угол",
                        secondValue = state.currentAngle?.let { "${it.toInt()}°" } ?: "--",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoachFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.selectedFilter == filter,
                                onClick = { viewModel.selectFilter(filter) },
                                label = { Text(filter.title) },
                            )
                        }
                    }
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("График", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Зеленая линия показывает момент реакции корсета примерно с ${state.thresholdAngle.toInt()}°.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CoachChart(
                                points = state.chartPoints,
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
                            Text("Рекомендация", style = MaterialTheme.typography.titleLarge)
                            if (state.isAiLoading) {
                                Text("Собираем рекомендацию...", style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(state.aiAdvice, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachChart(
    points: List<PosturePoint>,
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
        val maxAngle = maxOf(15f, points.maxOf { it.angle })
        val widthStep = size.width / (points.size - 1).coerceAtLeast(1)
        val thresholdY = size.height - (MOTOR_THRESHOLD / maxAngle) * size.height

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
