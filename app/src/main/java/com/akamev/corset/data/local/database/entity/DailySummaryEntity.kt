package com.akamev.corset.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey
    val dateKey: String,
    val score: Int,
    val goodPostureMinutes: Long,
    val triggerCount: Int,
    val averageDeviation: Float,
    val updatedAt: Long,
)
