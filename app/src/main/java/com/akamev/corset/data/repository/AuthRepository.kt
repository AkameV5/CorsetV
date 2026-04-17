package com.akamev.corset.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val firebaseAuth: FirebaseAuth,
) {

    fun currentUser(): FirebaseUser? = firebaseAuth.currentUser

    suspend fun login(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun register(email: String, password: String) {
        val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
        result.user?.sendEmailVerification()?.await()
    }

    suspend fun resendVerification(): String? {
        val user = firebaseAuth.currentUser ?: return null
        user.sendEmailVerification().await()
        return user.email
    }

    suspend fun reloadCurrentUser(): FirebaseUser? {
        val user = firebaseAuth.currentUser ?: return null
        user.reload().await()
        return firebaseAuth.currentUser
    }

    fun signOut() {
        firebaseAuth.signOut()
    }
}
