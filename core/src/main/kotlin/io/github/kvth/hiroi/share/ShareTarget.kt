package io.github.kvth.hiroi.share

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Somewhere to send text from the panel: [type] "url" opens [url] with the text in it (in the
 * browser, or the app that handles those links: DeepL's, ChatGPT's, …), "app" sends it to one
 * app (its activity in [url], taking [action]: Android's share or "process text"). [template]
 * is what's sent, e.g. a prompt around the text.
 */
data class ShareTarget(
    val type: String,
    val name: String,
    val url: String = "",
    val template: String = TEXT,
    val action: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("name", name).put("url", url).put("template", template).put("action", action)

    /** What's sent: [template] with {text} and {language} (the translation language's name) filled in. */
    fun message(text: String, language: String): String =
        template.ifBlank { TEXT }.replace(TEXT, text).replace("{language}", language)

    /** The URL to open: {text} is the [message], {target} the translation language's code; both URL-encoded. */
    fun link(text: String, targetCode: String, language: String): String =
        Regex("\\{(text|target)\\}").replace(url.trim()) { m ->
            encode(if (m.groupValues[1] == "text") message(text, language) else targetCode)
        }

    companion object {
        const val TEXT = "{text}"

        fun fromJson(o: JSONObject) = ShareTarget(
            o.getString("type"), o.optString("name"), o.optString("url"), o.optString("template", TEXT).ifEmpty { TEXT }, o.optString("action"),
        )

        /** Percent-encoding for any part of a URL (also the fragment DeepL reads): spaces as %20, / as %2F. */
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private const val EXPLAIN = "Explain this Japanese text in {language}: its meaning, vocabulary and grammar.\n\n{text}"

        /** Ready-made targets to add (the user can change any of them). */
        val PRESETS = listOf(
            ShareTarget("url", "DeepL", "https://www.deepl.com/translator#ja/{target}/{text}"),
            ShareTarget("url", "Google Translate", "https://translate.google.com/?sl=ja&tl={target}&text={text}&op=translate"),
            ShareTarget("url", "ChatGPT", "https://chatgpt.com/?q={text}", EXPLAIN),
            ShareTarget("url", "Claude", "https://claude.ai/new?q={text}", EXPLAIN),
            ShareTarget("url", "Jisho", "https://jisho.org/search/{text}"),
            ShareTarget("url", "Google", "https://www.google.com/search?q={text}"),
        )

        /** What the list starts with */
        val DEFAULTS = listOf(PRESETS[0], PRESETS[2])
    }
}
