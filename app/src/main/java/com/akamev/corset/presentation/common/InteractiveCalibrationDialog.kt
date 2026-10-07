package com.akamev.corset.presentation.common

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.akamev.corset.R
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class CalibrationWizardStep {
    AlignPosture,
    HoldingStill,
    Completed,
}

/**
 * Interactive Calibration Master Modal Dialog.
 * Guides the user through:
 * 1. Posture alignment with real-time spine visualization
 * 2. 3-second stillness lock with countdown
 * 3. Haptic confirmation and saving baseline angle
 */
@Composable
fun InteractiveCalibrationDialog(
    currentAngle: Float?,
    isConnected: Boolean,
    onDismissRequest: () -> Unit,
    onCalibrateConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(CalibrationWizardStep.AlignPosture) }
    var countdownSeconds by remember { mutableIntStateOf(3) }

    // Track stability (detect if user is moving excessively)
    var previousAngle by remember { mutableFloatStateOf(currentAngle ?: 0f) }
    var isStable by remember { mutableStateOf(true) }

    val safeCurrentAngle = currentAngle ?: 0f

    LaunchedEffect(currentAngle) {
        if (currentAngle != null) {
            val delta = abs(currentAngle - previousAngle)
            isStable = delta < 1.8f // considered still if movement < 1.8 degrees
            previousAngle = currentAngle
        }
    }

    // Stillness countdown effect
    LaunchedEffect(currentStep) {
        if (currentStep == CalibrationWizardStep.HoldingStill) {
            countdownSeconds = 3
            while (countdownSeconds > 0) {
                delay(1000)
                countdownSeconds -= 1
            }
            // Trigger haptic feedback
            triggerHapticFeedback(context)
            onCalibrateConfirmed()
            currentStep = CalibrationWizardStep.Completed
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(32.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.calibration_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "calibrationStepContent",
                ) { step ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (step) {
                            CalibrationWizardStep.AlignPosture -> {
                                Text(
                                    text = stringResource(R.string.calibration_step1_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.calibration_step1_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Live Spine preview
                                SpineVisualizer(
                                    deviationAngle = safeCurrentAngle,
                                    thresholdAngle = 8f,
                                    height = 180.dp,
                                )

                                // Stability indicator badge
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isStable)
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isStable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                    )
                                    Text(
                                        text = stringResource(
                                            if (isStable) R.string.calibration_stability_good
                                            else R.string.calibration_stability_moving
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isStable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = onDismissRequest,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Text(stringResource(R.string.calibration_cancel_btn))
                                    }
                                    Button(
                                        onClick = { currentStep = CalibrationWizardStep.HoldingStill },
                                        enabled = isConnected,
                                        modifier = Modifier.weight(1.3f),
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Text(stringResource(R.string.calibration_start_btn))
                                    }
                                }
                            }

                            CalibrationWizardStep.HoldingStill -> {
                                Text(
                                    text = stringResource(R.string.calibration_step2_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.calibration_step2_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        progress = { (3 - countdownSeconds) / 3f },
                                        modifier = Modifier.size(150.dp),
                                        strokeWidth = 8.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "$countdownSeconds",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.calibration_current_posture, String.format(java.util.Locale.getDefault(), "%.1f°", safeCurrentAngle)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            CalibrationWizardStep.Completed -> {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(52.dp),
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.calibration_step3_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.calibration_step3_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Button(
                                    onClick = onDismissRequest,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(stringResource(R.string.calibration_done_btn))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun triggerHapticFeedback(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        }
    } catch (_: Exception) {
        // Fallback silently if device vibration is not allowed or unavailable
    }
}
