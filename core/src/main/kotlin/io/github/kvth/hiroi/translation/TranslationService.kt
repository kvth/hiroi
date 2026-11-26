package io.github.kvth.hiroi.translation

import org.json.JSONObject

/**
 * Where translations come from: [type] is "mlkit" (Google's on-device models), "deepl" (DeepL's
 * API, with the user's [key]), "libretranslate" (a LibreTranslate server at [url], with a [key]
 * if it wants one), "custom" (a URL with {text}, {source} and {target}) or "app" (sends the text
 * to a translation app instead of showing a translation).
 */
data class TranslationService(val type: String, val url: String = "", val key: String = "") {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("url", url).put("key", key)

    /** Shows its translation in Hiroi (not "app"), so it can also fill a card. */
    val inPlace get() = type != "app"

    companion object {
        val TYPES = listOf("mlkit", "deepl", "libretranslate", "custom", "app")

        fun fromJson(o: JSONObject) = TranslationService(o.getString("type"), o.optString("url"), o.optString("key"))
    }
}

/** A language to translate into, by its ISO 639-1 code; all are there in ML Kit and DeepL. */
data class TargetLanguage(val code: String, val name: String) {
    companion object {
        val ALL = listOf(
            TargetLanguage("en", "English"),
            TargetLanguage("de", "Deutsch"),
            TargetLanguage("fr", "Français"),
            TargetLanguage("es", "Español"),
            TargetLanguage("it", "Italiano"),
            TargetLanguage("pt", "Português"),
            TargetLanguage("nl", "Nederlands"),
            TargetLanguage("pl", "Polski"),
            TargetLanguage("cs", "Čeština"),
            TargetLanguage("sv", "Svenska"),
            TargetLanguage("tr", "Türkçe"),
            TargetLanguage("ru", "Русский"),
            TargetLanguage("uk", "Українська"),
            TargetLanguage("id", "Bahasa Indonesia"),
            TargetLanguage("ko", "한국어"),
            TargetLanguage("zh", "中文（简体）"),
        )

        fun of(code: String) = ALL.firstOrNull { it.code == code } ?: ALL.first()
    }
}

/** Why a service gave no translation, to show as it is. */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
