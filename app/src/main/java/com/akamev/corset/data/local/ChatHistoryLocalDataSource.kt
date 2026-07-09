package com.akamev.corset.data.local

import android.content.Context
import com.akamev.corset.domain.model.ChatMessage
import com.akamev.corset.domain.model.ChatSessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ChatHistoryLocalDataSource(context: Context) {

    private val appContext = context.applicationContext

    suspend fun listSessions(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        val sessions = loadSessionIndex()
            .filter { System.currentTimeMillis() - it.updatedAt < THIRTY_DAYS_MS }
            .sortedByDescending { it.updatedAt }

        rewriteSessionIndex(sessions)
        sessions
    }

    suspend fun createSession(title: String = "Новый чат"): String = withContext(Dispatchers.IO) {
        val sessionId = System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        val session = ChatSessionSummary(
            id = sessionId,
            title = title,
            updatedAt = now,
            preview = "Новый диалог",
        )
        val sessions = loadSessionIndex()
            .filterNot { it.id == sessionId }
            .plus(session)
            .sortedByDescending { it.updatedAt }
        rewriteSessionIndex(sessions)
        sessionId
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val file = sessionFile(sessionId)
        if (!file.exists()) return@withContext emptyList()

        file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val parts = line.split(DELIMITER)
                if (parts.size < 3) return@mapNotNull null
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val isUser = parts[1] == "1"
                val text = decode(parts[2])
                ChatMessage(id = id, text = text, isUser = isUser)
            }
    }

    suspend fun saveExchange(
        sessionId: String,
        query: String,
        answer: String,
    ) = withContext(Dispatchers.IO) {
        val file = sessionFile(sessionId)
        file.parentFile?.mkdirs()
        val userId = System.currentTimeMillis()
        val assistantId = userId + 1

        appContext.openFileOutput(sessionFileName(sessionId), Context.MODE_APPEND).use { output ->
            output.write("${userId}${DELIMITER}1${DELIMITER}${encode(query)}\n".toByteArray(Charsets.UTF_8))
            output.write("${assistantId}${DELIMITER}0${DELIMITER}${encode(answer)}\n".toByteArray(Charsets.UTF_8))
        }

        upsertSession(
            sessionId = sessionId,
            title = buildSessionTitle(query),
            preview = answer.take(PREVIEW_LIMIT),
            updatedAt = assistantId,
        )
    }

    suspend fun renameSessionIfNeeded(
        sessionId: String,
        titleFromUserMessage: String,
    ) = withContext(Dispatchers.IO) {
        val sessions = loadSessionIndex()
        val target = sessions.firstOrNull { it.id == sessionId } ?: return@withContext
        if (target.title != "Новый чат") return@withContext

        val updated = sessions.map { session ->
            if (session.id == sessionId) session.copy(title = buildSessionTitle(titleFromUserMessage)) else session
        }
        rewriteSessionIndex(updated.sortedByDescending { it.updatedAt })
    }

    private fun upsertSession(
        sessionId: String,
        title: String,
        preview: String,
        updatedAt: Long,
    ) {
        val sessions = loadSessionIndex()
        val existing = sessions.firstOrNull { it.id == sessionId }
        val updatedTitle = existing?.title
            ?.takeUnless { it == "Новый чат" }
            ?: title

        val updatedSessions = sessions
            .filterNot { it.id == sessionId }
            .plus(
                ChatSessionSummary(
                    id = sessionId,
                    title = updatedTitle,
                    updatedAt = updatedAt,
                    preview = preview.ifBlank { "Пустой ответ" },
                ),
            )
            .sortedByDescending { it.updatedAt }

        rewriteSessionIndex(updatedSessions)
    }

    private fun loadSessionIndex(): List<ChatSessionSummary> {
        val file = File(appContext.filesDir, SESSIONS_FILE_NAME)
        if (!file.exists()) return emptyList()

        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val parts = line.split(DELIMITER)
            if (parts.size < 4) return@mapNotNull null

            val updatedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            ChatSessionSummary(
                id = parts[0],
                title = decode(parts[1]),
                updatedAt = updatedAt,
                preview = decode(parts[3]),
            )
        }
    }

    private fun rewriteSessionIndex(
        sessions: List<ChatSessionSummary>,
    ) {
        appContext.openFileOutput(SESSIONS_FILE_NAME, Context.MODE_PRIVATE).use { output ->
            sessions.forEach { session ->
                val line = buildString {
                    append(session.id)
                    append(DELIMITER)
                    append(encode(session.title))
                    append(DELIMITER)
                    append(session.updatedAt)
                    append(DELIMITER)
                    append(encode(session.preview))
                    append('\n')
                }
                output.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun sessionFile(sessionId: String): File = File(appContext.filesDir, sessionFileName(sessionId))

    private fun sessionFileName(sessionId: String): String = "$SESSION_FILE_PREFIX$sessionId.txt"

    private fun buildSessionTitle(query: String): String {
        return query
            .trim()
            .replace('\n', ' ')
            .take(TITLE_LIMIT)
            .ifBlank { "Новый чат" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val DELIMITER = "|||"
        const val SESSIONS_FILE_NAME = "chat_sessions_v1.txt"
        const val SESSION_FILE_PREFIX = "chat_session_"
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        const val TITLE_LIMIT = 48
        const val PREVIEW_LIMIT = 72
    }
}
