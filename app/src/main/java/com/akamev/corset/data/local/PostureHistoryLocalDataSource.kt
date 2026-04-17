package com.akamev.corset.data.local

import android.content.Context
import com.akamev.corset.domain.model.PosturePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PostureHistoryLocalDataSource(context: Context) {

    private val appContext = context.applicationContext

    suspend fun savePoint(timestamp: Long, angle: Float) = withContext(Dispatchers.IO) {
        val entry = "$timestamp,$angle\n"
        appContext.openFileOutput(FILE_NAME, Context.MODE_APPEND).use { output ->
            output.write(entry.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun loadHistory(): List<PosturePoint> = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        val threshold = System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS
        buildList {
            file.forEachLine(Charsets.UTF_8) { line ->
                val parts = line.split(",")
                if (parts.size == 2) {
                    val timestamp = parts[0].toLongOrNull()
                    val angle = parts[1].toFloatOrNull()
                    if (timestamp != null && angle != null && timestamp >= threshold) {
                        add(PosturePoint(timestamp = timestamp, angle = angle))
                    }
                }
            }
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "posture_history_v2.csv"
        const val TWENTY_FOUR_HOURS_MS = 24L * 60 * 60 * 1000
    }
}
