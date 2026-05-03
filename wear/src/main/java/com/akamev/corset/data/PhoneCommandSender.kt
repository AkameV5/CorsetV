package com.akamev.corset.data

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class PhoneCommandSender(context: Context) {

    private val appContext = context.applicationContext

    suspend fun requestState(): Boolean = send(COMMAND_REQUEST_STATE)

    suspend fun sendCalibrate(): Boolean = send(COMMAND_CALIBRATE)

    suspend fun startSession(): Boolean = send(COMMAND_SESSION_START)

    suspend fun stopSession(): Boolean = send(COMMAND_SESSION_STOP)

    private suspend fun send(path: String): Boolean {
        val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
        if (nodes.isEmpty()) return false

        nodes.forEach { node ->
            Wearable.getMessageClient(appContext)
                .sendMessage(node.id, path, ByteArray(0))
                .await()
        }
        return true
    }

    companion object {
        const val COMMAND_CALIBRATE = "/command/calibrate"
        const val COMMAND_SESSION_START = "/command/session/start"
        const val COMMAND_SESSION_STOP = "/command/session/stop"
        const val COMMAND_REQUEST_STATE = "/command/request_state"
    }
}
