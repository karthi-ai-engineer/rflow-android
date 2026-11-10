package io.github.karthiaiengineer.rflow

import android.app.Application
import io.github.karthiaiengineer.rflow.ai.Gemini
import io.github.karthiaiengineer.rflow.ai.LiveTranslate
import io.github.karthiaiengineer.rflow.core.AndroidKeyVault
import io.github.karthiaiengineer.rflow.core.Releases
import io.github.karthiaiengineer.rflow.core.Settings
import io.github.karthiaiengineer.rflow.meeting.MeetingStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The app's few long-lived parts, made once. */
class RflowApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
    }
    val vault by lazy { AndroidKeyVault(this) }
    val settings by lazy { Settings(this) }
    val gemini get() = Gemini(http, restBase())
    val releases by lazy { Releases(http) }
    val meetings by lazy { MeetingStore(this, http, vault) { restBase() to uploadUrl() } }

    /** A debug build may talk to a test server instead of Google (Settings); a release build always talks to Google. */
    private fun testServer(): String? = settings.testServer.takeIf { BuildConfig.DEBUG && it.isNotBlank() }?.trimEnd('/')

    fun restBase() = testServer()?.let { "$it/v1beta" } ?: "https://generativelanguage.googleapis.com/v1beta"
    fun uploadUrl() = testServer()?.let { "$it/upload/v1beta/files" } ?: "https://generativelanguage.googleapis.com/upload/v1beta/files"
    fun liveUrl() = testServer()?.let { it.replaceFirst("http", "ws") + "/ws" } ?: LiveTranslate.URL
}
