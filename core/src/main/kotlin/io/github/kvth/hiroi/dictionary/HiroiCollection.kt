package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Hiroi's dictionary collection: a zip of the dictionaries' own files ([DictionaryFile], as
 * [DictionaryFile.writePortable] writes them), so importing one is copying them and making their
 * keys again, with nothing to parse. [MANIFEST] comes first and lists them
 * in their order with their switches; each file also says itself which dictionary it is.
 */
object HiroiCollection {
    const val MANIFEST = "hiroi-dictionaries.json"

    /**
     * Increases if the zip's layout changes (the files' own format is [DictionaryFile.FORMAT]).
     * 1 held SQLite files, which aren't read any more.
     */
    const val VERSION = 2

    /** What a dictionary's file in the zip ends with */
    const val EXTENSION = ".hd"

    /** A dictionary in the collection: its file in the zip, and whether it's turned on. */
    data class Item(val title: String, val path: String, val enabled: Boolean)

    fun manifest(items: List<Item>): String = JSONObject()
        .put("hiroiDictionaries", VERSION)
        .put("dictionaries", JSONArray().apply {
            for (item in items) put(JSONObject().put("title", item.title).put("path", item.path).put("enabled", item.enabled))
        })
        .toString(1)

    /** The items of manifest [json]; throws if it isn't one, or one of a newer Hiroi. */
    fun items(json: String): List<Item> {
        val manifest = JSONObject(json)
        val version = manifest.optInt("hiroiDictionaries", 0)
        require(version >= 1) { "Not a Hiroi dictionary collection" }
        require(version >= VERSION) { "This collection is from an older Hiroi, whose dictionary files this one doesn't read: import the dictionaries again" }
        require(version <= VERSION) { "This collection is from a newer Hiroi" }
        val list = manifest.optJSONArray("dictionaries") ?: JSONArray()
        return List(list.length()) { i ->
            val item = list.getJSONObject(i)
            Item(item.optString("title"), item.getString("path"), item.optBoolean("enabled", true))
        }
    }

    /** The path of the [n]th dictionary's file in the zip */
    fun path(n: Int) = "dictionaries/$n$EXTENSION"

    /**
     * Whether the zip [input] gives is a collection rather than a Yomitan dictionary: whether its
     * first entry is [MANIFEST], as collections have it. Only that entry's header is read, and
     * [input] is reset to where it was; it must support mark.
     */
    fun isCollection(input: InputStream): Boolean {
        input.mark(HEADER + 0xffff)
        try {
            val header = input.readUpTo(HEADER) ?: return false
            fun short(at: Int) = (header[at].toInt() and 0xff) or ((header[at + 1].toInt() and 0xff) shl 8)
            // A zip entry's header: "PK\u0003\u0004", then its name's length at 26
            if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte() || header[2] != 3.toByte() || header[3] != 4.toByte()) return false
            val name = input.readUpTo(short(26)) ?: return false
            return String(name, Charsets.UTF_8) == MANIFEST
        } finally {
            input.reset()
        }
    }

    /** A zip entry's header before its name */
    private const val HEADER = 30

    /** The next [n] bytes, or null if the stream ends first */
    private fun InputStream.readUpTo(n: Int): ByteArray? {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = read(bytes, read, n - read)
            if (got < 0) return null
            read += got
        }
        return bytes
    }
}
