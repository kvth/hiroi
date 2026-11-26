package io.github.kvth.hiroi.audio

import io.github.kvth.hiroi.settings.KeyValueStore
import org.json.JSONArray

/** Word audio settings, as Yomitan's (settings → Audio). */
class AudioSettings(private val prefs: KeyValueStore) {

    /** Show a 🔊 button with each result */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** Tried in this order; Yomitan's default is JapanesePod101 alone. */
    var sources: List<AudioSource>
        get() = prefs.getString("sources", null)?.let { runCatching { JSONArray(it).let { a -> List(a.length()) { i -> AudioSource.fromJson(a.getJSONObject(i)) } } }.getOrNull() }
            ?: listOf(AudioSource("jpod101"))
        set(value) = prefs.edit().putString("sources", JSONArray(value.map { it.toJson() }).toString()).apply()

    /** The order of the local audio database's sources ([LocalAudio]): nhk16, jpod… */
    var localOrder: List<String>
        get() = prefs.getString("local_order", null)?.split(',')?.filter { it.isNotEmpty() } ?: LocalAudio.DEFAULT_ORDER
        set(value) = prefs.edit().putString("local_order", value.joinToString(",")).apply()

    /**
     * Makes the local audio database the first source, once for each database ([key]: its file's
     * size and time), so one that's removed from the sources stays removed; true if it did.
     */
    fun offerLocal(key: String): Boolean {
        if (prefs.getString("local_offered", null) == key) return false
        prefs.edit().putString("local_offered", key).apply()
        if (sources.any { it.type == "local" }) return false
        sources = listOf(AudioSource("local")) + sources
        return true
    }

    /** Word audio, with [local] the local audio database (if there is one) and its sources in [localOrder] */
    fun wordAudio(local: () -> LocalAudioStore?): WordAudio =
        WordAudio(WordAudio::httpFetch, local = local, localOrder = { localOrder })

    /** In percent */
    var volume: Int
        get() = prefs.getInt("volume", 100)
        set(value) = prefs.edit().putInt("volume", value.coerceIn(0, 100)).apply()

    /** Play the first result's audio as soon as the results show */
    var autoPlay: Boolean
        get() = prefs.getBoolean("auto_play", false)
        set(value) = prefs.edit().putBoolean("auto_play", value).apply()

    /** What plays when no source has audio: "none", "click" or "bloop", Yomitan's fallback sounds */
    var fallbackSound: String
        get() = prefs.getString("fallback", null)?.takeIf { it in FALLBACK_SOUNDS } ?: "none"
        set(value) = prefs.edit().putString("fallback", value).apply()

    companion object {
        val FALLBACK_SOUNDS = listOf("none", "click", "bloop")
    }
}
