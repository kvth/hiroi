package io.github.kvth.hiroi.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import io.github.kvth.hiroi.R
import java.util.UUID

/**
 * AnkiDroid on this phone, through its own API for other apps (its content provider,
 * FlashCardsContract): what AnkiConnect Android does, without that app running. Every change is
 * made by AnkiDroid itself, on its open collection; Hiroi never touches the collection file.
 * Needs AnkiDroid's permission [PERMISSION], which the user grants in Hiroi's Anki settings.
 */
class AnkiDroidApi(context: Context) : AnkiBackend {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    private class Model(val id: Long, val name: String, val fields: List<String>)

    /** Read once per client; clients are made for each task, so they don't go stale. */
    private val models: List<Model> by lazy {
        query(MODELS, arrayOf(MODEL_ID, MODEL_NAME, MODEL_FIELD_NAMES)) { c ->
            Model(c.getLong(0), c.getString(1), splitFields(c.getString(2)))
        }
    }

    private val decks: Map<String, Long> by lazy {
        query(DECKS, arrayOf(DECK_NAME, DECK_ID)) { c -> c.getString(0) to c.getLong(1) }.toMap()
    }

    private fun model(name: String) = models.firstOrNull { it.name == name } ?: throw AnkiException("model was not found: $name")

    override fun deckNames(): List<String> = decks.keys.sorted()

    override fun modelNames(): List<String> = models.map { it.name }.sorted()

    override fun modelFieldNames(model: String): List<String> = model(model).fields

    /** One search per note, with Anki's own duplicate search ([duplicateSearch]). */
    override fun canAddNotes(notes: List<AnkiBackend.NewNote>): List<Boolean> = notes.map { note ->
        val model = model(note.model)
        val first = note.fields[model.fields.first()].orEmpty()
        when {
            first.isBlank() -> false
            note.allowDuplicate -> true
            else -> {
                val ids = if (note.checkAllModels) models.map { it.id } else listOf(model.id)
                val deck = note.scopeDeck.takeIf { note.duplicateScope == "deck" }
                findNotes(duplicateSearch(first, ids, deck, note.checkChildren)).isEmpty()
            }
        }
    }

    /**
     * As AnkiConnect adds it: refused when empty or (by its options) a duplicate. AnkiDroid adds
     * the cards to the note type's deck; they're moved to [AnkiBackend.NewNote.deck] right after.
     */
    override fun addNote(note: AnkiBackend.NewNote): Long {
        val model = model(note.model)
        val deckId = decks[note.deck] ?: throw AnkiException("deck was not found: ${note.deck}")
        val fields = model.fields.map { note.fields[it].orEmpty() }
        if (fields.first().isBlank()) throw AnkiException("cannot create note because it is empty")
        if (!note.allowDuplicate && !canAddNote(note)) throw AnkiException("cannot create note because it is a duplicate")
        val values = ContentValues().apply {
            put(NOTE_MID, model.id)
            put(NOTE_FLDS, joinFields(fields))
            put(NOTE_TAGS, note.tags.joinToString(" "))
        }
        val uri = call { resolver.insert(NOTES, values) } ?: throw AnkiException("AnkiDroid didn't add the note")
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw AnkiException("AnkiDroid didn't add the note")
        val cards = Uri.withAppendedPath(uri, "cards")
        // Just these columns: by default AnkiDroid renders each card's question and answer too
        val ords = query(cards, arrayOf(CARD_ORD, CARD_DECK_ID)) { c -> c.getString(0) to c.getLong(1) }
        for ((ord, did) in ords) {
            if (did != deckId) call { resolver.update(Uri.withAppendedPath(cards, ord), ContentValues().apply { put(CARD_DECK_ID, deckId) }, null, null) }
        }
        return id
    }

    /** AnkiDroid only takes all fields at once: the note's are read and the given ones replaced. */
    override fun updateNoteFields(id: Long, fields: Map<String, String>) {
        val note = notesInfo(listOf(id)).singleOrNull() ?: throw AnkiException("note was not found: $id")
        if (fields.keys.none { it in note.fields }) return
        val values = ContentValues().apply { put(NOTE_FLDS, joinFields(mergeFields(note.fields, fields))) }
        call { resolver.update(Uri.withAppendedPath(NOTES, id.toString()), values, null, null) }
    }

    /**
     * Lends AnkiDroid the file through [AnkiMediaProvider] while it copies it. AnkiDroid picks the
     * extension from the file's type and may rename it (or reuse a file with the same name and contents).
     */
    override fun storeMediaFile(name: String, data: ByteArray): String {
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && '/' !in it }
        val file = AnkiMediaProvider.file(context, UUID.randomUUID().toString() + extension?.let { ".$it" }.orEmpty())
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        val uri = AnkiMediaProvider.uri(context, file.name)
        context.grantUriPermission(ANKIDROID, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put(MEDIA_FILE_URI, uri.toString())
                put(MEDIA_PREFERRED_NAME, mediaName(name))
            }
            val stored = call { resolver.insert(MEDIA, values) } ?: throw AnkiException("AnkiDroid didn't store $name")
            return stored.lastPathSegment ?: throw AnkiException("AnkiDroid didn't store $name")
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }

    override fun findNotes(query: String): List<Long> = query(NOTES, arrayOf(NOTE_ID), query) { it.getLong(0) }

    /** In the order of [ids], each [batch] in one search. */
    override fun notesInfo(ids: List<Long>, batch: Int): List<AnkiBackend.Note> {
        val byMid = models.associateBy { it.id }
        val read = HashMap<Long, AnkiBackend.Note>()
        for (chunk in ids.distinct().chunked(batch.coerceAtLeast(1))) {
            query(NOTES, arrayOf(NOTE_ID, NOTE_MID, NOTE_FLDS, NOTE_TAGS, NOTE_MOD), nidSearch(chunk)) { c ->
                val model = byMid[c.getLong(1)]
                val values = splitFields(c.getString(2))
                val names = model?.fields ?: List(values.size) { "Field ${it + 1}" }
                AnkiBackend.Note(
                    c.getLong(0),
                    names.zip(values).toMap(LinkedHashMap()),
                    model?.name.orEmpty(),
                    c.getString(3).orEmpty().split(' ').filter { it.isNotEmpty() },
                    c.getLong(4),
                )
            }.forEach { read[it.id] = it }
        }
        return ids.mapNotNull { read[it] }
    }

    override fun notesModTime(ids: List<Long>): Map<Long, Long> = ids.distinct().chunked(MOD_BATCH).flatMap { chunk ->
        query(NOTES, arrayOf(NOTE_ID, NOTE_MOD), nidSearch(chunk)) { c -> c.getLong(0) to c.getLong(1) }
    }.toMap()

    /** AnkiDroid's card browser; Android may not let it open while Hiroi is in the background. */
    override fun guiBrowse(query: String) {
        context.startActivity(browseIntent(query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun modelStyling(model: String): String =
        query(Uri.withAppendedPath(MODELS, model(model).id.toString()), arrayOf(MODEL_CSS)) { it.getString(0).orEmpty() }.firstOrNull()
            ?: throw AnkiException("model was not found: $model")

    override fun updateModelStyling(model: String, css: String) {
        val values = ContentValues().apply { put(MODEL_CSS, css) }
        call { resolver.update(Uri.withAppendedPath(MODELS, model(model).id.toString()), values, null, null) }
    }

    /** Each row of a query as [row] reads it; none for a search without hits (AnkiDroid's null). */
    private fun <T> query(uri: Uri, projection: Array<String>?, selection: String? = null, row: (Cursor) -> T): List<T> = call {
        resolver.query(uri, projection, selection, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        } ?: emptyList()
    }

    /** Runs a call to AnkiDroid, with what went wrong as an [AnkiException]. */
    private fun <T> call(block: () -> T): T {
        if (context.packageManager.resolveContentProvider(AUTHORITY, 0) == null) {
            throw AnkiException(context.getString(R.string.ankidroid_missing))
        }
        if (!hasPermission(context)) throw AnkiException(context.getString(R.string.ankidroid_permission_missing))
        return try {
            block()
        } catch (e: AnkiException) {
            throw e
        } catch (e: SecurityException) {
            throw AnkiException(context.getString(R.string.ankidroid_permission_missing))
        } catch (e: Exception) {
            throw AnkiException("AnkiDroid: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val ANKIDROID = "com.ichi2.anki"
        const val PERMISSION = "$ANKIDROID.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "$ANKIDROID.flashcards"
        // Lazy, so unit tests can use the functions below (android.net.Uri is a stub there)
        private val NOTES: Uri by lazy { Uri.parse("content://$AUTHORITY/notes") }
        private val MODELS: Uri by lazy { Uri.parse("content://$AUTHORITY/models") }
        private val DECKS: Uri by lazy { Uri.parse("content://$AUTHORITY/decks") }
        private val MEDIA: Uri by lazy { Uri.parse("content://$AUTHORITY/media") }

        // FlashCardsContract's columns
        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_MOD = "mod"
        private const val NOTE_TAGS = "tags"
        private const val NOTE_FLDS = "flds"
        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_CSS = "css"
        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"
        private const val CARD_ORD = "ord"
        private const val CARD_DECK_ID = "deck_id"
        private const val MEDIA_FILE_URI = "file_uri"
        private const val MEDIA_PREFERRED_NAME = "preferred_name"

        private const val FIELD_SEPARATOR = '\u001f'
        private const val MOD_BATCH = 1000

        fun installed(context: Context) = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

        fun hasPermission(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        /** AnkiDroid's card browser on [query]. */
        fun browseIntent(query: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("anki://x-callback-url/browser?search=" + Uri.encode(query)))
                .setPackage(ANKIDROID)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun joinFields(fields: List<String>) = fields.joinToString(FIELD_SEPARATOR.toString())

        fun splitFields(fields: String?): List<String> = fields.orEmpty().split(FIELD_SEPARATOR)

        /** A note's [fields] (all, in order) with the [changed] ones replaced. */
        fun mergeFields(fields: Map<String, String>, changed: Map<String, String>): List<String> =
            fields.map { (name, value) -> changed[name] ?: value }

        fun nidSearch(ids: List<Long>) = "nid:" + ids.joinToString(",")

        /**
         * The notes a note whose first field is [text] would duplicate, as AnkiConnect checks:
         * Anki's own duplicate search (dupe:, the first field without HTML, in note types [modelIds])
         * and, with a [deck], a card in that deck, or also its subdecks with [checkChildren].
         */
        fun duplicateSearch(text: String, modelIds: List<Long>, deck: String?, checkChildren: Boolean): String = buildString {
            // dupe: takes its text as it is, but for \ and " (no wildcards)
            val value = text.replace("\\", "\\\\").replace("\"", "\\\"")
            append('(').append(modelIds.joinToString(" OR ") { "\"dupe:$it,$value\"" }).append(')')
            if (deck != null) {
                append(' ').append(AnkiBackend.deckQuery(deck))
                if (!checkChildren) append(" -\"deck:").append(AnkiBackend.escape(deck)).append("::*\"")
            }
        }

        /** The name AnkiDroid is asked to give a media file: without its extension (it adds one), safe as a file name. */
        fun mediaName(name: String): String =
            name.substringBeforeLast('.').replace(Regex("""[\s/\\:*?"<>|]"""), "_").ifEmpty { "hiroi_media" }.padEnd(3, '_')
    }
}
