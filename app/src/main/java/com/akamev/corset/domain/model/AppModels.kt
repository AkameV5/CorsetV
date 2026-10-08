package com.akamev.corset.domain.model

import androidx.annotation.StringRes
import com.akamev.corset.R

data class UserProfile(
    val firstName: String,
    val lastName: String,
    val email: String,
    val currentStreak: Long = 0,
    val isGuest: Boolean = false,
)

data class DailySummary(
    val dateKey: String,
    val score: Int,
    val goodPostureMinutes: Long,
    val triggerCount: Int,
    val averageDeviation: Float,
)

data class Telemetry(
    val angle: Float,
    val motorOn: Boolean?,
    val batteryLevel: Int?,
)

data class PosturePoint(
    val timestamp: Long,
    val angle: Float,
)

data class DeviceState(
    val hasSavedDevice: Boolean = false,
    val isConnected: Boolean = false,
    val batteryLevel: Int? = null,
    val isBatteryStale: Boolean = false,
)

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isSaved: Boolean = false,
)

data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
)

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
)

data class AiChatTurn(
    val role: AiChatRole,
    val text: String,
)

enum class AiChatRole {
    User,
    Model,
}

enum class PostureAlertMode(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val angle: Float?,
) {
    Comfort(
        titleRes = R.string.coach_mode_comfort_title,
        descriptionRes = R.string.coach_mode_comfort_desc,
        angle = 8f,
    ),
    Balanced(
        titleRes = R.string.coach_mode_balanced_title,
        descriptionRes = R.string.coach_mode_balanced_desc,
        angle = 6f,
    ),
    Precise(
        titleRes = R.string.coach_mode_precise_title,
        descriptionRes = R.string.coach_mode_precise_desc,
        angle = 5f,
    ),
    Custom(
        titleRes = R.string.coach_mode_custom_title,
        descriptionRes = R.string.coach_mode_custom_desc,
        angle = null,
    ),
    ;

    fun resolveAngle(customAngle: Float): Float = angle ?: customAngle
}

enum class CoachFilter(@StringRes val titleRes: Int, val periodMs: Long?) {
    Live(R.string.coach_filter_live, null),
    TenMinutes(R.string.coach_filter_10m, 10 * 60 * 1000L),
    OneHour(R.string.coach_filter_1h, 60 * 60 * 1000L),
    TenHours(R.string.coach_filter_10h, 10 * 60 * 60 * 1000L),
    OneDay(R.string.coach_filter_24h, 24 * 60 * 60 * 1000L),
}
