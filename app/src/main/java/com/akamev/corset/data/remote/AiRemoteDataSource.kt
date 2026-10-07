package com.akamev.corset.data.remote

import com.akamev.corset.domain.model.AiChatRole
import com.akamev.corset.domain.model.AiChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class AiRemoteDataSource(
    private val httpClient: OkHttpClient,
) {

    suspend fun requestChat(
        systemInstruction: String,
        conversation: List<AiChatTurn>,
    ): String = withContext(Dispatchers.IO) {
        val modelsToTry = listOf("openai", "openai-fast")
        var lastException: Exception? = null

        for (attempt in 1..4) {
            val currentModel = modelsToTry[(attempt - 1) % modelsToTry.size]
            val jsonBody = JSONObject().apply {
                val messagesArray = JSONArray().apply {
                    if (systemInstruction.isNotBlank()) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemInstruction)
                        })
                    }
                    conversation.forEach { turn ->
                        put(JSONObject().apply {
                            val roleStr = when (turn.role) {
                                AiChatRole.User -> "user"
                                AiChatRole.Model -> "assistant"
                            }
                            put("role", roleStr)
                            put("content", turn.text)
                        })
                    }
                }
                put("messages", messagesArray)
                put("model", currentModel)
                put("jsonMode", false)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(requestBody)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()?.trim()
                    if (response.code == 429) {
                        throw IOException("Сервер перегружен (429) для модели $currentModel. Пробуем резервную...")
                    }
                    if (!response.isSuccessful || responseBody.isNullOrBlank() || responseBody == "{}" || responseBody.startsWith("{\"error\":")) {
                        throw IOException("Некорректный ответ AI: ${response.code} ${response.message}")
                    }
                    return@withContext responseBody
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 4) {
                    // Backoff delay: 1.5s, 3s, 4.5s
                    kotlinx.coroutines.delay(attempt * 1500L)
                }
            }
        }
        throw lastException ?: IOException("Не удалось соединиться с сервером AI после нескольких попыток")
    }
}
