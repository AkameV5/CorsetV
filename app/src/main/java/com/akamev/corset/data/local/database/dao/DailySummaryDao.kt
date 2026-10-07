package com.akamev.corset.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.akamev.corset.data.local.database.entity.DailySummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {

    @Query("SELECT * FROM daily_summaries ORDER BY dateKey DESC")
    fun getAllSummaries(): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries ORDER BY dateKey DESC")
    suspend fun getAllSummariesSync(): List<DailySummaryEntity>

    @Query("SELECT * FROM daily_summaries WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getSummary(dateKey: String): DailySummaryEntity?

    @Upsert
    suspend fun upsertSummary(summary: DailySummaryEntity)

    @Upsert
    suspend fun upsertSummaries(summaries: List<DailySummaryEntity>)

    @Query("DELETE FROM daily_summaries WHERE dateKey = :dateKey")
    suspend fun deleteSummary(dateKey: String)

    @Query("DELETE FROM daily_summaries")
    suspend fun clearAll()
}
