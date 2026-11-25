package io.github.kvth.hiroi.anki

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import io.github.kvth.hiroi.audio.AudioInfo
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.WordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionaryMedia
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.FrequencySort
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan
import io.github.kvth.hiroi.japanese.Deinflector
import java.io.ByteArrayOutputStream
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
    /** The recording picked in the result's audio menu, for {audio}; else the sources' first */
    val audio: AudioInfo? = null,
    /** Translates a text, for {sentence-translation}; throws with the reason when it can't */
    val translate: (String) -> String = { throw IllegalStateException("No translation here") },
    /** The frequency dictionary results are sorted by, for {frequency-sort-rank} */
    val frequencySort: FrequencySort? = null,
) {
    /** The screenshot of the box or region as stored in Anki, for {screenshot-region}; set by [CardCreator] */
    var screenshotRegion: String? = null

    /** The sentence's translation, made once for the card (both passes need it) */
    val sentenceTranslation: Result<String> by lazy {
        val (start, end) = YomitanNoteData.sentence(text, this.start, this.end)
        runCatching { translate(text.substring(start, end).trim()) }
    }
}

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
    private val noteData by lazy { YomitanNoteData(db, db.enabledDictionaries(), DictionarySettings(context).aliases) }
    private val dictionaries by lazy { noteData.dictionariesJson(db.styles()) }

    override fun render(data: CardData, requirements: JSONArray?, media: JSONObject?): RenderedNote {
        val (filled, translationError) = hiroiMarkers(format.fields, data)
        val fields = JSONObject()
        for ((name, value) in filled) fields.put(name, JSONObject().put("value", value).put("overwriteMode", format.overwriteMode(name)))
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
            .put("resultOutputMode", DictionarySettings(context).resultGrouping)
            .put("requirements", requirements ?: JSONArray())
            .put("media", media ?: JSONObject.NULL)
            .put("dictionaries", dictionaries)
        val result = AnkiTemplates.call(context, "createNote", request) as JSONObject
        val out = result.getJSONObject("fields")
        val errors = result.getJSONArray("errors")
        return RenderedNote(
            format.fields.keys.associateWith { out.optString(it) },
            List(errors.length()) { errors.getString(it) } + listOfNotNull(translationError),
            result.getJSONArray("requirements"),
        )
    }
}

/**
 * Fills Hiroi's own markers ([AnkiTemplates.HIROI_MARKERS]) in [fields] before Yomitan's templates
 * fill theirs, each only if a field has it (the sentence is translated only then). Also says why
 * {sentence-translation} is empty, if it is.
 */
fun hiroiMarkers(fields: Map<String, String>, data: CardData): Pair<Map<String, String>, String?> {
    val used = AnkiTemplates.HIROI_MARKERS.filter { marker -> fields.values.any { "{$marker}" in it } }
    if (used.isEmpty()) return fields to null
    var error: String? = null
    val values = used.associate { marker ->
        "{$marker}" to when (marker) {
            "sentence-translation" -> {
                val translation = data.sentenceTranslation
                error = translation.exceptionOrNull()?.let { "sentence-translation: ${it.message ?: it.javaClass.simpleName}" }
                translation.getOrNull()?.let(::markerHtml).orEmpty()
            }
            "kanji-meanings" -> kanjiLines(data.term) { kanji ->
                kanji.meanings.takeIf { it.isNotEmpty() }?.joinToString(", ")
            }
            "kanji-readings" -> kanjiLines(data.term) { kanji ->
                listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi).filter { it.second.isNotBlank() }
                    .takeIf { it.isNotEmpty() }?.joinToString(" · ") { (label, readings) -> "$label " + readings.trim().replace(" ", "、") }
            }
            "conjugation-description" -> data.term.inflections.joinToString("<br>") { name ->
                markerHtml(Deinflector.description(name)?.let { "$name: " + it.replace("\n", " ") } ?: name)
            }
            "text-block" -> markerHtml(data.text.trim())
            "frequency-sort-rank" -> frequencySortRank(data)?.toString().orEmpty()
            "screenshot-region" -> data.screenshotRegion?.let { "<img src=\"${Html.escape(it)}\">" }.orEmpty()
            else -> ""
        }
    }
    return fields.mapValues { (_, value) -> values.entries.fold(value) { v, (marker, html) -> v.replace(marker, html) } } to error
}

/** [text] as HTML, and with no { left that Yomitan would take for a marker */
private fun markerHtml(text: String) = Html.escape(text).replace("\n", "<br>").replace("{", "&#123;")

/**
 * A line per kanji of [term]'s word ("食: eat, food"), from the first kanji dictionary (in their
 * order) that [line] has something for; kanji none has anything for are left out.
 */
private fun kanjiLines(term: TermGroup, line: (KanjiEntry) -> String?): String =
    term.kanji.groupBy { it.character }.mapNotNull { (character, entries) ->
        entries.firstNotNullOfOrNull(line)?.let { markerHtml("$character: $it") }
    }.joinToString("<br>")

/** The word's rank from the frequency dictionary results are sorted by: its lowest for ranks, its highest for occurrence counts */
private fun frequencySortRank(data: CardData): Int? {
    val sort = data.frequencySort ?: return null
    val values = data.term.meta.filter { it.dictionary == sort.dictionary }.mapNotNull { Yomitan.frequency(it, data.term.reading)?.value }
    return if (sort.ascending) values.minOrNull() else values.maxOrNull()
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
    /** A dictionary's image by its path, for glossaries that show one */
    private val dictionaryMedia: (dictionary: String, path: String) -> DictionaryMedia? = { _, _ -> null },
    /** Where {audio} comes from: the audio sources, in their order */
    private val audioSources: List<AudioSource> = listOf(AudioSource("jpod101")),
    private val audio: WordAudio = WordAudio(WordAudio::httpFetch),
    /** How {screenshot} is stored: "png", or "jpeg" with [screenshotQuality] */
    private val screenshotFormat: String = "png",
    private val screenshotQuality: Int = 92,
    /** Where the dictionaries' stylesheets go when they're taken out of the cards; null keeps them in ([StyleDedup]) */
    private val styles: NoteTypeStyles? = null,
) {
    constructor(context: Context, settings: AnkiSettings, clipboard: () -> String? = { null }) :
        this(
            settings.cardFormat, settings.client(), YomitanRenderer(context, settings), settings.wordAudio, clipboard,
            DictionaryDb.get(context)::findMedia, AudioSettings(context).sources,
            screenshotFormat = settings.screenshotFormat, screenshotQuality = settings.screenshotQuality,
            styles = NoteTypeStyles(context).takeIf { settings.dedupStyles },
        )

    /**
     * An added or overwritten note, and the templates that failed on it; [stylesChanged]: its
     * dictionary stylesheets went to [NoteTypeStyles] and changed them (the note type needs them).
     */
    class Result(val noteId: Long, val errors: List<String>, val stylesChanged: Boolean = false)

    /**
     * Adds the note; throws [DuplicateNoteException] if duplicates are prevented. [region] is the
     * screenshot of just the box or region, for {screenshot-region}.
     */
    fun add(data: CardData, screenshot: Bitmap?, region: Bitmap? = null): Result {
        if (format.preventDuplicates && !anki.canAddNote(duplicateCheckNote(data.term))) throw DuplicateNoteException()
        val note = render(data, screenshot, region)
        val (fields, stylesChanged) = withoutStyles(note.fields)
        return Result(anki.addNote(note(format, fields)), note.errors, stylesChanged)
    }

    /**
     * Overwrites note [id] with the note [data] makes, as Yomitan does with duplicates: each field
     * by its overwrite mode (by default only where the existing note's field is empty).
     */
    fun overwrite(id: Long, data: CardData, screenshot: Bitmap?, region: Bitmap? = null): Result {
        val existing = anki.notesInfo(listOf(id)).singleOrNull()?.fields ?: throw AnkiException("Note $id is gone")
        val note = render(data, screenshot, region)
        val (newFields, stylesChanged) = withoutStyles(note.fields)
        val fields = newFields
            .filterKeys { it in existing }
            .mapValues { (name, value) -> CardFormat.overwrite(format.overwriteMode(name), existing.getValue(name), value) }
            .filter { (name, value) -> value != existing[name] }
        if (fields.isNotEmpty()) anki.updateNoteFields(id, fields)
        return Result(id, note.errors, stylesChanged)
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

    /** [fields] without the dictionaries' stylesheets, which go to [styles], if that's on; and whether they changed it. */
    private fun withoutStyles(fields: Map<String, String>): Pair<Map<String, String>, Boolean> {
        val styles = styles ?: return fields to false
        val moved = mutableListOf<String>()
        val out = fields.mapValues { (_, value) -> StyleDedup.field(value).also { moved += it.moved }.text }
        return out to styles.add(moved)
    }

    /**
     * Both passes: the fields, and, if they need media, the fields again with the media stored.
     * {screenshot-region}, Hiroi's own, is stored first, if a field has it.
     */
    private fun render(data: CardData, screenshot: Bitmap?, region: Bitmap?): RenderedNote {
        if (region != null && data.screenshotRegion == null && format.fields.values.any { AnkiTemplates.SCREENSHOT_REGION in it }) {
            val (bytes, extension) = image(region)
            data.screenshotRegion = anki.storeMediaFile("hiroi_screenshot_region_${System.currentTimeMillis()}.$extension", bytes.base64())
        }
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
        val images = JSONArray()
        for (i in 0 until requirements.length()) {
            val requirement = requirements.getJSONObject(i)
            when (requirement.optString("type")) {
                // The recording picked in the audio menu, else the first the sources have, as
                // Yomitan's {audio} does; text-to-speech can't go on a card.
                "audio" -> if (wordAudio) {
                    val picked = data.audio?.takeIf { it.speak == null }?.let { info -> audio.download(info)?.let { WordAudio.Found(info, it) } }
                    val found = picked ?: audio.first(audioSources, data.term.expression, data.term.reading, speech = false)
                    found?.bytes?.let { bytes ->
                        val extension = found.info.url?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
                        media.put("audio", anki.storeMediaFile("hiroi_audio_${data.term.expression}_${data.term.reading}.$extension", bytes.base64()))
                    }
                }
                "screenshot" -> if (screenshot != null) {
                    val (bytes, extension) = image(screenshot)
                    media.put("screenshot", anki.storeMediaFile("hiroi_screenshot_${System.currentTimeMillis()}.$extension", bytes.base64()))
                }
                "clipboardText" -> clipboard()?.let { media.put("clipboardText", it) }
                "popupSelectionText" -> media.put("selectionText", data.selection)
                "textFurigana" -> requirement.optString("text").let { text -> parsed.put(text, YomitanNoteData.parsedText(data.parse(text))) }
                // An image in a glossary: stored in Anki under a name of its own, as Yomitan does
                "dictionaryMedia" -> {
                    val dictionary = requirement.optString("dictionary")
                    val path = requirement.optString("path")
                    val image = dictionaryMedia(dictionary, path) ?: continue
                    val extension = path.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                    val name = anki.storeMediaFile("hiroi_dictionary_media_${images.length() + 1}_${System.currentTimeMillis()}$extension", image.read().base64())
                    images.put(JSONObject().put("dictionary", dictionary).put("path", path).put("fileName", name))
                }
            }
        }
        return media.put("parsedText", parsed).put("dictionaryMedia", images)
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

    private fun ByteArray.base64() = Base64.encodeToString(this, Base64.NO_WRAP)

    /** A screenshot as the settings say to store it (PNG, or JPEG of their quality), and its file extension */
    private fun image(bitmap: Bitmap): Pair<ByteArray, String> {
        val jpeg = screenshotFormat == "jpeg"
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) screenshotQuality else 100, it)
        }.toByteArray()
        return bytes to if (jpeg) "jpg" else "png"
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "opus", "m4a", "aac", "wav", "flac", "webm")
    }
}
