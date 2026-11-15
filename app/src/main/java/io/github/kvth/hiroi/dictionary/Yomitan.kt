package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** One term bank row. [glossary] is the raw JSON array; [dictionary]/[priority] are filled in by the store. */
data class TermEntry(
    val expression: String,
    val reading: String,
    val definitionTags: String,
    val rules: String,
    val score: Int,
    val glossary: String,
    val sequence: Long,
    val termTags: String,
    val dictionary: String = "",
    val priority: Int = 0,
)

/** One term meta bank row: [mode] is freq, pitch or ipa; [data] is the value wrapped in a JSON array. */
data class TermMeta(
    val expression: String,
    val mode: String,
    val data: String,
    val dictionary: String = "",
    val priority: Int = 0,
)

data class KanjiEntry(
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meanings: List<String>,
    val dictionary: String = "",
)

/**
 * Reads the JSON files inside a Yomitan dictionary zip and turns glossaries and meta data into
 * display text. See https://github.com/yomidevs/yomitan/tree/master/ext/data/schemas
 */
object Yomitan {
    data class Index(val title: String, val revision: String, val format: Int)

    fun parseIndex(json: String): Index {
        val index = JSONObject(json)
        val format = index.optInt("format", index.optInt("version", 0))
        require(format in 1..3) { "Unsupported dictionary format $format" }
        return Index(index.getString("title"), index.optString("revision"), format)
    }

    fun parseTermBank(json: String, format: Int): List<TermEntry> {
        val bank = JSONArray(json)
        return List(bank.length()) { i ->
            val row = bank.getJSONArray(i)
            val expression = row.getString(0)
            val glossary = if (format == 1) {
                // Version 1 lists the definitions as the remaining array items.
                JSONArray().also { for (j in 5 until row.length()) it.put(row.get(j)) }
            } else {
                row.getJSONArray(5)
            }
            TermEntry(
                expression = expression,
                reading = row.str(1).ifEmpty { expression },
                definitionTags = row.str(2),
                rules = row.str(3),
                score = row.optInt(4),
                glossary = glossary.toString(),
                sequence = if (format == 1) 0 else row.optLong(6),
                termTags = if (format == 1) "" else row.str(7),
            )
        }
    }

    fun parseTermMetaBank(json: String): List<TermMeta> {
        val bank = JSONArray(json)
        return List(bank.length()) { i ->
            val row = bank.getJSONArray(i)
            TermMeta(row.getString(0), row.getString(1), JSONArray().put(row.get(2)).toString())
        }
    }

    fun parseKanjiBank(json: String, format: Int): List<KanjiEntry> {
        val bank = JSONArray(json)
        return List(bank.length()) { i ->
            val row = bank.getJSONArray(i)
            val meanings = if (format == 1) {
                (4 until row.length()).map { row.getString(it) }
            } else {
                row.optJSONArray(4)?.let { m -> List(m.length()) { m.getString(it) } }.orEmpty()
            }
            KanjiEntry(row.getString(0), row.str(1), row.str(2), row.str(3), meanings)
        }
    }

    // ---- Display ------------------------------------------------------------

    /** The definitions of a glossary as plain text, one string per item (an item may span several lines). */
    fun glossaryItems(glossary: String): List<String> {
        val items = JSONArray(glossary)
        val out = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val text = when (val item = items.get(i)) {
                is String -> item
                // [uninflected term, [inflection rules]]: this entry is an inflection of another term
                is JSONArray -> "→ " + item.optString(0)
                is JSONObject -> when (item.optString("type")) {
                    "text" -> item.optString("text")
                    "structured-content" -> StringBuilder().also { flatten(item.opt("content"), it) }.toString()
                    else -> "" // images
                }
                else -> ""
            }
            val tidy = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (tidy.isNotEmpty()) out += tidy
        }
        return out
    }

    private val blockTags = setOf("div", "p", "ul", "ol", "table", "tr", "thead", "tbody", "tfoot", "details", "summary")

    /** Structured content as text: block elements on their own lines, list items bulleted, ruby readings dropped. */
    private fun flatten(node: Any?, out: StringBuilder) {
        when (node) {
            is String -> out.append(node)
            is JSONArray -> for (i in 0 until node.length()) flatten(node.get(i), out)
            is JSONObject -> when (val tag = node.optString("tag")) {
                "br" -> out.append('\n')
                "rt", "rp", "img" -> {}
                "li" -> {
                    newLine(out)
                    out.append("• ")
                    flatten(node.opt("content"), out)
                    newLine(out)
                }
                "td", "th" -> {
                    flatten(node.opt("content"), out)
                    out.append(' ')
                }
                in blockTags -> {
                    newLine(out)
                    flatten(node.opt("content"), out)
                    newLine(out)
                }
                else -> flatten(node.opt("content"), out)
            }
        }
    }

    private fun newLine(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    /** A frequency for [reading], or null if the meta row is for another reading or not a frequency. */
    fun frequency(meta: TermMeta, reading: String): Frequency? {
        if (meta.mode != "freq") return null
        var data = JSONArray(meta.data).get(0)
        if (data is JSONObject && data.has("frequency")) {
            if (data.optString("reading", reading) != reading) return null
            data = data.get("frequency")
        }
        return when (data) {
            is Number -> Frequency(data.toInt(), data.toInt().toString())
            is String -> Frequency(data.takeWhile { it.isDigit() }.toIntOrNull(), data)
            is JSONObject -> {
                val value = if (data.has("value")) data.optInt("value") else null
                val display = data.optString("displayValue").ifEmpty { value?.toString() ?: return null }
                Frequency(value, display)
            }
            else -> null
        }
    }

    data class Frequency(val value: Int?, val display: String)

    /** Downstep positions for [reading] as "[0]" / "[2]", or null if the meta row isn't pitch data for it. */
    fun pitch(meta: TermMeta, reading: String): String? {
        if (meta.mode != "pitch") return null
        val data = JSONArray(meta.data).optJSONObject(0) ?: return null
        if (data.optString("reading") != reading) return null
        val pitches = data.optJSONArray("pitches") ?: return null
        val positions = (0 until pitches.length()).mapNotNull { pitches.optJSONObject(it)?.opt("position")?.toString() }
        return positions.takeIf { it.isNotEmpty() }?.joinToString(" ") { "[$it]" }
    }

    // org.json's optString turns JSON null into "null".
    private fun JSONArray.str(i: Int): String = if (isNull(i)) "" else optString(i)
}
