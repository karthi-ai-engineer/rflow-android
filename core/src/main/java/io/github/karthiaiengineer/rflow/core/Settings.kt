package io.github.karthiaiengineer.rflow.core

import android.content.Context

/** Light, dark, or the phone's choice, as the desktop's top-bar switch. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** The app's settings (not the key: that is [KeyVault]'s). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme: ThemeChoice
        get() = prefs.getString("theme", null)?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.SYSTEM
        set(value) = prefs.edit().putString("theme", value.name).apply()

    /** The language live translation and Meeting mode translate into. */
    var target: String
        get() = prefs.getString("target", "en")!!
        set(value) = prefs.edit().putString("target", value).apply()

    /** Conversation: the language of the person holding the phone, and of the person across the table. */
    var mine: String
        get() = prefs.getString("mine", "en")!!
        set(value) = prefs.edit().putString("mine", value).apply()
    var theirs: String
        get() = prefs.getString("theirs", "ja")!!
        set(value) = prefs.edit().putString("theirs", value).apply()

    /** Speak the translation: Gemini's own voice, as it streams. */
    var speak: Boolean
        get() = prefs.getBoolean("speak", false)
        set(value) = prefs.edit().putBoolean("speak", value).apply()

    /** The cost saver: only speech is sent, and a session with no words for 15 minutes stops. */
    var saver: Boolean
        get() = prefs.getBoolean("saver", true)
        set(value) = prefs.edit().putBoolean("saver", value).apply()

    /** Debug builds only: a test server standing in for Gemini ("http://10.0.2.2:8765"); blank: Google. */
    var testServer: String
        get() = prefs.getString("test_server", "")!!
        set(value) = prefs.edit().putString("test_server", value.trim()).apply()
}
