package com.akamev.corset.domain.model

data class UserProfile(
    val firstName: String,
    val lastName: String,
    val email: String,
    val currentStreak: Long = 0,
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

data class DailyStats(
    val dateKey: String,
    val goodFrames: Long,
    val totalFrames: Long,
)

data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
)

data class ChatHistoryItem(
    val timestamp: Long,
    val query: String,
    val answer: String,
)

enum class CoachFilter(val title: String, val periodMs: Long?) {
    Live("Live", null),
    TenMinutes("10m", 10 * 60 * 1000L),
    OneHour("1h", 60 * 60 * 1000L),
    TenHours("10h", 10 * 60 * 60 * 1000L),
    OneDay("24h", 24 * 60 * 60 * 1000L),
}
