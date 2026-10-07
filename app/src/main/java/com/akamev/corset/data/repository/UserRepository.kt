package com.akamev.corset.data.repository

import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.data.local.database.dao.DailySummaryDao
import com.akamev.corset.data.local.database.entity.DailySummaryEntity
import com.akamev.corset.domain.model.DailySummary
import com.akamev.corset.domain.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class UserRepository(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val appPreferences: AppPreferences,
    private val dailySummaryDao: DailySummaryDao,
) {

    fun currentUid(): String? = firebaseAuth.currentUser?.uid

    fun isGuest(): Boolean = appPreferences.isGuestMode() || firebaseAuth.currentUser == null

    suspend fun hasCompletedProfile(uid: String): Boolean {
        if (appPreferences.isGuestMode()) return true
        val document = runCatching { firestore.collection(USERS_COLLECTION).document(uid).get().await() }.getOrNull() ?: return false
        return document.exists() &&
            !document.getString(FIRST_NAME_FIELD).isNullOrBlank() &&
            !document.getString(LAST_NAME_FIELD).isNullOrBlank()
    }

    suspend fun getCurrentUserProfile(): UserProfile? {
        val user = firebaseAuth.currentUser
        if (user == null || appPreferences.isGuestMode()) {
            return UserProfile(
                firstName = appPreferences.getGuestFirstName(),
                lastName = appPreferences.getGuestLastName(),
                email = "",
                currentStreak = appPreferences.getGuestStreak(),
                isGuest = true,
            )
        }

        val document = runCatching { firestore.collection(USERS_COLLECTION).document(user.uid).get().await() }.getOrNull()
        if (document == null || !document.exists()) {
            return UserProfile(
                firstName = appPreferences.getGuestFirstName(),
                lastName = appPreferences.getGuestLastName(),
                email = user.email.orEmpty(),
                currentStreak = appPreferences.getGuestStreak(),
                isGuest = false,
            )
        }

        return UserProfile(
            firstName = document.getString(FIRST_NAME_FIELD).orEmpty(),
            lastName = document.getString(LAST_NAME_FIELD).orEmpty(),
            email = document.getString(EMAIL_FIELD) ?: user.email.orEmpty(),
            currentStreak = document.getLong(CURRENT_STREAK_FIELD) ?: 0,
            isGuest = false,
        )
    }

    suspend fun saveProfile(firstName: String, lastName: String) {
        appPreferences.saveGuestName(firstName, lastName)
        val user = firebaseAuth.currentUser ?: return
        if (appPreferences.isGuestMode()) return

        val payload = mapOf(
            FIRST_NAME_FIELD to firstName,
            LAST_NAME_FIELD to lastName,
            EMAIL_FIELD to user.email,
        )
        runCatching {
            firestore.collection(USERS_COLLECTION).document(user.uid).set(payload).await()
        }
    }

    suspend fun syncTodaySummary(
        dateKey: String,
        score: Int,
        goodPostureMinutes: Long,
        triggerCount: Int,
        averageDeviation: Float,
    ) {
        val now = System.currentTimeMillis()
        val roundedDev = (averageDeviation * 10).roundToInt() / 10f

        // 1. ALWAYS save locally in Room (offline-first, 100% reliable for guest mode)
        dailySummaryDao.upsertSummary(
            DailySummaryEntity(
                dateKey = dateKey,
                score = score,
                goodPostureMinutes = goodPostureMinutes,
                triggerCount = triggerCount,
                averageDeviation = roundedDev,
                updatedAt = now,
            )
        )

        // 2. Sync to cloud only if user is authenticated and not in guest mode
        if (appPreferences.isGuestMode()) return
        val user = firebaseAuth.currentUser ?: return

        val payload = mapOf(
            "dateKey" to dateKey,
            "score" to score,
            "goodPostureMinutes" to goodPostureMinutes,
            "triggerCount" to triggerCount,
            "averageDeviation" to roundedDev,
            "updatedAt" to now,
        )

        runCatching {
            firestore.collection(USERS_COLLECTION)
                .document(user.uid)
                .collection(DAILY_SUMMARIES_COLLECTION)
                .document(dateKey)
                .set(payload)
                .await()
        }
    }

    fun observeDailySummaries(): Flow<List<DailySummary>> {
        return dailySummaryDao.getAllSummaries().map { list ->
            list.map {
                DailySummary(
                    dateKey = it.dateKey,
                    score = it.score,
                    goodPostureMinutes = it.goodPostureMinutes,
                    triggerCount = it.triggerCount,
                    averageDeviation = it.averageDeviation,
                )
            }
        }
    }

    suspend fun loadDailySummaries(): List<DailySummary> {
        val localList = dailySummaryDao.getAllSummariesSync().map {
            DailySummary(
                dateKey = it.dateKey,
                score = it.score,
                goodPostureMinutes = it.goodPostureMinutes,
                triggerCount = it.triggerCount,
                averageDeviation = it.averageDeviation,
            )
        }

        // In guest mode or without signed in user, use offline Room storage directly
        if (appPreferences.isGuestMode() || firebaseAuth.currentUser == null) {
            return localList
        }

        val user = firebaseAuth.currentUser ?: return localList

        val cloudList = runCatching {
            val snapshot = firestore.collection(USERS_COLLECTION)
                .document(user.uid)
                .collection(DAILY_SUMMARIES_COLLECTION)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                val dateKey = doc.getString("dateKey") ?: doc.id
                val score = doc.getLong("score")?.toInt() ?: 100
                val goodPostureMinutes = doc.getLong("goodPostureMinutes") ?: 0L
                val triggerCount = doc.getLong("triggerCount")?.toInt() ?: 0
                val averageDeviation = doc.getDouble("averageDeviation")?.toFloat() ?: 0f
                val updatedAt = doc.getLong("updatedAt") ?: 0L

                DailySummaryEntity(
                    dateKey = dateKey,
                    score = score,
                    goodPostureMinutes = goodPostureMinutes,
                    triggerCount = triggerCount,
                    averageDeviation = averageDeviation,
                    updatedAt = updatedAt,
                )
            }
        }.getOrNull()

        if (!cloudList.isNullOrEmpty()) {
            dailySummaryDao.upsertSummaries(cloudList)
            return cloudList.map {
                DailySummary(
                    dateKey = it.dateKey,
                    score = it.score,
                    goodPostureMinutes = it.goodPostureMinutes,
                    triggerCount = it.triggerCount,
                    averageDeviation = it.averageDeviation,
                )
            }.sortedByDescending { it.dateKey }
        }

        return localList
    }

    private companion object {
        const val USERS_COLLECTION = "users"
        const val DAILY_SUMMARIES_COLLECTION = "daily_summaries"
        const val FIRST_NAME_FIELD = "firstName"
        const val LAST_NAME_FIELD = "lastName"
        const val EMAIL_FIELD = "email"
        const val CURRENT_STREAK_FIELD = "currentStreak"
    }
}
