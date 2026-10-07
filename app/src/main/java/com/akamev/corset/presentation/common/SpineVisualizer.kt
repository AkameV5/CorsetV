package com.akamev.corset.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akamev.corset.R
import kotlin.math.sin

/**
 * Interactive visual representation of human spine biomechanics.
 * Bends smoothly in real-time depending on the deviation angle from smart corset sensors.
 */
@Composable
fun SpineVisualizer(
    deviationAngle: Float,
    thresholdAngle: Float = 8f,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    showBadges: Boolean = true,
) {
    val animatedAngle by animateFloatAsState(
        targetValue = deviationAngle.coerceIn(0f, 30f),
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "spineAngleAnim",
    )

    // Biomechanical color gradient based on posture deviation
    val postureColor by animateColorAsState(
        targetValue = when {
            animatedAngle <= (thresholdAngle * 0.65f) -> Color(0xFF10B981) // Green / Optimal
            animatedAngle <= thresholdAngle -> Color(0xFFF59E0B) // Amber / Warning
            else -> Color(0xFFEF4444) // Red / Alert
        },
        animationSpec = tween(durationMillis = 300),
        label = "postureColorAnim",
    )

    val postureLabelRes = when {
        animatedAngle <= (thresholdAngle * 0.65f) -> R.string.spine_posture_optimal
        animatedAngle <= thresholdAngle -> R.string.spine_posture_warning
        else -> R.string.spine_posture_alert
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showBadges) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(postureColor),
                    )
                    Text(
                        text = stringResource(postureLabelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = postureColor,
                    )
                }

                Text(
                    text = String.format(java.util.Locale.getDefault(), "%.1f°", animatedAngle),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val centerX = canvasWidth / 2f
                val headCenterY = canvasHeight * 0.12f
                val headRadius = canvasHeight * 0.08f

                // Base of spine (sacrum) stays anchored in the lower middle
                val baseY = canvasHeight * 0.90f
                val baseX = centerX

                // Horizontal deflection of upper thoracic spine based on tilt angle
                // Max curvature deflection at 25-30 degrees
                val maxDeflection = canvasWidth * 0.28f
                val curvatureFactor = (animatedAngle / 25f).coerceIn(-1f, 1f)
                val deflectionX = curvatureFactor * maxDeflection

                // Head position moves horizontally with spinal bending
                val currentHeadX = centerX + deflectionX
                val currentHeadY = headCenterY + (animatedAngle * 0.5f)

                // Neck (cervical spine top)
                val neckX = currentHeadX
                val neckY = currentHeadY + headRadius + 4f

                // Mid-thoracic control point
                val midX = centerX + (deflectionX * 1.15f)
                val midY = canvasHeight * 0.50f

                // Lumbar control point
                val lumbarX = centerX + (deflectionX * 0.45f)
                val lumbarY = canvasHeight * 0.72f

                // 1. Draw glowing baseline guide (straight reference line)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.22f),
                    start = Offset(centerX, headCenterY - headRadius),
                    end = Offset(centerX, baseY + 10f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // 2. Draw Shoulders (clavicle / upper back girdle)
                val shoulderWidth = canvasWidth * 0.22f
                drawLine(
                    color = postureColor.copy(alpha = 0.85f),
                    start = Offset(neckX - shoulderWidth, neckY + 12f),
                    end = Offset(neckX + shoulderWidth, neckY + 12f),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // 3. Draw Head (cervical silhouette)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            postureColor.copy(alpha = 0.35f),
                            postureColor.copy(alpha = 0.12f),
                        ),
                        center = Offset(currentHeadX, currentHeadY),
                        radius = headRadius,
                    ),
                    radius = headRadius,
                    center = Offset(currentHeadX, currentHeadY),
                )
                drawCircle(
                    color = postureColor,
                    radius = headRadius,
                    center = Offset(currentHeadX, currentHeadY),
                    style = Stroke(width = 3.dp.toPx()),
                )

                // 4. Draw Spinal Column (smooth bezier curve)
                val spinePath = Path().apply {
                    moveTo(baseX, baseY)
                    cubicTo(
                        x1 = lumbarX, y1 = lumbarY,
                        x2 = midX, y2 = midY,
                        x3 = neckX, y3 = neckY,
                    )
                }

                // Outer glow stroke
                drawPath(
                    path = spinePath,
                    color = postureColor.copy(alpha = 0.25f),
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                )

                // Main spine core stroke
                drawPath(
                    path = spinePath,
                    color = postureColor,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )

                // 5. Draw Vertebrae nodes along the spine (7 representative vertebrae discs)
                val vertebraeCount = 7
                for (i in 0..vertebraeCount) {
                    val t = i.toFloat() / vertebraeCount.toFloat()
                    // Cubic bezier interpolation formula: B(t) = (1-t)^3*P0 + 3(1-t)^2*t*P1 + 3(1-t)*t^2*P2 + t^3*P3
                    val oneMinusT = 1f - t
                    val vx = oneMinusT * oneMinusT * oneMinusT * baseX +
                            3f * oneMinusT * oneMinusT * t * lumbarX +
                            3f * oneMinusT * t * t * midX +
                            t * t * t * neckX
                    val vy = oneMinusT * oneMinusT * oneMinusT * baseY +
                            3f * oneMinusT * oneMinusT * t * lumbarY +
                            3f * oneMinusT * t * t * midY +
                            t * t * t * neckY

                    val discWidth = (14f - (t * 4f)).dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = Offset(vx - discWidth / 2f, vy),
                        end = Offset(vx + discWidth / 2f, vy),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // Pelvis / base foundation
                val pelvisWidth = canvasWidth * 0.28f
                drawLine(
                    color = postureColor.copy(alpha = 0.7f),
                    start = Offset(baseX - pelvisWidth, baseY),
                    end = Offset(baseX + pelvisWidth, baseY),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
