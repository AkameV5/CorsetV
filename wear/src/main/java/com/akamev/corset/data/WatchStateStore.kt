package com.akamev.corset.data

import android.content.Context

data class WatchStateSnapshot(
    val isConnected: Boolean = false,
    val batteryLevel: Int? = null,
    val currentAngle: Float? = null,
    val graphAngles: List<Float> = emptyList(),
    val sessionActive: Boolean = false,
    val sessionRemainingMs: Long = 0L,
    val updatedAt: Long = 0L,
)

class WatchStateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: WatchStateSnapshot) {
        prefs.edit()
            .putBoolean(KEY_CONNECTED, snapshot.isConnected)
            .putBoolean(KEY_HAS_BATTERY, snapshot.batteryLevel != null)
            .putInt(KEY_BATTERY, snapshot.batteryLevel ?: -1)
            .putBoolean(KEY_HAS_ANGLE, snapshot.currentAngle != null)
            .putFloat(KEY_CURRENT_ANGLE, snapshot.currentAngle ?: -1f)
            .putString(KEY_GRAPH_ANGLES, snapshot.graphAngles.joinToString(","))
            .putBoolean(KEY_SESSION_ACTIVE, snapshot.sessionActive)
            .putLong(KEY_SESSION_REMAINING_MS, snapshot.sessionRemainingMs)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAt)
            .apply()
    }

    fun load(): WatchStateSnapshot {
        val graphAngles = prefs.getString(KEY_GRAPH_ANGLES, null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            .orEmpty()

        return WatchStateSnapshot(
            isConnected = prefs.getBoolean(KEY_CONNECTED, false),
            batteryLevel = if (prefs.getBoolean(KEY_HAS_BATTERY, false)) prefs.getInt(KEY_BATTERY, -1).takeIf { it >= 0 } else null,
            currentAngle = if (prefs.getBoolean(KEY_HAS_ANGLE, false)) prefs.getFloat(KEY_CURRENT_ANGLE, -1f).takeIf { it >= 0f } else null,
            graphAngles = graphAngles,
            sessionActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false),
            sessionRemainingMs = prefs.getLong(KEY_SESSION_REMAINING_MS, 0L),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    private companion object {
        const val PREFS_NAME = "wear_state_store"
        const val KEY_CONNECTED = "connected"
        const val KEY_HAS_BATTERY = "has_battery"
        const val KEY_BATTERY = "battery"
        const val KEY_HAS_ANGLE = "has_angle"
        const val KEY_CURRENT_ANGLE = "current_angle"
        const val KEY_GRAPH_ANGLES = "graph_angles"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_SESSION_REMAINING_MS = "session_remaining_ms"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
