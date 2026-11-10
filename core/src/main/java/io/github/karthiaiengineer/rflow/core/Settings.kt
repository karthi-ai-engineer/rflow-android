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
}
