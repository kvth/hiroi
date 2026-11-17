package io.github.kvth.hiroi.anki

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.TermGroup
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class DuplicateNoteException : Exception("Already in Anki")

/**
 * What a card is made from: the looked-up [term] and the [text] it was found in, where
 * [start]..[end] is the matched word. [parse] splits any text (the sentence) into words with
 * readings, for {sentence-furigana}; [selection] is the text selected in the panel.
 */
class CardData(
    val term: TermGroup,
    val text: String,
    val start: Int,
    val end: Int,
    val documentTitle: String = "",
    val parse: (String) -> List<List<Furigana.Segment>> = { listOf(listOf(Furigana.Segment(it, null))) },
    val selection: String = "",
)

/**
 * A card format's fields filled for a card: [errors] are templates that failed (their field
 * shows {marker-render-error}, as in Yomitan); [requirements] the media the fields need.
 */
class RenderedNote(val fields: Map<String, String>, val errors: List<String>, val requirements: JSONArray)

/**
 * Fills a card format's fields: Yomitan's templates ([YomitanRenderer]) in the app. Without
 * [media] it's the first pass, which also tells which media are needed; with it, the fields
 * get those media's names.
 */
fun interface NoteRenderer {
    fun render(data: CardData, requirements: JSONArray?, media: JSONObject?): RenderedNote
}

/**
 * Renders with Yomitan's own note builder and the user's templates (or Yomitan's default ones);
 * [format] and [templates] can be others, to try them out.
 */
class YomitanRenderer(
    private val context: Context,
    private val settings: AnkiSettings,
    private val format: CardFormat = settings.cardFormat,
    private val templates: String? = settings.fieldTemplates,
) : NoteRenderer {
    private val db = DictionaryDb.get(context)
    private val noteData by lazy { YomitanNoteData(db, db.enabledDictionaries()) }
    private val dictionaries by lazy { noteData.dictionariesJson(db.styles()) }

    override fun render(data: CardData, requirements: JSONArray?, media: JSONObject?): RenderedNote {
        val fields = JSONObject()
        for ((name, value) in format.fields) fields.put(name, JSONObject().put("value", value).put("overwriteMode", format.overwriteMode(name)))
        val request = JSONObject()
            .put("dictionaryEntry", noteData.dictionaryEntry(data.term))
            .put("cardFormat", JSONObject().put("type", "term").put("name", "Hiroi").put("deck", format.deck).put("model", format.model).put("fields", fields).put("icon", "big-circle"))
            .put("context", YomitanNoteData.context(data))
            .put("templates", templates ?: JSONObject.NULL)
            .put("tags", JSONArray(format.tags))
            .put("duplicateScope", format.duplicateScope)
            .put("checkAllModels", format.checkAllModels)
            .put("glossaryLayoutMode", if (settings.compactGlossaries) "compact-popup-anki" else "default")
            .put("compactTags", settings.compactTags)
            .put("requirements", requirements ?: JSONArray())
            .put("media", media ?: JSONObject.NULL)
            .put("dictionaries", dictionaries)
        val result = AnkiTemplates.call(context, "createNote", request) as JSONObject
        val out = result.getJSONObject("fields")
        val errors = result.getJSONArray("errors")
        return RenderedNote(
            format.fields.keys.associateWith { out.optString(it) },
            List(errors.length()) { errors.getString(it) },
            result.getJSONArray("requirements"),
        )
    }
}

/**
 * Makes an Anki note from a lookup, with the user's card format, and finds the notes it would
 * duplicate. Fields are filled as Yomitan fills them, in two passes: the first tells which
 * media the fields need (word audio, screenshot, clipboard, sentence furigana, ...), which are
 * stored in Anki before the second. Network and IPC: call off the main thread.
 */
class CardCreator(
    private val format: CardFormat,
    private val anki: AnkiConnect,
    private val renderer: NoteRenderer,
    private val wordAudio: Boolean = true,
    private val clipboard: () -> String? = { null },
) {
    constructor(context: Context, settings: AnkiSettings, clipboard: () -> String? = { null }) :
        this(settings.cardFormat, settings.client(), YomitanRenderer(context, settings), settings.wordAudio, clipboard)

    /** An added or overwritten note, and the templates that failed on it. */
    class Result(val noteId: Long, val errors: List<String>)

    /** Adds the note; throws [DuplicateNoteException] if duplicates are prevented. */
    fun add(data: CardData, screenshot: Bitmap?): Result {
        if (format.preventDuplicates && !anki.canAddNote(duplicateCheckNote(data.term))) throw DuplicateNoteException()
        val note = render(data, screenshot)
        return Result(anki.addNote(note(format, note.fields)), note.errors)
    }

    /**
     * Overwrites note [id] with the note [data] makes, as Yomitan does with duplicates: each field
     * by its overwrite mode (by default only where the existing note's field is empty).
     */
    fun overwrite(id: Long, data: CardData, screenshot: Bitmap?): Result {
        val existing = anki.notesInfo(listOf(id)).singleOrNull()?.fields ?: throw AnkiException("Note $id is gone")
        val note = render(data, screenshot)
        val fields = note.fields
            .filterKeys { it in existing }
            .mapValues { (name, value) -> CardFormat.overwrite(format.overwriteMode(name), existing.getValue(name), value) }
            .filter { (name, value) -> value != existing[name] }
        if (fields.isNotEmpty()) anki.updateNoteFields(id, fields)
        return Result(id, note.errors)
    }

    /**
     * For each term, the notes a card of it would duplicate (none if duplicates aren't checked),
     * as Yomitan checks them before showing its add buttons: a note with the headword in the first
     * field, canAddNotes for all at once, then a search for each one that can't be added.
     */
    fun duplicates(terms: List<TermGroup>): List<List<Long>> {
        if (!format.complete || !format.checkForDuplicates || terms.isEmpty()) return terms.map { emptyList() }
        val first = format.fields.keys.first()
        val canAdd = anki.canAddNotes(terms.map { duplicateCheckNote(it) })
        return terms.mapIndexed { i, term -> if (canAdd[i]) emptyList() else anki.findNotes(duplicateQuery(first, term.expression)) }
    }

    /** What the duplicate check compares: the headword in the first field, as Yomitan does. */
    private fun duplicateCheckNote(term: TermGroup) =
        note(format.copy(duplicateBehavior = "prevent"), mapOf(format.fields.keys.first() to term.expression))

    /** Notes whose first field is [value], in the duplicate scope. */
    private fun duplicateQuery(field: String, value: String): String = buildString {
        when (format.duplicateScope) {
            "deck" -> append(AnkiConnect.deckQuery(format.deck)).append(' ')
            "deck-root" -> append(AnkiConnect.deckQuery(rootDeck(format.deck))).append(' ')
        }
        if (!format.checkAllModels) append("\"note:").append(AnkiConnect.escape(format.model)).append("\" ")
        append('"').append(AnkiConnect.escape(field)).append(':').append(AnkiConnect.escape(value)).append('"')
    }

    /** Both passes: the fields, and, if they need media, the fields again with the media stored. */
    private fun render(data: CardData, screenshot: Bitmap?): RenderedNote {
        val first = renderer.render(data, null, null)
        if (first.requirements.length() == 0) return first
        return renderer.render(data, first.requirements, media(first.requirements, data, screenshot))
    }

    /**
     * What Yomitan's background page would inject for [requirements]: file names of media stored
     * in Anki, texts, and parsed text for sentence furigana. Dictionary images aren't kept by Hiroi.
     */
    private fun media(requirements: JSONArray, data: CardData, screenshot: Bitmap?): JSONObject {
        val media = JSONObject()
        val parsed = JSONObject()
        for (i in 0 until requirements.length()) {
            val requirement = requirements.getJSONObject(i)
            when (requirement.optString("type")) {
                "audio" -> if (wordAudio) {
                    wordAudio(data.term.expression, data.term.reading)?.let { mp3 ->
                        media.put("audio", anki.storeMediaFile("hiroi_audio_${data.term.expression}_${data.term.reading}.mp3", mp3.base64()))
                    }
                }
                "screenshot" -> if (screenshot != null) {
                    val png = ByteArrayOutputStream().also { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    media.put("screenshot", anki.storeMediaFile("hiroi_screenshot_${System.currentTimeMillis()}.png", png.base64()))
                }
                "clipboardText" -> clipboard()?.let { media.put("clipboardText", it) }
                "popupSelectionText" -> media.put("selectionText", data.selection)
                "textFurigana" -> requirement.optString("text").let { text -> parsed.put(text, YomitanNoteData.parsedText(data.parse(text))) }
            }
        }
        return media.put("parsedText", parsed)
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
