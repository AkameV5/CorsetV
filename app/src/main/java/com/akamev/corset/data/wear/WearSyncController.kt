package com.akamev.corset.data.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

data class WearStateSnapshot(
    val isConnected: Boolean,
    val batteryLevel: Int?,
    val currentAngle: Float?,
    val graphAngles: List<Float>,
    val sessionActive: Boolean,
    val sessionRemainingMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

class WearSyncController(context: Context) {

    private val appContext = context.applicationContext

    fun pushState(snapshot: WearStateSnapshot) {
        val request = PutDataMapRequest.create(PATH_STATE).apply {
            dataMap.putBoolean(KEY_CONNECTED, snapshot.isConnected)
            dataMap.putInt(KEY_BATTERY, snapshot.batteryLevel ?: -1)
            dataMap.putBoolean(KEY_HAS_BATTERY, snapshot.batteryLevel != null)
            dataMap.putFloat(KEY_CURRENT_ANGLE, snapshot.currentAngle ?: -1f)
            dataMap.putBoolean(KEY_HAS_ANGLE, snapshot.currentAngle != null)
            dataMap.putFloatArray(KEY_GRAPH_ANGLES, snapshot.graphAngles.toFloatArray())
            dataMap.putBoolean(KEY_SESSION_ACTIVE, snapshot.sessionActive)
            dataMap.putLong(KEY_SESSION_REMAINING_MS, snapshot.sessionRemainingMs)
            dataMap.putLong(KEY_UPDATED_AT, snapshot.updatedAt)
        }

        Wearable.getDataClient(appContext)
            .putDataItem(request.asPutDataRequest().setUrgent())
    }

    companion object {
        const val PATH_STATE = "/corset/state"
        const val KEY_CONNECTED = "connected"
        const val KEY_BATTERY = "battery"
        const val KEY_HAS_BATTERY = "has_battery"
        const val KEY_CURRENT_ANGLE = "current_angle"
        const val KEY_HAS_ANGLE = "has_angle"
        const val KEY_GRAPH_ANGLES = "graph_angles"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_SESSION_REMAINING_MS = "session_remaining_ms"
        const val KEY_UPDATED_AT = "updated_at"

        const val PATH_COMMAND_CALIBRATE = "/command/calibrate"
        const val PATH_COMMAND_SESSION_START = "/command/session/start"
        const val PATH_COMMAND_SESSION_STOP = "/command/session/stop"
        const val PATH_COMMAND_REQUEST_STATE = "/command/request_state"
    }
}
