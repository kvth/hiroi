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

    /** How cards made from the overlay look (see CardFormat). */
    var cardFormat: CardFormat
        get() = prefs.getString("card_format", null)?.let { runCatching { CardFormat.fromJson(it) }.getOrNull() } ?: CardFormat()
        set(value) = prefs.edit().putString("card_format", value.toJson()).apply()

    /** Yomitan's Anki card templates (Handlebars) the fields are rendered with; null for Yomitan's default ones. */
    var fieldTemplates: String?
        get() = prefs.getString("field_templates", null)
        set(value) = prefs.edit().putString("field_templates", value).apply()

    /** Yomitan's "compact" glossary layout: {glossary} without the list around single definitions' entries. */
    var compactGlossaries: Boolean
        get() = prefs.getBoolean("compact_glossaries", false)
        set(value) = prefs.edit().putBoolean("compact_glossaries", value).apply()

    /** Yomitan's "compact tags": a definition's tags left out where they repeat the previous definition's. */
    var compactTags: Boolean
        get() = prefs.getBoolean("compact_tags", false)
        set(value) = prefs.edit().putBoolean("compact_tags", value).apply()

    /** Fill {audio} with word audio from JapanesePod101, as Yomitan's default audio source does. */
    var wordAudio: Boolean
        get() = prefs.getBoolean("word_audio", true)
        set(value) = prefs.edit().putBoolean("word_audio", value).apply()

    fun client(timeoutMs: Int = 3000) = AnkiConnect(url, apiKey.ifEmpty { null }, timeoutMs)

    companion object {
        /** AnkiConnect Android on the same phone; desktop Anki's AnkiConnect uses the same port */
        const val DEFAULT_URL = "http://127.0.0.1:8765"
    }
}
