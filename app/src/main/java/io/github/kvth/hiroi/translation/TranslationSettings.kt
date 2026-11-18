package io.github.kvth.hiroi.translation

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray

/** What translation uses. Kept out of backups: the services may have API keys. */
class TranslationSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tried in this order until one has a translation; by default ML Kit on the phone. */
    var services: List<TranslationService>
        get() = prefs.getString("services", null)
            ?.let { runCatching { JSONArray(it).let { a -> List(a.length()) { i -> TranslationService.fromJson(a.getJSONObject(i)) } } }.getOrNull() }
            ?: listOf(TranslationService("mlkit"))
        set(value) = prefs.edit().putString("services", JSONArray(value.map { it.toJson() }).toString()).apply()

    /** The language to translate into, as an ISO 639-1 code */
    var target: String
        get() = TargetLanguage.of(prefs.getString("target", "en") ?: "en").code
        set(value) = prefs.edit().putString("target", value).apply()

    /** Translate what the panel shows right away, without a tap (never by sending it to an app) */
    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(value) = prefs.edit().putBoolean("auto", value).apply()

    /** The translation app chosen for "app": its activity, and the intent action it takes */
    var app: Pair<String, ComponentName>?
        get() {
            val action = prefs.getString("app_action", null) ?: return null
            val component = prefs.getString("app_component", null)?.let(ComponentName::unflattenFromString) ?: return null
            return action to component
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove("app_action").remove("app_component")
            else putString("app_action", value.first).putString("app_component", value.second.flattenToString())
        }.apply()

    companion object {
        const val PREFS = "translation"
    }
}
