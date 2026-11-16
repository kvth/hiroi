package io.github.kvth.hiroi.anki

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class DuplicateNoteException : Exception("Already in Anki")

/**
 * Makes an Anki note from a lookup, with the user's card format, and finds the notes it would
 * duplicate. Network and IPC: call off the main thread.
 */
class CardCreator(private val format: CardFormat, private val anki: AnkiConnect, private val wordAudio: Boolean = true) {

    constructor(settings: AnkiSettings) : this(settings.cardFormat, settings.client(), settings.wordAudio)

    /** Adds the note and returns its id; throws [DuplicateNoteException] if duplicates are prevented. */
    fun add(data: CardData, screenshot: Bitmap?): Long {
        if (format.preventDuplicates) {
            // Media isn't stored yet: the check only looks at the first field.
            if (!anki.canAddNote(note(format, format.fields.mapValues { Markers.render(it.value, data) }))) throw DuplicateNoteException()
        }
        return anki.addNote(note(format, render(format, data, screenshot)))
    }

    /**
     * Overwrites note [id] with the note [data] makes, as Yomitan does with duplicates: each field
     * by its overwrite mode (by default the new value, unless it's empty).
     */
    fun overwrite(id: Long, data: CardData, screenshot: Bitmap?) {
        val existing = anki.notesInfo(listOf(id)).singleOrNull()?.fields ?: throw AnkiException("Note $id is gone")
        val fields = render(format, data, screenshot)
            .filterKeys { it in existing }
            .mapValues { (name, value) -> CardFormat.overwrite(format.overwriteMode(name), existing.getValue(name), value) }
            .filter { (name, value) -> value != existing[name] }
        if (fields.isNotEmpty()) anki.updateNoteFields(id, fields)
    }

    /**
     * For each of [cards], the notes it would duplicate (none if duplicates aren't checked), as
     * Yomitan checks them before showing its add buttons: canAddNotes for all at once, then a
     * search for the first field of each one that can't be added.
     */
    fun duplicates(cards: List<CardData>): List<List<Long>> {
        if (!format.complete || !format.checkForDuplicates || cards.isEmpty()) return cards.map { emptyList() }
        val first = format.fields.keys.first()
        val values = cards.map { Markers.render(format.fields.getValue(first), it) }
        // A note with an empty first field can't be added either, but isn't a duplicate.
        val checked = values.indices.filter { values[it].isNotBlank() }
        val notes = checked.map { i -> note(format.copy(duplicateBehavior = "prevent"), mapOf(first to values[i])) }
        val canAdd = anki.canAddNotes(notes)
        val out = MutableList(cards.size) { emptyList<Long>() }
        for ((n, i) in checked.withIndex()) {
            if (!canAdd[n]) out[i] = anki.findNotes(duplicateQuery(format, first, values[i]))
        }
        return out
    }

    /** Notes whose first field is [value], in the duplicate scope. */
    private fun duplicateQuery(format: CardFormat, field: String, value: String): String = buildString {
        when (format.duplicateScope) {
            "deck" -> append(AnkiConnect.deckQuery(format.deck)).append(' ')
            "deck-root" -> append(AnkiConnect.deckQuery(rootDeck(format.deck))).append(' ')
        }
        if (!format.checkAllModels) append("\"note:").append(AnkiConnect.escape(format.model)).append("\" ")
        append('"').append(AnkiConnect.escape(field)).append(':').append(AnkiConnect.escape(value)).append('"')
    }

    /** The fields with media stored in Anki (screenshot, word audio). */
    private fun render(format: CardFormat, data: CardData, screenshot: Bitmap?): Map<String, String> {
        val used = format.fields.values.flatMapTo(HashSet()) { Markers.used(it) }
        val media = HashMap<String, String>()
        if ("screenshot" in used && screenshot != null) {
            val png = ByteArrayOutputStream().also { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val name = anki.storeMediaFile("hiroi_screenshot_${System.currentTimeMillis()}.png", png.base64())
            media["screenshot"] = "<img src=\"$name\">"
        }
        if ("audio" in used && wordAudio) {
            wordAudio(data.term.expression, data.term.reading)?.let { mp3 ->
                val name = anki.storeMediaFile("hiroi_audio_${data.term.expression}_${data.term.reading}.mp3", mp3.base64())
                media["audio"] = "[sound:$name]"
            }
        }
        return format.fields.mapValues { Markers.render(it.value, data, media) }
    }

    /** addNote's note; its options give AnkiConnect the duplicate scope as Yomitan does (deck-root: the top deck with its subdecks). */
    private fun note(format: CardFormat, fields: Map<String, String>): JSONObject = JSONObject()
        .put("deckName", format.deck)
        .put("modelName", format.model)
        .put("fields", JSONObject().also { json -> fields.forEach { (name, value) -> json.put(name, value) } })
        .put("tags", JSONArray(format.tags))
        .put(
            "options",
            JSONObject()
                .put("allowDuplicate", !format.preventDuplicates)
                .put("duplicateScope", if (format.duplicateScope == "collection") "collection" else "deck")
                .put(
                    "duplicateScopeOptions",
                    JSONObject()
                        .put("deckName", if (format.duplicateScope == "deck-root") rootDeck(format.deck) else format.deck)
                        .put("checkChildren", format.duplicateScope == "deck-root")
                        .put("checkAllModels", format.checkAllModels),
                ),
        )

    private fun rootDeck(deck: String) = deck.substringBefore("::")

    /**
     * The word's audio from JapanesePod101, like Yomitan's default audio source. It answers unknown
     * words with a "no audio" recording, which Yomitan recognises by its hash and so does Hiroi.
     */
    private fun wordAudio(expression: String, reading: String): ByteArray? = try {
        val query = "kanji=${URLEncoder.encode(expression, "UTF-8")}&kana=${URLEncoder.encode(reading, "UTF-8")}"
        val connection = URL("https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        try {
            val bytes = connection.inputStream.use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            bytes.takeIf { it.isNotEmpty() && hash != NO_AUDIO_SHA256 }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "No word audio for $expression", e)
        null
    }

    private fun ByteArray.base64() = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "HiroiAnki"
        const val NO_AUDIO_SHA256 = "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"
    }
}
