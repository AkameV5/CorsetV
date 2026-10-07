package com.akamev.corset.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "posture_points",
    indices = [Index(value = ["timestamp"])],
)
data class PostureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val angle: Float,
)
