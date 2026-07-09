package com.akamev.corset.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberScalingLazyListState

@Composable
fun WearApp() {
    val context = LocalContext.current
    val viewModel: WearViewModel = viewModel(factory = WearViewModel.factory(context))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(1800)
            viewModel.consumeMessage()
        }
    }

    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    HeroCard(
                        angleLabel = state.currentAngleLabel,
                        summary = state.summaryLabel,
                    )
                }
                item {
                    GraphCard(points = state.graphAngles)
                }
                item {
                    Chip(
                        onClick = {
                            if (state.sessionActive) {
                                viewModel.stopSession()
                            } else {
                                viewModel.startSession()
                            }
                        },
                        label = {
                            Text(
                                text = if (state.sessionActive) "Stop session" else "Start session",
                                textAlign = TextAlign.Center,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.message != null) {
                    item {
                        MessageCard(state.message.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    angleLabel: String,
    summary: String,
) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = angleLabel,
                style = MaterialTheme.typography.display2,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GraphCard(
    points: List<Float>,
) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Posture graph",
                style = MaterialTheme.typography.title3,
            )
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            color = MaterialTheme.colors.surface,
                            shape = RoundedCornerShape(12.dp),
                        ),
                ) {
                    Text(
                        text = "Waiting for live data",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.body2,
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            color = MaterialTheme.colors.surface,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
                ) {
                    val maxAngle = maxOf(10f, points.maxOrNull() ?: 0f)
                    val stepX = size.width / (points.size - 1).coerceAtLeast(1)
                    val path = Path()

                    points.forEachIndexed { index, point ->
                        val x = stepX * index
                        val y = size.height - (point / maxAngle) * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = Color(0xFFFF8A3D),
                        style = Stroke(width = 4f),
                    )
                    drawLine(
                        color = Color(0xFF6BCB77),
                        start = Offset(0f, size.height * 0.45f),
                        end = Offset(size.width, size.height * 0.45f),
                        strokeWidth = 2f,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.body2,
        )
    }
}
