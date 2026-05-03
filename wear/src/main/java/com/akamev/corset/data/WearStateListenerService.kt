package com.akamev.corset.data

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearStateListenerService : WearableListenerService(), DataClient.OnDataChangedListener {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = WatchStateStore(this)
        dataEvents.forEach { event ->
            val item = event.dataItem ?: return@forEach
            if (item.uri.path != PATH_STATE) return@forEach

            val dataMap = DataMapItem.fromDataItem(item).dataMap
            store.save(
                WatchStateSnapshot(
                    isConnected = dataMap.getBoolean(KEY_CONNECTED, false),
                    batteryLevel = if (dataMap.getBoolean(KEY_HAS_BATTERY, false)) {
                        dataMap.getInt(KEY_BATTERY).takeIf { it >= 0 }
                    } else {
                        null
                    },
                    currentAngle = if (dataMap.getBoolean(KEY_HAS_ANGLE, false)) {
                        dataMap.getFloat(KEY_CURRENT_ANGLE).takeIf { it >= 0f }
                    } else {
                        null
                    },
                    graphAngles = dataMap.getFloatArray(KEY_GRAPH_ANGLES)?.toList().orEmpty(),
                    sessionActive = dataMap.getBoolean(KEY_SESSION_ACTIVE, false),
                    sessionRemainingMs = dataMap.getLong(KEY_SESSION_REMAINING_MS, 0L),
                    updatedAt = dataMap.getLong(KEY_UPDATED_AT, 0L),
                ),
            )
        }
    }

    private companion object {
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
    }
}
