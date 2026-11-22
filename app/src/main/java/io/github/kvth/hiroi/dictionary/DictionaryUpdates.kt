package io.github.kvth.hiroi.dictionary

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dictionary updates as Yomitan does them (dictionary-controller.js, checkForUpdate): a dictionary
 * whose index.json has `"isUpdatable": true` with an `indexUrl` and a `downloadUrl` can be
 * updated. Its index at `indexUrl` says the latest revision; if that's newer, the dictionary is
 * downloaded again from the latest index's `downloadUrl` (else its own) and replaces the old one.
 */
object DictionaryUpdates {
    /** Where a dictionary's updates come from, and its revision */
    data class Source(val indexUrl: String, val downloadUrl: String, val revision: String)

    /** A newer [revision] of a dictionary, at [downloadUrl] */
    data class Update(val revision: String, val downloadUrl: String)

    /** The update source in a dictionary's stored index.json (its summary); null if it has none. */
    fun source(summary: String?): Source? {
        val index = summary?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!index.optBoolean("isUpdatable")) return null
        val indexUrl = index.optString("indexUrl").takeIf { it.startsWith("http") } ?: return null
        val downloadUrl = index.optString("downloadUrl").takeIf { it.startsWith("http") } ?: return null
        return Source(indexUrl, downloadUrl, index.optString("revision"))
    }

    /** The update [source] offers, if its latest revision is newer; [fetch] gets the index (tests replace it). */
    fun check(source: Source, fetch: (String) -> String = ::httpGet): Update? {
        val index = try {
            JSONObject(fetch(source.indexUrl))
        } catch (e: org.json.JSONException) {
            throw IOException("Not a dictionary index: ${source.indexUrl}", e)
        }
        if (!index.has("title") || !index.has("revision")) throw IOException("Not a dictionary index: ${source.indexUrl}")
        val latest = index.getString("revision")
        if (!newer(source.revision, latest)) return null
        return Update(latest, index.optString("downloadUrl").takeIf { it.startsWith("http") } ?: source.downloadUrl)
    }

    /**
     * Whether [latest] is a newer revision than [current], as Yomitan compares them
     * (compareRevisions): dot-separated numbers part by part, anything else as text.
     */
    fun newer(current: String, latest: String): Boolean {
        val numbers = Regex("^(\\d+\\.)*\\d+$")
        if (!numbers.matches(current) || !numbers.matches(latest)) return current < latest
        val a = current.split('.').map { it.toBigInteger() }
        val b = latest.split('.').map { it.toBigInteger() }
        if (a.size != b.size) return current < latest
        for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    /** Downloads [url] into [file]; [onProgress] gets the bytes so far and the total (-1 if unknown). */
    fun download(url: String, file: File, onProgress: (Long, Long) -> Unit) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}: $url")
            val total = connection.contentLengthLong
            var done = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - reported >= 512 * 1024) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}: $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** A connection that follows redirects, also between hosts (GitHub's downloads go to another host). */
    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Hiroi")
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: return connection
            connection.disconnect()
            current = URL(current, location)
        }
        throw IOException("Too many redirects: $url")
    }

    private const val MAX_REDIRECTS = 8
}
