package io.github.kvth.hiroi.audio

import android.content.Context
import org.json.JSONArray

/** Word audio settings, as Yomitan's (settings → Audio). */
class AudioSettings(context: Context) {
    private val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)

    /** Show a 🔊 button with each result */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** Tried in this order; Yomitan's default is JapanesePod101 alone. */
    var sources: List<AudioSource>
        get() = prefs.getString("sources", null)?.let { runCatching { JSONArray(it).let { a -> List(a.length()) { i -> AudioSource.fromJson(a.getJSONObject(i)) } } }.getOrNull() }
            ?: listOf(AudioSource("jpod101"))
        set(value) = prefs.edit().putString("sources", JSONArray(value.map { it.toJson() }).toString()).apply()

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
