package io.github.kvth.hiroi.anki

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AnkiException(message: String) : IOException(message)

/**
 * Minimal AnkiConnect client (https://foosoft.net/projects/anki-connect/), limited to actions
 * AnkiConnect Android supports as well.
 * [transport] sends a request body and returns the response body; tests replace it.
 */
class AnkiConnect(
    private val apiKey: String?,
    private val transport: (String) -> String,
) {
    /** [timeoutMs]: syncs give AnkiDroid longer for big searches than a lookup waits. */
    constructor(url: String, apiKey: String?, timeoutMs: Int = TIMEOUT_MS) : this(apiKey, { body -> post(url, body, timeoutMs) })

    /** [fields] in the note type's order; [mod] is when it was last edited (0 if unknown). */
    data class Note(
        val id: Long,
        val fields: Map<String, String>,
        val modelName: String = "",
        val tags: List<String> = emptyList(),
        val mod: Long = 0,
    )

    fun deckNames(): List<String> = (request("deckNames") as JSONArray).strings().sorted()

    fun modelNames(): List<String> = (request("modelNames") as JSONArray).strings().sorted()

    fun modelFieldNames(model: String): List<String> =
        (request("modelFieldNames", JSONObject().put("modelName", model)) as JSONArray).strings()

    /** Whether [note] (addNote's note object) would be added; false for a duplicate of its first field. */
    fun canAddNote(note: JSONObject): Boolean =
        (request("canAddNotes", JSONObject().put("notes", JSONArray().put(note))) as JSONArray).optBoolean(0)

    /** Adds [note] and returns its id. */
    fun addNote(note: JSONObject): Long =
        (request("addNote", JSONObject().put("note", note)) as Number).toLong()

    /** Stores a media file and returns the name Anki gave it (AnkiConnect Android appends a number). */
    fun storeMediaFile(name: String, base64: String): String =
        request("storeMediaFile", JSONObject().put("filename", name).put("data", base64))?.toString()?.takeIf { it != "null" } ?: name

    fun findNotes(query: String): List<Long> {
        val ids = request("findNotes", JSONObject().put("query", query)) as JSONArray
        return List(ids.length()) { ids.getLong(it) }
    }

    fun notesInfo(ids: List<Long>, batch: Int = BATCH): List<Note> = ids.chunked(batch).flatMap { chunk ->
        val notes = request("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
        List(notes.length()) { i ->
            val note = notes.getJSONObject(i)
            val fields = note.getJSONObject("fields")
            val names = fields.keys().asSequence().sortedBy { fields.getJSONObject(it).optInt("order") }
            Note(
                note.getLong("noteId"),
                names.associateWith { fields.getJSONObject(it).optString("value") },
                note.optString("modelName"),
                note.optJSONArray("tags")?.strings().orEmpty(),
                note.optLong("mod"),
            )
        }
    }

    /**
     * When each note was last edited, or null where AnkiConnect lacks notesModTime (AnkiConnect
     * Android, old versions). An unreachable Anki still throws.
     */
    fun notesModTime(ids: List<Long>): Map<Long, Long>? = try {
        ids.chunked(1000).flatMap { chunk ->
            val mods = request("notesModTime", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            List(mods.length()) { mods.getJSONObject(it).let { m -> m.getLong("noteId") to m.getLong("mod") } }
        }.toMap()
    } catch (e: AnkiException) {
        null
    }

    /** Field names of (a sample of) the notes in [deck], in the order they appear. */
    fun fieldNames(deck: String): List<String> =
        notesInfo(findNotes(deckQuery(deck)).take(FIELD_SAMPLE)).flatMap { it.fields.keys }.distinct()

    /** The notes in [deck] whose [field] is one of [words] (ignoring HTML and furigana), by word. */
    fun notesInDeck(deck: String, field: String, words: Collection<String>): Map<String, List<Long>> {
        if (words.isEmpty()) return emptyMap()
        // A contains-search finds notes whose field has extra markup; the exact check happens below.
        val terms = words.joinToString(" OR ") { "\"${escape(field)}:*${escape(it)}*\"" }
        val ids = findNotes("${deckQuery(deck)} ($terms)").take(MAX_NOTES)
        val wanted = words.toSet()
        return notesInfo(ids)
            .mapNotNull { note -> note.fields[field]?.let(::fieldText)?.takeIf { it in wanted }?.let { it to note.id } }
            .groupBy({ it.first }, { it.second })
    }

    /** Opens Anki's card browser on [query] (on the computer, for desktop Anki). */
    fun guiBrowse(query: String) {
        request("guiBrowse", JSONObject().put("query", query))
    }

    private fun request(action: String, params: JSONObject = JSONObject()): Any? {
        val body = JSONObject().put("action", action).put("version", 6).put("params", params)
        if (!apiKey.isNullOrEmpty()) body.put("key", apiKey)
        val response = JSONObject(transport(body.toString()))
        if (!response.isNull("error")) throw AnkiException(response.getString("error"))
        return response.opt("result")
    }

    companion object {
        private const val BATCH = 100 // AnkiConnect Android struggles with big notesInfo requests
        private const val MAX_NOTES = 300
        private const val FIELD_SAMPLE = 50
        private const val TIMEOUT_MS = 3000

        fun deckQuery(deck: String) = "\"deck:${escape(deck)}\""

        /** Escapes text for an Anki search inside double quotes: \ " and the wildcards * _ */
        fun escape(text: String): String = text.replace(Regex("""([\\"*_])"""), """\\$1""")

        /** A field's plain text: HTML, ruby readings and Anki furigana (日本[にほん]) removed. */
        fun fieldText(html: String): String = html
            .replace(Regex("<(rt|rp)\\b[^>]*>.*?</\\1>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex(" ?([^\\s\\[\\]]*)\\[[^\\]]*\\]"), "$1")
            .trim()

        /**
         * One request at a time: AnkiConnect Android hands each straight to AnkiDroid, and a sync
         * of the marks and a lookup may ask at once.
         */
        private val lock = Any()

        private fun post(url: String, body: String, timeoutMs: Int): String = synchronized(lock) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = timeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }
    }
}
