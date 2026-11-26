package io.github.kvth.hiroi.anki

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class AnkiException(message: String) : IOException(message)

/**
 * Minimal AnkiConnect client (https://foosoft.net/projects/anki-connect/), limited to actions
 * AnkiConnect Android supports as well (but the note type's Styling, which it lacks).
 * [transport] sends a request body and returns the response body; tests replace it.
 */
class AnkiConnect(
    private val apiKey: String?,
    private val transport: (String) -> String,
) : AnkiBackend {
    /** [timeoutMs]: syncs give AnkiDroid longer for big searches than a lookup waits. */
    constructor(url: String, apiKey: String?, timeoutMs: Int = TIMEOUT_MS) : this(apiKey, { body -> post(url, body, timeoutMs) })

    override fun deckNames(): List<String> = (request("deckNames") as JSONArray).strings().sorted()

    override fun modelNames(): List<String> = (request("modelNames") as JSONArray).strings().sorted()

    override fun modelFieldNames(model: String): List<String> =
        (request("modelFieldNames", JSONObject().put("modelName", model)) as JSONArray).strings()

    override fun canAddNotes(notes: List<AnkiBackend.NewNote>): List<Boolean> {
        if (notes.isEmpty()) return emptyList()
        val result = request("canAddNotes", JSONObject().put("notes", JSONArray(notes.map(::noteJson)))) as JSONArray
        return List(notes.size) { result.optBoolean(it) }
    }

    override fun updateNoteFields(id: Long, fields: Map<String, String>) {
        request("updateNoteFields", JSONObject().put("note", JSONObject().put("id", id).put("fields", JSONObject(fields as Map<*, *>))))
    }

    override fun addNote(note: AnkiBackend.NewNote): Long =
        (request("addNote", JSONObject().put("note", noteJson(note))) as Number).toLong()

    /** AnkiConnect Android appends a number to the name. */
    override fun storeMediaFile(name: String, data: ByteArray): String =
        request("storeMediaFile", JSONObject().put("filename", name).put("data", Base64.getEncoder().encodeToString(data)))
            ?.toString()?.takeIf { it != "null" } ?: name

    override fun findNotes(query: String): List<Long> {
        val ids = request("findNotes", JSONObject().put("query", query)) as JSONArray
        return List(ids.length()) { ids.getLong(it) }
    }

    override fun notesInfo(ids: List<Long>, batch: Int): List<AnkiBackend.Note> = ids.chunked(batch).flatMap { chunk ->
        val notes = request("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
        List(notes.length()) { i ->
            val note = notes.getJSONObject(i)
            val fields = note.getJSONObject("fields")
            val names = fields.keys().asSequence().sortedBy { fields.getJSONObject(it).optInt("order") }
            AnkiBackend.Note(
                note.getLong("noteId"),
                names.associateWith { fields.getJSONObject(it).optString("value") },
                note.optString("modelName"),
                note.optJSONArray("tags")?.strings().orEmpty(),
                note.optLong("mod"),
            )
        }
    }

    /** Null where AnkiConnect lacks notesModTime (AnkiConnect Android, old versions). */
    override fun notesModTime(ids: List<Long>): Map<Long, Long>? = try {
        ids.chunked(1000).flatMap { chunk ->
            val mods = request("notesModTime", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            List(mods.length()) { mods.getJSONObject(it).let { m -> m.getLong("noteId") to m.getLong("mod") } }
        }.toMap()
    } catch (e: AnkiException) {
        null
    }

    /** On the computer, for desktop Anki. */
    override fun guiBrowse(query: String) {
        request("guiBrowse", JSONObject().put("query", query))
    }

    override fun modelStyling(model: String): String =
        (request("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")

    override fun updateModelStyling(model: String, css: String) {
        request("updateModelStyling", JSONObject().put("model", JSONObject().put("name", model).put("css", css)))
    }

    /** addNote's note object. */
    private fun noteJson(note: AnkiBackend.NewNote): JSONObject = JSONObject()
        .put("deckName", note.deck)
        .put("modelName", note.model)
        .put("fields", JSONObject().also { json -> note.fields.forEach { (name, value) -> json.put(name, value) } })
        .put("tags", JSONArray(note.tags))
        .put(
            "options",
            JSONObject()
                .put("allowDuplicate", note.allowDuplicate)
                .put("duplicateScope", note.duplicateScope)
                .put(
                    "duplicateScopeOptions",
                    JSONObject()
                        .put("deckName", note.scopeDeck)
                        .put("checkChildren", note.checkChildren)
                        .put("checkAllModels", note.checkAllModels),
                ),
        )

    private fun request(action: String, params: JSONObject = JSONObject()): Any? {
        val body = JSONObject().put("action", action).put("version", 6).put("params", params)
        if (!apiKey.isNullOrEmpty()) body.put("key", apiKey)
        val response = JSONObject(transport(body.toString()))
        if (!response.isNull("error")) throw AnkiException(response.getString("error"))
        return response.opt("result")
    }

    companion object {
        private const val TIMEOUT_MS = 3000

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
