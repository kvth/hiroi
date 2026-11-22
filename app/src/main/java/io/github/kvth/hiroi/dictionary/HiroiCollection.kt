package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Hiroi's dictionary collection: a zip of the dictionaries' own files ([DictionaryFile]), so
 * importing one is copying files, with nothing to parse. [MANIFEST] comes first and lists them
 * in their order with their switches; each file also says itself which dictionary it is.
 */
object HiroiCollection {
    const val MANIFEST = "hiroi-dictionaries.json"

    /** Increases if the zip's layout changes (the files' own format is [DictionaryFile.FORMAT]) */
    const val VERSION = 1

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
        require(version <= VERSION) { "This collection is from a newer Hiroi" }
        val list = manifest.optJSONArray("dictionaries") ?: JSONArray()
        return List(list.length()) { i ->
            val item = list.getJSONObject(i)
            Item(item.optString("title"), item.getString("path"), item.optBoolean("enabled", true))
        }
    }

    /** The path of the [n]th dictionary's file in the zip */
    fun path(n: Int) = "dictionaries/$n.db"

    /** Whether [zip] is a collection rather than a Yomitan dictionary */
    fun isCollection(zip: File): Boolean = runCatching { ZipFile(zip).use { it.getEntry(MANIFEST) != null } }.getOrDefault(false)
}
