package io.github.kvth.hiroi.audio

import io.github.kvth.hiroi.japanese.Japanese
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Where word audio comes from, as Yomitan's audio sources (settings → Audio → Sources): [type] is
 * "jpod101", "jisho", "custom" (a URL with {term}, {reading} and {language}), "custom-json" (a
 * URL answering with a list of audio URLs), "text-to-speech" or "text-to-speech-reading".
 */
data class AudioSource(val type: String, val url: String = "") {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("url", url)

    companion object {
        val TYPES = listOf("jpod101", "jisho", "custom", "custom-json", "text-to-speech", "text-to-speech-reading")

        fun fromJson(o: JSONObject) = AudioSource(o.getString("type"), o.optString("url"))
    }
}

/** One recording a source offers: a URL to download, or text for the phone's text-to-speech. */
data class AudioInfo(val source: AudioSource, val url: String? = null, val speak: String? = null, val name: String? = null)

/**
 * Finds and downloads word audio as Yomitan's AudioDownloader does (audio-downloader.js), source
 * by source. [fetch] gets a URL's bytes (null if it fails); tests replace it, and [noAudio], the
 * SHA-256 of JapanesePod101's "no audio" clip.
 */
class WordAudio(private val fetch: (String) -> ByteArray?, private val noAudio: String = JPOD101_NO_AUDIO) {

    /** What [source] offers for a word; empty if it has nothing (or failed to say). */
    fun infos(source: AudioSource, term: String, reading: String): List<AudioInfo> = runCatching {
        when (source.type) {
            "jpod101" -> listOf(AudioInfo(source, url = jpod101Url(term, reading)))
            "jisho" -> jishoUrl(term, reading)?.let { listOf(AudioInfo(source, url = it)) }.orEmpty()
            "custom" -> listOf(AudioInfo(source, url = customUrl(source.url, term, reading)))
            "custom-json" -> customJson(source, fetch(customUrl(source.url, term, reading))?.let { String(it) } ?: return@runCatching emptyList())
            "text-to-speech" -> listOf(AudioInfo(source, speak = term))
            "text-to-speech-reading" -> listOf(AudioInfo(source, speak = reading.ifEmpty { term }))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** The recording at [info], or null if there's none (JapanesePod101's "no audio" clip counts as none). */
    fun download(info: AudioInfo): ByteArray? {
        val url = info.url ?: return null
        val bytes = fetch(url)?.takeIf { it.isNotEmpty() } ?: return null
        return bytes.takeIf { info.source.type != "jpod101" || sha256(it) != noAudio }
    }

    /**
     * The first recording the [sources] have, in their order, as Yomitan's audio button plays it;
     * text-to-speech counts only if [speech] is allowed. Null if none has one.
     */
    fun first(sources: List<AudioSource>, term: String, reading: String, speech: Boolean = true): Found? {
        for (source in sources) {
            for (info in infos(source, term, reading)) {
                if (info.speak != null) {
                    if (speech) return Found(info, null)
                    continue
                }
                download(info)?.let { return Found(info, it) }
            }
        }
        return null
    }

    /** A recording that's there: its bytes, or for text-to-speech none. */
    class Found(val info: AudioInfo, val bytes: ByteArray?)

    companion object {
        /** The SHA-256 of JapanesePod101's "the audio for this clip is currently not available" clip */
        const val JPOD101_NO_AUDIO = "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"

        /** JapanesePod101's audio for a word; a word written in kana alone goes by its reading only. */
        fun jpod101Url(term: String, reading: String): String {
            val kanaOnly = reading == term && Japanese.isKanaOnly(term)
            val params = listOfNotNull(
                ("kanji" to term).takeIf { !kanaOnly && term.isNotEmpty() },
                ("kana" to reading).takeIf { reading.isNotEmpty() },
            )
            return "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?" +
                params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        }

        /** A custom source's URL with {term}, {reading} and {language} filled in (other {…} stay). */
        fun customUrl(template: String, term: String, reading: String): String =
            Regex("\\{([^}]*)\\}").replace(template) { m ->
                when (m.groupValues[1]) {
                    "term" -> term
                    "reading" -> reading
                    "language" -> "ja"
                    else -> m.value
                }
            }

        /** A custom JSON source's answer: {"type": "audioSourceList", "audioSources": [{"url", "name"?}]}. */
        fun customJson(source: AudioSource, json: String): List<AudioInfo> {
            val list = JSONObject(json)
            require(list.optString("type") == "audioSourceList") { "Not an audio source list" }
            val sources = list.getJSONArray("audioSources")
            return List(sources.length()) { sources.getJSONObject(it) }.map { item ->
                AudioInfo(source, url = item.getString("url"), name = item.optString("name").takeIf { it.isNotEmpty() })
            }
        }

        /** Jisho's word page lists its recording as <audio id="audio_{term}:{reading}"><source src="…">. */
        fun jishoAudio(html: String, term: String, reading: String, pageUrl: String): String? {
            val start = html.indexOf("id=\"audio_$term:$reading\"").takeIf { it >= 0 } ?: return null
            val end = html.indexOf("</audio>", start).takeIf { it >= 0 } ?: return null
            val src = Regex("<source[^>]*\\bsrc=\"([^\"]+)\"").find(html.substring(start, end))?.groupValues?.get(1) ?: return null
            return URI(pageUrl).resolve(src.replace("&amp;", "&")).toString()
        }

        /** Downloads [url] (up to 10 MB); null if it fails. */
        fun httpFetch(url: String): ByteArray? = try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Hiroi")
            try {
                if (connection.responseCode !in 200..299) null
                else connection.inputStream.use { input -> input.readNBytesCompat(10 * 1024 * 1024) }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun jishoUrl(term: String, reading: String): String? {
        val page = "https://jisho.org/search/${URLEncoder.encode(term, "UTF-8").replace("+", "%20")}"
        val html = fetch(page)?.let { String(it) } ?: return null
        return jishoAudio(html, term, reading, page)
    }
}
