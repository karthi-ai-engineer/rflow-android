package io.github.karthiaiengineer.rflow

import android.app.Application
import io.github.karthiaiengineer.rflow.ai.Gemini
import io.github.karthiaiengineer.rflow.core.AndroidKeyVault
import io.github.karthiaiengineer.rflow.core.Releases
import io.github.karthiaiengineer.rflow.core.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The app's few long-lived parts, made once. */
class RflowApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    val vault by lazy { AndroidKeyVault(this) }
    val settings by lazy { Settings(this) }
    val gemini by lazy { Gemini(http) }
    val releases by lazy { Releases(http) }
}
