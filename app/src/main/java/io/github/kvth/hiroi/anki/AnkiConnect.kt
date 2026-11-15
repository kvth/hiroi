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
    constructor(url: String, apiKey: String?) : this(apiKey, { body -> post(url, body) })

    data class Note(val id: Long, val fields: Map<String, String>)

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

    fun notesInfo(ids: List<Long>): List<Note> = ids.chunked(BATCH).flatMap { batch ->
        val notes = request("notesInfo", JSONObject().put("notes", JSONArray(batch))) as JSONArray
        List(notes.length()) { i ->
            val note = notes.getJSONObject(i)
            val fields = note.getJSONObject("fields")
            val names = fields.keys().asSequence().sortedBy { fields.getJSONObject(it).optInt("order") }
            Note(note.getLong("noteId"), names.associateWith { fields.getJSONObject(it).optString("value") })
        }
    }

    /** Field names of (a sample of) the notes in [deck], in the order they appear. */
    fun fieldNames(deck: String): List<String> =
        notesInfo(findNotes(deckQuery(deck)).take(FIELD_SAMPLE)).flatMap { it.fields.keys }.distinct()

    /** Which of [words] have a note in [deck] whose [field] is that word (ignoring HTML and furigana). */
    fun wordsInDeck(deck: String, field: String, words: Collection<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        // A contains-search finds notes whose field has extra markup; the exact check happens below.
        val terms = words.joinToString(" OR ") { "\"${escape(field)}:*${escape(it)}*\"" }
        val ids = findNotes("${deckQuery(deck)} ($terms)").take(MAX_NOTES)
        val wanted = words.toSet()
        return notesInfo(ids).mapNotNullTo(HashSet()) { note -> note.fields[field]?.let(::fieldText)?.takeIf { it in wanted } }
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

        private fun post(url: String, body: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }
    }
}
