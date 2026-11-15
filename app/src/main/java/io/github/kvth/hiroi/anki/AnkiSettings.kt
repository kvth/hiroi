package io.github.kvth.hiroi.anki

import android.content.Context

/** Where to find Anki and which deck/field holds the words. Lookups check Anki once both are chosen. */
class AnkiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("anki", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(value) = prefs.edit().putString("url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("key", null).orEmpty()
        set(value) = prefs.edit().putString("key", value.trim()).apply()

    var deck: String
        get() = prefs.getString("deck", null).orEmpty()
        set(value) = prefs.edit().putString("deck", value).apply()

    var field: String
        get() = prefs.getString("field", null).orEmpty()
        set(value) = prefs.edit().putString("field", value).apply()

    val enabled get() = deck.isNotEmpty() && field.isNotEmpty()

    fun client() = AnkiConnect(url, apiKey.ifEmpty { null })

    companion object {
        /** AnkiConnect Android on the same phone; desktop Anki's AnkiConnect uses the same port */
        const val DEFAULT_URL = "http://127.0.0.1:8765"
    }
}
