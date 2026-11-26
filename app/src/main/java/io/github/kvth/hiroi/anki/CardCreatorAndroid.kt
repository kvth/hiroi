package io.github.kvth.hiroi.anki

import android.content.Context
import android.graphics.Bitmap
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.wordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.get
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

// Making cards on the phone: Yomitan's templates run in a WebView ([AnkiTemplates]), screenshots are Bitmaps.

/** The card creator with the user's settings */
fun CardCreator(context: Context, settings: AnkiSettings, clipboard: () -> String? = { null }) =
    CardCreator(
        settings.cardFormat, settings.client(), YomitanRenderer(context, settings), settings.wordAudio, clipboard,
        DictionaryDb.get(context)::findMedia, AudioSettings(context).sources, AudioSettings(context).wordAudio(context),
        screenshotFormat = settings.screenshotFormat, screenshotQuality = settings.screenshotQuality,
        styles = NoteTypeStyles(context).takeIf { settings.dedupStyles },
        updateNoteType = settings.updateNoteTypeCss,
    )

fun NoteTypeStyles(context: Context) = NoteTypeStyles(File(context.filesDir, "note-type-styles.json"))

fun Bitmap.asScreenshot() = Screenshot { jpeg, quality ->
    ByteArrayOutputStream().also { compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, quality, it) }.toByteArray()
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
