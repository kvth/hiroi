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

/** Makes an Anki note from a lookup, with the user's card format. Network and IPC: call off the main thread. */
class CardCreator(private val settings: AnkiSettings) {

    /** Adds the note and returns its id; throws [DuplicateNoteException] if duplicates are prevented. */
    fun add(data: CardData, screenshot: Bitmap?): Long {
        val format = settings.cardFormat
        val anki = settings.client()
        val used = format.fields.values.flatMapTo(HashSet()) { Markers.used(it) }

        if (format.preventDuplicates) {
            // Media isn't stored yet: the check only looks at the first field.
            if (!anki.canAddNote(note(format, format.fields.mapValues { Markers.render(it.value, data) }))) throw DuplicateNoteException()
        }

        val media = HashMap<String, String>()
        if ("screenshot" in used && screenshot != null) {
            val png = ByteArrayOutputStream().also { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val name = anki.storeMediaFile("hiroi_screenshot_${System.currentTimeMillis()}.png", png.base64())
            media["screenshot"] = "<img src=\"$name\">"
        }
        if ("audio" in used && settings.wordAudio) {
            wordAudio(data.term.expression, data.term.reading)?.let { mp3 ->
                val name = anki.storeMediaFile("hiroi_audio_${data.term.expression}_${data.term.reading}.mp3", mp3.base64())
                media["audio"] = "[sound:$name]"
            }
        }
        return anki.addNote(note(format, format.fields.mapValues { Markers.render(it.value, data, media) }))
    }

    private fun note(format: CardFormat, fields: Map<String, String>): JSONObject = JSONObject()
        .put("deckName", format.deck)
        .put("modelName", format.model)
        .put("fields", JSONObject().also { json -> fields.forEach { (name, value) -> json.put(name, value) } })
        .put("tags", JSONArray(format.tags))
        .put(
            "options",
            JSONObject()
                .put("allowDuplicate", !format.preventDuplicates)
                .put("duplicateScope", format.duplicateScope)
                .put("duplicateScopeOptions", JSONObject().put("deckName", format.deck).put("checkChildren", false).put("checkAllModels", false)),
        )

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
