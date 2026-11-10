package io.github.karthiaiengineer.rflow.core

/**
 * The languages live translation can translate into: the name shown and the code Gemini Live Translate takes. The same
 * list as Rflow for Windows (`sst/live/contracts.py` LANGUAGES); the language spoken is found by the model itself.
 */
object Languages {
    val ALL: List<Pair<String, String>> = listOf(
        "English" to "en", "Japanese" to "ja", "Tamil" to "ta", "Hindi" to "hi", "Chinese (Simplified)" to "zh-CN",
        "Chinese (Traditional)" to "zh-TW", "Korean" to "ko", "Spanish" to "es", "French" to "fr", "German" to "de",
        "Italian" to "it", "Portuguese" to "pt", "Dutch" to "nl", "Russian" to "ru", "Arabic" to "ar", "Telugu" to "te",
        "Malayalam" to "ml", "Kannada" to "kn", "Bengali" to "bn", "Marathi" to "mr", "Thai" to "th",
        "Vietnamese" to "vi", "Indonesian" to "id", "Turkish" to "tr", "Polish" to "pl",
    )

    fun name(code: String): String = ALL.firstOrNull { it.second == code }?.first ?: code

    /** "ja-JP" and "ja" are the same language here: Google's answers carry either. */
    fun same(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() && a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
}
