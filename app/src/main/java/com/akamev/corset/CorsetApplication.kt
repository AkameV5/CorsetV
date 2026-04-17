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
        startBluetoothService()
    }

    private fun startBluetoothService() {
        val serviceIntent = Intent(this, BluetoothLeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
