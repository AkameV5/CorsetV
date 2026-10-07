package com.akamev.corset.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AiRemoteDataSource(
    private val httpClient: OkHttpClient,
) {

    suspend fun requestText(prompt: String): String = withContext(Dispatchers.IO) {
        val url = "https://text.pollinations.ai/${URLEncoder.encode(prompt, StandardCharsets.UTF_8.name())}"
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()?.trim()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                throw IOException("Empty AI response")
            }
            body
        }
    }
}
