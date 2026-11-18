package io.github.kvth.hiroi.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hiroi's settings as one JSON file, to move them to another phone or keep a copy: every
 * settings file (see [FILES]) with its values, and the dictionaries' order and on/off switches
 * by title (the dictionaries themselves go in a collection). API keys are only in it if asked
 * for; importing a file without them keeps the keys this phone has.
 *
 * Values are JSON's own: text, true/false, whole numbers (Android's int settings), lists of text
 * (string sets).
 */
object SettingsBackup {
    const val FORMAT = "hiroi-settings"
    const val VERSION = 1

    /** The settings files, by their SharedPreferences name */
    val FILES = listOf("hiroi", "dictionaries", "audio", "translation", "share", "anki", "marks")

    /** A dictionary's place in the order (the list's) and whether it's on */
    data class DictionaryState(val title: String, val enabled: Boolean)

    class Backup(
        val preferences: Map<String, Map<String, Any>>,
        val dictionaries: List<DictionaryState>,
        val includesKeys: Boolean,
        val app: String,
        val exported: String,
    )

    fun toJson(
        preferences: Map<String, Map<String, Any?>>,
        dictionaries: List<DictionaryState>,
        includeKeys: Boolean,
        app: String,
        exported: String,
    ): JSONObject {
        val files = JSONObject()
        for (name in FILES) {
            val values = preferences[name] ?: continue
            val file = JSONObject()
            for ((key, value) in (if (includeKeys) values else withoutKeys(name, values)).toSortedMap()) {
                encode(value)?.let { file.put(key, it) }
            }
            files.put(name, file)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", app)
            .put("exported", exported)
            .put("includesKeys", includeKeys)
            .put("preferences", files)
            .put("dictionaries", JSONArray(dictionaries.map { JSONObject().put("title", it.title).put("enabled", it.enabled) }))
    }

    /** Reads a settings file; throws [IllegalArgumentException] for anything else. */
    fun parse(text: String): Backup {
        val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a Hiroi settings file") }
        require(json.optString("format") == FORMAT) { "Not a Hiroi settings file" }
        require(json.optInt("version") in 1..VERSION) { "Made by a newer Hiroi (settings version ${json.optInt("version")})" }
        val files = json.optJSONObject("preferences") ?: JSONObject()
        val preferences = FILES.filter { files.has(it) }.associateWith { name ->
            val file = files.getJSONObject(name)
            file.keys().asSequence().mapNotNull { key -> decode(file.get(key))?.let { key to it } }.toMap()
        }
        val list = json.optJSONArray("dictionaries") ?: JSONArray()
        val dictionaries = List(list.length()) { list.getJSONObject(it) }.map { DictionaryState(it.getString("title"), it.optBoolean("enabled", true)) }
        return Backup(preferences, dictionaries, json.optBoolean("includesKeys"), json.optString("app"), json.optString("exported"))
    }

    /** [values] of settings file [name] without API keys: AnkiConnect's, and each translation service's. */
    fun withoutKeys(name: String, values: Map<String, Any?>): Map<String, Any?> = when (name) {
        "anki" -> values - "key"
        "translation" -> values.mapValues { (key, value) -> if (key == "services" && value is String) servicesWithKeys(value) { _, _ -> "" } else value }
        else -> values
    }

    /**
     * What settings file [name] becomes when [imported] replaces [current]: the imported values,
     * except that keys the file doesn't have stay as they are here (a translation service's, if
     * the same service is here with one).
     */
    fun merged(name: String, imported: Map<String, Any>, current: Map<String, Any?>): Map<String, Any> = when (name) {
        "anki" -> {
            val key = current["key"]
            if (imported["key"].let { it == null || it == "" } && key is String && key.isNotEmpty()) imported + ("key" to key) else imported
        }
        "translation" -> {
            val here = (current["services"] as? String)?.let(::services).orEmpty()
            imported.mapValues { (key, value) ->
                if (key != "services" || value !is String) value
                else servicesWithKeys(value) { service, ownKey ->
                    ownKey.ifEmpty { here.firstOrNull { it.optString("type") == service.optString("type") && it.optString("url") == service.optString("url") }?.optString("key").orEmpty() }
                }
            }
        }
        else -> imported
    }

    private fun services(json: String): List<JSONObject> =
        runCatching { JSONArray(json).let { a -> List(a.length()) { a.getJSONObject(it) } } }.getOrDefault(emptyList())

    /** The services list [json] with each service's key replaced by [key] (given the service and its key). */
    private fun servicesWithKeys(json: String, key: (JSONObject, String) -> String): String {
        val list = runCatching { JSONArray(json) }.getOrNull() ?: return json
        for (i in 0 until list.length()) {
            val service = list.optJSONObject(i) ?: continue
            service.put("key", key(service, service.optString("key")))
        }
        return list.toString()
    }

    private fun encode(value: Any?): Any? = when (value) {
        is String, is Boolean, is Int, is Long -> value
        is Float -> value.toDouble()
        is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted())
        else -> null
    }

    private fun decode(value: Any?): Any? = when (value) {
        is String, is Boolean -> value
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
        is Number -> value.toDouble().let { if (it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) it.toInt() else it.toFloat() }
        is JSONArray -> List(value.length()) { value.optString(it) }.toSet()
        else -> null
    }
}
