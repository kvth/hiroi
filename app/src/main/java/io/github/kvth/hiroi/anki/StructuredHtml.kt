package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.DictionaryMedia
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * Renders a Yomitan glossary item as HTML for the results page, as Yomitan's popup shows it:
 * structured content keeps its elements, inline styles and data attributes (as data-sc-*), which
 * the dictionaries' styles.css use, and images are shown from the dictionary ([media]).
 */
object StructuredHtml {
    /** Images larger than this aren't put into the page. */
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    private val elements = setOf(
        "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "span", "div", "ol", "ul", "li", "details", "summary",
    )

    fun item(item: Any?, media: (String) -> DictionaryMedia? = { null }): String = when (item) {
        is String -> text(item)
        is JSONObject -> when (item.optString("type")) {
            "text" -> text(item.optString("text"))
            "structured-content" -> StringBuilder().also { node(item.opt("content"), it, media) }.toString()
            "image" -> StringBuilder().also { out ->
                image(item, media, out)
                item.optString("description").takeIf { it.isNotEmpty() }?.let { out.append("<div class=\"gloss-image-description\">").append(text(it)).append("</div>") }
            }.toString()
            else -> ""
        }
        else -> "" // [term, [rules]] deinflection items have nothing to show
    }

    /**
     * An image as Yomitan's StructuredContentGenerator lays it out: the preferred size the
     * dictionary gave (px, or em with sizeUnits "em"), else the image's own, keeping its aspect
     * ratio, never wider than the page. Without the image, its alt text or "Image".
     */
    private fun image(data: JSONObject, media: (String) -> DictionaryMedia?, out: StringBuilder) {
        val path = data.optString("path")
        val found = media(path)
        val width = (data.opt("width") as? Number)?.toDouble() ?: found?.width?.toDouble() ?: 100.0
        val height = (data.opt("height") as? Number)?.toDouble() ?: found?.height?.toDouble() ?: 100.0
        val preferredWidth = (data.opt("preferredWidth") as? Number)?.toDouble()
        val preferredHeight = (data.opt("preferredHeight") as? Number)?.toDouble()
        val inverseAspect = if (preferredWidth != null && preferredHeight != null) preferredHeight / preferredWidth else height / width
        val used = preferredWidth ?: preferredHeight?.let { it / inverseAspect } ?: width
        val unit = if (data.optString("sizeUnits") == "em" && (preferredWidth != null || preferredHeight != null)) "em" else "px"
        val rendering = data.optString("imageRendering").ifEmpty { if (data.optBoolean("pixelated")) "pixelated" else "auto" }
        val style = buildString {
            append("display:inline-block;max-width:100%;width:").append(fmt(used)).append(unit).append(';')
            data.optString("verticalAlign").takeIf { it.isNotEmpty() }?.let { append("vertical-align:").append(it).append(';') }
            data.optString("border").takeIf { it.isNotEmpty() }?.let { append("border:").append(it).append(';') }
            data.optString("borderRadius").takeIf { it.isNotEmpty() }?.let { append("border-radius:").append(it).append(';') }
        }
        val collapsed = data.optBoolean("collapsed", false)
        if (collapsed) out.append("<details class=\"gloss-image\"><summary>Image</summary>")
        out.append("<span class=\"gloss-image\" style=\"").append(Html.escape(style)).append('"')
        data.optString("title").takeIf { it.isNotEmpty() }?.let { out.append(" title=\"").append(Html.escape(it)).append('"') }
        out.append('>')
        val bytes = found?.let { runCatching { it.read() }.getOrNull() }?.takeIf { it.size <= MAX_IMAGE_BYTES }
        if (found != null && bytes != null) {
            out.append("<img src=\"data:").append(found.mediaType).append(";base64,").append(Base64.getEncoder().encodeToString(bytes)).append('"')
            out.append(" alt=\"").append(Html.escape(data.optString("alt"))).append('"')
            out.append(" style=\"display:block;width:100%;height:auto;aspect-ratio:").append(fmt(1 / inverseAspect)).append(";image-rendering:").append(Html.escape(rendering)).append("\">")
        } else {
            out.append("[").append(Html.escape(data.optString("alt").ifEmpty { "Image" })).append("]")
        }
        out.append("</span>")
        if (collapsed) out.append("</details>")
    }

    private fun fmt(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.4f".format(java.util.Locale.ROOT, value).trimEnd('0')

    private fun text(text: String) = Html.escape(text).replace("\n", "<br>")

    private fun node(node: Any?, out: StringBuilder, media: (String) -> DictionaryMedia?) {
        when (node) {
            is String -> out.append(text(node))
            is JSONArray -> for (i in 0 until node.length()) {
                // Jitendex's labels (5-dan, transitive) are adjacent spans; without the dictionary's
                // CSS they'd run together, so put a space between them.
                if (i > 0 && isLabel(node.get(i - 1)) && isLabel(node.get(i))) out.append(' ')
                node(node.get(i), out, media)
            }
            is JSONObject -> {
                val tag = node.optString("tag")
                when {
                    tag == "br" -> out.append("<br>")
                    tag == "img" -> image(node, media, out)
                    tag in elements || tag == "a" -> {
                        val name = if (tag == "a") "span" else tag // dictionary-internal links lead nowhere in Anki
                        out.append('<').append(name)
                        attributes(node, out)
                        // A link to another word (Yomitan's "?query=…"): tapping it in the results looks that up
                        if (tag == "a") linkQuery(node.optString("href"))?.let { out.append(" class=\"link\" data-query=\"").append(Html.escape(it)).append('"') }
                        out.append('>')
                        node(node.opt("content"), out, media)
                        out.append("</").append(name).append('>')
                    }
                    else -> node(node.opt("content"), out, media)
                }
            }
        }
    }

    /** The word a dictionary's link [href] searches for: Yomitan's `?query=食べる&wildcards=off` */
    fun linkQuery(href: String): String? {
        if (!href.startsWith("?")) return null
        val value = href.drop(1).split('&').firstOrNull { it.startsWith("query=") }?.removePrefix("query=") ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isLabel(node: Any?) = (node as? JSONObject)?.optJSONObject("data")?.optString("class") == "tag"

    private fun attributes(node: JSONObject, out: StringBuilder) {
        node.optJSONObject("data")?.let { data ->
            for (key in data.keys()) out.append(" data-sc-").append(kebab(key)).append("=\"").append(Html.escape(data.optString(key))).append('"')
        }
        node.optJSONObject("style")?.let { style ->
            val css = style.keys().asSequence().joinToString(";") { key ->
                val value = style.get(key)
                "${kebab(key)}:${if (value is Number) "${value}em" else value}"
            }
            if (css.isNotEmpty()) out.append(" style=\"").append(Html.escape(css)).append('"')
        }
        for ((json, html) in listOf("title" to "title", "lang" to "lang", "colSpan" to "colspan", "rowSpan" to "rowspan")) {
            if (node.has(json)) out.append(' ').append(html).append("=\"").append(Html.escape(node.get(json).toString())).append('"')
        }
    }

    private fun kebab(key: String) = key.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }
}
