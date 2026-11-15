package io.github.kvth.hiroi.anki

import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders a Yomitan glossary item as HTML for Anki, the way Yomitan's {glossary} does: structured
 * content keeps its elements, inline styles and data attributes (as data-sc-*), which note types
 * like Lapis style. Images are left out (Hiroi doesn't import dictionary media).
 */
object StructuredHtml {
    private val elements = setOf(
        "ruby", "rt", "rp", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "span", "div", "ol", "ul", "li", "details", "summary",
    )

    fun item(item: Any?): String = when (item) {
        is String -> text(item)
        is JSONObject -> when (item.optString("type")) {
            "text" -> text(item.optString("text"))
            "structured-content" -> StringBuilder().also { node(item.opt("content"), it) }.toString()
            else -> ""
        }
        else -> "" // [term, [rules]] deinflection items have nothing to show
    }

    private fun text(text: String) = Html.escape(text).replace("\n", "<br>")

    private fun node(node: Any?, out: StringBuilder) {
        when (node) {
            is String -> out.append(text(node))
            is JSONArray -> for (i in 0 until node.length()) {
                // Jitendex's labels (5-dan, transitive) are adjacent spans; without the dictionary's
                // CSS they'd run together, so put a space between them.
                if (i > 0 && isLabel(node.get(i - 1)) && isLabel(node.get(i))) out.append(' ')
                node(node.get(i), out)
            }
            is JSONObject -> {
                val tag = node.optString("tag")
                when {
                    tag == "br" -> out.append("<br>")
                    tag == "img" -> {}
                    tag in elements || tag == "a" -> {
                        val name = if (tag == "a") "span" else tag // dictionary-internal links lead nowhere in Anki
                        out.append('<').append(name)
                        attributes(node, out)
                        out.append('>')
                        node(node.opt("content"), out)
                        out.append("</").append(name).append('>')
                    }
                    else -> node(node.opt("content"), out)
                }
            }
        }
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
