package com.akamev.corset.data.wear
import com.akamev.corset.CorsetApplication
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneWearBridgeService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val app = application as CorsetApplication
        val prefs = app.container.appPreferences
        val bluetoothController = app.container.bluetoothController

        when (messageEvent.path) {
            WearSyncController.PATH_COMMAND_CALIBRATE -> {
                if (bluetoothController.deviceState.value.isConnected) {
                    bluetoothController.writeCommand("SET")
                    prefs.setCalibrationDone(true)
                    prefs.clearBaselineAngle()
                }
            }

            WearSyncController.PATH_COMMAND_SESSION_START -> {
                prefs.startFocusSession(45 * 60 * 1000L)
                app.ensureBluetoothServiceRunning()
            }

            WearSyncController.PATH_COMMAND_SESSION_STOP -> {
                prefs.clearFocusSession()
                app.ensureBluetoothServiceRunning()
            }

            WearSyncController.PATH_COMMAND_REQUEST_STATE -> {
                if (bluetoothController.hasSavedDevice()) {
                    app.ensureBluetoothServiceRunning()
                } else {
                    WearSyncController(this).pushState(
                        WearStateSnapshot(
                            isConnected = false,
                            batteryLevel = null,
                            currentAngle = null,
                            graphAngles = emptyList(),
                            sessionActive = prefs.isFocusSessionActive(),
                            sessionRemainingMs = prefs.getFocusSessionRemainingMs(),
                        ),
                    )
                }
            }
        }

        super.onMessageReceived(messageEvent)
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as CorsetApplication
        if (app.container.bluetoothController.hasSavedDevice()) {
            app.ensureBluetoothServiceRunning()
        }
    }
}
