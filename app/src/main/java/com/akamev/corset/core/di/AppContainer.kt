package com.akamev.corset.core.di

import android.content.Context
import com.akamev.corset.data.bluetooth.BluetoothController
import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.data.local.ChatHistoryLocalDataSource
import com.akamev.corset.data.local.PostureHistoryLocalDataSource
import com.akamev.corset.data.remote.AiRemoteDataSource
import com.akamev.corset.data.repository.AuthRepository
import com.akamev.corset.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val httpClient by lazy { OkHttpClient() }

    val appPreferences by lazy { AppPreferences(appContext) }
    val bluetoothController by lazy { BluetoothController(appContext, appPreferences) }
    val authRepository by lazy { AuthRepository(firebaseAuth) }
    val userRepository by lazy { UserRepository(firebaseAuth, firestore) }
    val chatHistoryLocalDataSource by lazy { ChatHistoryLocalDataSource(appContext) }
    val postureHistoryLocalDataSource by lazy { PostureHistoryLocalDataSource(appContext) }
    val aiRemoteDataSource by lazy { AiRemoteDataSource(httpClient) }
}
