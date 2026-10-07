package com.akamev.corset.data.repository

import android.content.Context
import com.akamev.corset.data.local.database.dao.ChatDao
import com.akamev.corset.data.local.database.entity.ChatMessageEntity
import com.akamev.corset.data.local.database.entity.ChatSessionEntity
import com.akamev.corset.domain.model.ChatMessage
import com.akamev.corset.domain.model.ChatSessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ChatRepository(
    private val context: Context,
    private val chatDao: ChatDao,
) {

    private var hasMigratedLegacy = false

    suspend fun listSessions(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        chatDao.getSessionsSync().map {
            ChatSessionSummary(
                id = it.id,
                title = it.title,
                updatedAt = it.updatedAt,
                preview = it.preview,
            )
        }
    }

    fun observeSessions(): Flow<List<ChatSessionSummary>> {
        return chatDao.getSessions().map { entities ->
            entities.map {
                ChatSessionSummary(
                    id = it.id,
                    title = it.title,
                    updatedAt = it.updatedAt,
                    preview = it.preview,
                )
            }
        }
    }

    suspend fun createSession(title: String = "Новый чат"): String = withContext(Dispatchers.IO) {
        val sessionId = System.currentTimeMillis().toString()
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = sessionId,
            title = title,
            updatedAt = now,
            preview = "Новый диалог",
        )
        chatDao.upsertSession(session)
        sessionId
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        ensureLegacyMigrated()
        chatDao.getMessagesSync(sessionId).map {
            ChatMessage(id = it.id, text = it.text, isUser = it.isUser)
        }
    }

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessages(sessionId).map { entities ->
            entities.map {
                ChatMessage(id = it.id, text = it.text, isUser = it.isUser)
            }
        }
    }

    suspend fun saveExchange(
        sessionId: String,
        query: String,
        answer: String,
    ) = withContext(Dispatchers.IO) {
        val userTimestamp = System.currentTimeMillis()
        val assistantTimestamp = userTimestamp + 1

        val userMessage = ChatMessageEntity(
            id = userTimestamp,
            sessionId = sessionId,
            text = query,
            isUser = true,
            timestamp = userTimestamp,
        )
        val assistantMessage = ChatMessageEntity(
            id = assistantTimestamp,
            sessionId = sessionId,
            text = answer,
            isUser = false,
            timestamp = assistantTimestamp,
        )

        chatDao.insertMessage(userMessage)
        chatDao.insertMessage(assistantMessage)

        val existingSession = chatDao.getSession(sessionId)
        val currentTitle = existingSession?.title
        val newTitle = if (currentTitle.isNullOrBlank() || currentTitle == "Новый чат") {
            buildSessionTitle(query)
        } else {
            currentTitle
        }

        val updatedSession = ChatSessionEntity(
            id = sessionId,
            title = newTitle,
            updatedAt = assistantTimestamp,
            preview = answer.take(PREVIEW_LIMIT).ifBlank { "Пустой ответ" },
        )
        chatDao.upsertSession(updatedSession)
    }

    suspend fun renameSessionIfNeeded(
        sessionId: String,
        titleFromUserMessage: String,
    ) = withContext(Dispatchers.IO) {
        val existing = chatDao.getSession(sessionId) ?: return@withContext
        if (existing.title == "Новый чат") {
            chatDao.updateSessionTitle(sessionId, buildSessionTitle(titleFromUserMessage))
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSession(sessionId)
    }

    private fun buildSessionTitle(query: String): String {
        return query
            .trim()
            .replace('\n', ' ')
            .take(TITLE_LIMIT)
            .ifBlank { "Новый чат" }
    }

    private suspend fun ensureLegacyMigrated() {
        if (hasMigratedLegacy) return
        hasMigratedLegacy = true

        val count = chatDao.getSessionsCount()
        if (count > 0) return

        val filesDir = context.applicationContext.filesDir
        val legacyIndexFile = File(filesDir, "chat_sessions_v1.txt")
        if (!legacyIndexFile.exists()) return

        runCatching {
            legacyIndexFile.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split("|||")
                if (parts.size >= 4) {
                    val id = parts[0]
                    val title = decode(parts[1])
                    val updatedAt = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                    val preview = decode(parts[3])

                    chatDao.upsertSession(
                        ChatSessionEntity(
                            id = id,
                            title = title,
                            updatedAt = updatedAt,
                            preview = preview,
                        )
                    )

                    // Migrate corresponding messages file
                    val msgFile = File(filesDir, "chat_session_$id.txt")
                    if (msgFile.exists()) {
                        val messages = msgFile.readLines(Charsets.UTF_8).mapNotNull { msgLine ->
                            val msgParts = msgLine.split("|||")
                            if (msgParts.size >= 3) {
                                val msgId = msgParts[0].toLongOrNull() ?: return@mapNotNull null
                                val isUser = msgParts[1] == "1"
                                val text = decode(msgParts[2])
                                ChatMessageEntity(
                                    id = msgId,
                                    sessionId = id,
                                    text = text,
                                    isUser = isUser,
                                    timestamp = msgId,
                                )
                            } else null
                        }
                        if (messages.isNotEmpty()) {
                            chatDao.insertMessages(messages)
                        }
                    }
                }
            }
        }
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    }

    private companion object {
        const val TITLE_LIMIT = 48
        const val PREVIEW_LIMIT = 72
    }
}
