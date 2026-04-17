package com.akamev.corset.data.repository

import com.akamev.corset.domain.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {

    fun currentUid(): String? = firebaseAuth.currentUser?.uid

    suspend fun hasCompletedProfile(uid: String): Boolean {
        val document = firestore.collection(USERS_COLLECTION).document(uid).get().await()
        return document.exists() &&
            !document.getString(FIRST_NAME_FIELD).isNullOrBlank() &&
            !document.getString(LAST_NAME_FIELD).isNullOrBlank()
    }

    suspend fun getCurrentUserProfile(): UserProfile? {
        val user = firebaseAuth.currentUser ?: return null
        val document = firestore.collection(USERS_COLLECTION).document(user.uid).get().await()
        if (!document.exists()) return null

        return UserProfile(
            firstName = document.getString(FIRST_NAME_FIELD).orEmpty(),
            lastName = document.getString(LAST_NAME_FIELD).orEmpty(),
            email = document.getString(EMAIL_FIELD) ?: user.email.orEmpty(),
            currentStreak = document.getLong(CURRENT_STREAK_FIELD) ?: 0,
        )
    }

    suspend fun saveProfile(firstName: String, lastName: String) {
        val user = firebaseAuth.currentUser ?: return
        val payload = mapOf(
            FIRST_NAME_FIELD to firstName,
            LAST_NAME_FIELD to lastName,
            EMAIL_FIELD to user.email,
        )
        firestore.collection(USERS_COLLECTION).document(user.uid).set(payload).await()
    }

    private companion object {
        const val USERS_COLLECTION = "users"
        const val FIRST_NAME_FIELD = "firstName"
        const val LAST_NAME_FIELD = "lastName"
        const val EMAIL_FIELD = "email"
        const val CURRENT_STREAK_FIELD = "currentStreak"
    }
}
