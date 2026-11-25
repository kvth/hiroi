package io.github.kvth.hiroi.anki

import android.content.Context

/** Where to find Anki and which deck/field holds the words. Lookups check Anki once both are chosen. */
class AnkiSettings(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("anki", Context.MODE_PRIVATE)

    /** How Hiroi reaches Anki: "ankiconnect" ([url]) or "ankidroid" (AnkiDroid on this phone, directly). */
    var backend: String
        get() = prefs.getString("backend", null)?.takeIf { it in BACKENDS } ?: "ankiconnect"
        set(value) = prefs.edit().putString("backend", value).apply()

    val ankiDroid get() = backend == "ankidroid"

    /** Which Anki the marks' cached notes came from: they're read again for another. */
    val source get() = if (ankiDroid) "ankidroid" else url

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

    /** How {screenshot} is stored: "png" or "jpeg", as Yomitan's screenshot format */
    var screenshotFormat: String
        get() = prefs.getString("screenshot_format", null)?.takeIf { it in SCREENSHOT_FORMATS } ?: "png"
        set(value) = prefs.edit().putString("screenshot_format", value).apply()

    /** JPEG quality, 0–100 (Yomitan's default is 92) */
    var screenshotQuality: Int
        get() = prefs.getInt("screenshot_quality", 92)
        set(value) = prefs.edit().putInt("screenshot_quality", value.coerceIn(0, 100)).apply()

    /** {screenshot} shows just the box or region the panel's text is from, not the whole screen */
    var screenshotCrop: Boolean
        get() = prefs.getBoolean("screenshot_crop", false)
        set(value) = prefs.edit().putBoolean("screenshot_crop", value).apply()

    /**
     * Take the dictionaries' stylesheets out of new cards and keep them in Hiroi, to add to the
     * note type's Styling once ([NoteTypeStyles]), as anki-yomitan-css-dedup does for old notes.
     */
    var dedupStyles: Boolean
        get() = prefs.getBoolean("dedup_styles", false)
        set(value) = prefs.edit().putBoolean("dedup_styles", value).apply()

    /**
     * With [dedupStyles], Hiroi puts the stylesheets into the card format's note type's Styling
     * itself whenever they change. Desktop Anki and AnkiDroid (directly) can; AnkiConnect Android can't.
     */
    var updateNoteTypeCss: Boolean
        get() = prefs.getBoolean("update_note_type_css", false)
        set(value) = prefs.edit().putBoolean("update_note_type_css", value).apply()

    /** [timeoutMs]: for AnkiConnect; AnkiDroid's own API has none. */
    fun client(timeoutMs: Int = 3000): AnkiBackend =
        if (ankiDroid) AnkiDroidApi(context) else AnkiConnect(url, apiKey.ifEmpty { null }, timeoutMs)

    companion object {
        /** AnkiConnect Android on the same phone; desktop Anki's AnkiConnect uses the same port */
        const val DEFAULT_URL = "http://127.0.0.1:8765"

        val SCREENSHOT_FORMATS = listOf("png", "jpeg")
        val BACKENDS = listOf("ankiconnect", "ankidroid")
    }
}
