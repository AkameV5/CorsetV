package com.akamev.corset

import android.app.Application
import android.content.Intent
import android.os.Build
import com.akamev.corset.core.di.AppContainer
import com.akamev.corset.data.bluetooth.BluetoothLeService

class CorsetApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (container.bluetoothController.hasSavedDevice() && container.appPreferences.isFocusSessionActive()) {
            ensureBluetoothServiceRunning()
        }
    }

    fun ensureBluetoothServiceRunning() {
        if (!container.bluetoothController.hasSavedDevice()) return
        val shouldRun = container.bluetoothController.deviceState.value.isConnected ||
            container.appPreferences.isFocusSessionActive()
        if (!shouldRun) return
        val serviceIntent = Intent(this, BluetoothLeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    fun stopBluetoothService() {
        stopService(Intent(this, BluetoothLeService::class.java))
    }
}
