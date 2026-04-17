package com.akamev.corset.data.local

import android.content.Context
import com.akamev.corset.domain.model.ChatHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChatHistoryLocalDataSource(context: Context) {

    private val appContext = context.applicationContext

    suspend fun saveRequest(query: String, answer: String) = withContext(Dispatchers.IO) {
        val safeQuery = query.replace("\n", " ")
        val safeAnswer = answer.replace("\n", " ")
        val entry = "${System.currentTimeMillis()}|||$safeQuery|||$safeAnswer\n"
        appContext.openFileOutput(FILE_NAME, Context.MODE_APPEND).use { output ->
            output.write(entry.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun loadHistory(): List<ChatHistoryItem> = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val validLines = mutableListOf<String>()
        var needsRewrite = false
        val items = mutableListOf<ChatHistoryItem>()

        file.forEachLine(Charsets.UTF_8) { line ->
            val parts = line.split("|||")
            if (parts.size >= 3) {
                val timestamp = parts[0].toLongOrNull()
                if (timestamp != null && now - timestamp < THIRTY_DAYS_MS) {
                    items.add(0, ChatHistoryItem(timestamp = timestamp, query = parts[1], answer = parts[2]))
                    validLines.add(line)
                } else {
                    needsRewrite = true
                }
            }
        }

        if (needsRewrite) {
            rewrite(validLines)
        }
        items
    }

    private fun rewrite(lines: List<String>) {
        appContext.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { output ->
            lines.forEach { line ->
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val FILE_NAME = "chat_history_v2.txt"
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
