package com.akamev.corset.data.repository

import android.content.Context
import com.akamev.corset.data.local.database.dao.PostureDao
import com.akamev.corset.data.local.database.entity.PostureEntity
import com.akamev.corset.domain.model.PosturePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class PostureRepository(
    private val context: Context,
    private val postureDao: PostureDao,
) {

    private var hasMigratedFromCsv = false

    suspend fun savePoint(timestamp: Long, angle: Float) = withContext(Dispatchers.IO) {
        postureDao.insertPoint(PostureEntity(timestamp = timestamp, angle = angle))
    }

    suspend fun loadHistory(hours: Long = 24): List<PosturePoint> = withContext(Dispatchers.IO) {
        ensureLegacyCsvMigrated()
        val since = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        postureDao.getPointsSinceSync(since).map {
            PosturePoint(timestamp = it.timestamp, angle = it.angle)
        }
    }

    fun observeHistory(hours: Long = 24): Flow<List<PosturePoint>> {
        val since = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        return postureDao.getPointsSince(since).map { entities ->
            entities.map { PosturePoint(timestamp = it.timestamp, angle = it.angle) }
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        postureDao.clearAll()
    }

    private suspend fun ensureLegacyCsvMigrated() {
        if (hasMigratedFromCsv) return
        hasMigratedFromCsv = true

        val count = postureDao.getCount()
        if (count > 0) return

        val legacyFile = File(context.applicationContext.filesDir, "posture_history_v2.csv")
        if (!legacyFile.exists()) return

        runCatching {
            val points = buildList {
                legacyFile.forEachLine(Charsets.UTF_8) { line ->
                    val parts = line.split(",")
                    if (parts.size == 2) {
                        val ts = parts[0].toLongOrNull()
                        val ang = parts[1].toFloatOrNull()
                        if (ts != null && ang != null) {
                            add(PostureEntity(timestamp = ts, angle = ang))
                        }
                    }
                }
            }
            if (points.isNotEmpty()) {
                postureDao.insertPoints(points)
            }
        }
    }
}
