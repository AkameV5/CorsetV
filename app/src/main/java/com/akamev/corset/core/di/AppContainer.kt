package com.akamev.corset.core.di

import android.content.Context
import com.akamev.corset.data.bluetooth.BluetoothController
import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.data.local.database.AppDatabase
import com.akamev.corset.data.remote.AiRemoteDataSource
import com.akamev.corset.data.repository.AuthRepository
import com.akamev.corset.data.repository.ChatRepository
import com.akamev.corset.data.repository.PostureRepository
import com.akamev.corset.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val appDatabase by lazy { AppDatabase.getInstance(appContext) }
    val appPreferences by lazy { AppPreferences(appContext) }
    val bluetoothController by lazy { BluetoothController(appContext, appPreferences) }
    val authRepository by lazy { AuthRepository(firebaseAuth) }
    val postureRepository by lazy { PostureRepository(appContext, appDatabase.postureDao()) }
    val chatRepository by lazy { ChatRepository(appContext, appDatabase.chatDao()) }
    val userRepository by lazy {
        UserRepository(
            firebaseAuth = firebaseAuth,
            firestore = firestore,
            appPreferences = appPreferences,
            dailySummaryDao = appDatabase.dailySummaryDao(),
        )
    }
    val aiRemoteDataSource by lazy { AiRemoteDataSource(httpClient) }
}
