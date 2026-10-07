package com.akamev.corset.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akamev.corset.data.local.database.entity.PostureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PostureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: PostureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<PostureEntity>)

    @Query("SELECT * FROM posture_points WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getPointsSince(since: Long): Flow<List<PostureEntity>>

    @Query("SELECT * FROM posture_points WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getPointsSinceSync(since: Long): List<PostureEntity>

    @Query("SELECT COUNT(*) FROM posture_points")
    suspend fun getCount(): Int

    @Query("DELETE FROM posture_points WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long)

    @Query("DELETE FROM posture_points")
    suspend fun clearAll()
}
