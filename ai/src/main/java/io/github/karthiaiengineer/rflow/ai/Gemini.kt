package io.github.karthiaiengineer.rflow.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** The Gemini models Rflow uses, the same as Rflow for Windows (`sst/costs.py`, `sst/engines/cloud.py`). */
object Models {
    const val TRANSCRIBE = "gemini-3.5-transcribe"
    const val FLASH_LITE = "gemini-3.5-flash-lite"
    const val MINUTES = "gemini-3.6-flash"
    const val LIVE_TRANSLATE = "gemini-3.5-live-translate-preview"
}

/** What "Test key" found. */
sealed interface KeyCheck {
    data object Works : KeyCheck
    data object Rejected : KeyCheck
    data class Failed(val reason: String) : KeyCheck
}

/** Google Gemini over REST. The key goes in a header (never the URL, which can end up in logs). */
class Gemini(
    private val http: OkHttpClient,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    /** Lists one model: it proves the key works and costs nothing. */
    suspend fun check(key: String): KeyCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/models?pageSize=1").header("x-goog-api-key", key.trim()).build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> KeyCheck.Works
                    response.code in setOf(400, 401, 403) -> KeyCheck.Rejected
                    else -> KeyCheck.Failed(message(response.body?.string()) ?: "Gemini answered ${response.code}")
                }
            }
        } catch (e: IOException) {
            KeyCheck.Failed("No connection to Gemini")
        }
    }

    private fun message(body: String?): String? =
        body?.let { runCatching { JSONObject(it).getJSONObject("error").getString("message") }.getOrNull() }
}
