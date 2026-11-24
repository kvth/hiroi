package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.dictionary.BankReader.Companion.int
import io.github.kvth.hiroi.dictionary.BankReader.Companion.long
import io.github.kvth.hiroi.dictionary.BankReader.Companion.str
import io.github.kvth.hiroi.dictionary.BankReader.Companion.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * One term bank row. [glossary] is the raw JSON array; [dictionary]/[priority] are filled in by
 * the store, and [id], the row in its dictionary, which reads the glossary when the store left
 * it out ("": see [DictionaryStore.withGlossaries]).
 */
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
    val id: Long = 0,
)

/**
 * One term meta bank row: [mode] is freq, pitch or ipa; [data] is the value wrapped in a JSON array.
 * [parsed]: the same value already read, when the dictionary's file keeps it so ([DictionaryFile]),
 * so that lookups needn't parse [data].
 */
data class TermMeta(
    val expression: String,
    val mode: String,
    val data: String,
    val dictionary: String = "",
    val priority: Int = 0,
    val parsed: MetaData? = null,
)

/** A term meta row's value as [TermMeta.parsed] has it */
sealed class MetaData {
    /** [display] null: the value is a plain number; [reading] null: it's for any reading. */
    data class Frequency(val reading: String?, val value: Int, val display: String?) : MetaData()

    /** The downstep positions of [reading]'s pitch accents */
    data class Pitch(val reading: String, val positions: List<Int>) : MetaData()
}

/** A tag bank row: what a tag such as "v1" or "★" means, as Yomitan shows and sorts it. */
data class TagInfo(val name: String, val category: String, val order: Int, val notes: String, val score: Int, val dictionary: String = "")

/**
 * A kanji bank row. [frequencies]: the kanji's frequencies by dictionary (kanji meta banks),
 * filled in by the translator. [stats]: its stats as the bank gives them, a JSON object of tag
 * names (strokes, grade, jlpt…) to values; "{}" if it has none.
 */
data class KanjiEntry(
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meanings: List<String>,
    val dictionary: String = "",
    val frequencies: List<Pair<String, Yomitan.Frequency>> = emptyList(),
    val stats: String = "{}",
)

/**
 * Reads the JSON files inside a Yomitan dictionary zip and turns glossaries and meta data into
 * display text. See https://github.com/yomidevs/yomitan/tree/master/ext/data/schemas
 */
object Yomitan {
    /** [frequencyMode]: "rank-based" or "occurrence-based" for frequency lists that say so, else null. */
    data class Index(val title: String, val revision: String, val format: Int, val frequencyMode: String? = null)

    fun parseIndex(json: String): Index {
        val index = JSONObject(json)
        val format = index.optInt("format", index.optInt("version", 0))
        require(format in 1..3) { "Unsupported dictionary format $format" }
        val frequencyMode = index.optString("frequencyMode").takeIf { it in FREQUENCY_MODES }
        return Index(index.getString("title"), index.optString("revision"), format, frequencyMode)
    }

    val FREQUENCY_MODES = listOf("rank-based", "occurrence-based")

    // Banks are read with BankReader rather than org.json: a term bank's glossaries go straight
    // into the database as the JSON text they are.

    fun parseTermBank(json: String, format: Int): List<TermEntry> =
        ArrayList<TermEntry>().also { out -> parseTermBank(BankReader(json), format, Int.MAX_VALUE) { out += it } }

    /**
     * The rows of term bank [input], read as it's read, given to [each] [chunk] at a time: a bank
     * can be bigger than the memory there is for it.
     */
    fun parseTermBank(input: java.io.Reader, format: Int, chunk: Int, each: (List<TermEntry>) -> Unit) =
        parseTermBank(BankReader(input), format, chunk, each)

    private fun parseTermBank(reader: BankReader, format: Int, chunk: Int, each: (List<TermEntry>) -> Unit) {
        var out = ArrayList<TermEntry>()
        reader.forEachRow { row ->
            val expression = row.text(0)
            val glossary = if (format == 1) {
                // Version 1 lists the definitions as the remaining array items.
                (5 until row.size).joinToString(",", "[", "]") { BankReader.json(row[it]) }
            } else {
                (row.getOrNull(5) as? BankReader.Raw)?.json ?: throw IllegalArgumentException("No glossary array: $row")
            }
            out += TermEntry(
                expression = expression,
                reading = row.str(1).ifEmpty { expression },
                definitionTags = row.str(2),
                rules = row.str(3),
                score = row.int(4),
                glossary = glossary,
                sequence = if (format == 1) 0 else row.long(6),
                termTags = if (format == 1) "" else row.str(7),
            )
            if (out.size >= chunk) {
                each(out)
                out = ArrayList()
            }
        }
        if (out.isNotEmpty()) each(out)
    }

    /** tag_bank rows: [name, category, order, notes, score]. */
    fun parseTagBank(json: String): List<TagInfo> {
        val out = ArrayList<TagInfo>()
        BankReader(json).forEachRow { row -> out += TagInfo(row.text(0), row.str(1), row.int(2), row.str(3), row.int(4)) }
        return out
    }

    /** Term and kanji meta banks share a layout: [term or kanji, mode, data]. */
    fun parseTermMetaBank(json: String): List<TermMeta> =
        ArrayList<TermMeta>().also { out -> parseTermMetaBank(BankReader(json), Int.MAX_VALUE) { out += it } }

    /** The rows of meta bank [input], read as it's read, given to [each] [chunk] at a time */
    fun parseTermMetaBank(input: java.io.Reader, chunk: Int, each: (List<TermMeta>) -> Unit) = parseTermMetaBank(BankReader(input), chunk, each)

    private fun parseTermMetaBank(reader: BankReader, chunk: Int, each: (List<TermMeta>) -> Unit) {
        var out = ArrayList<TermMeta>()
        reader.forEachRow { row ->
            out += TermMeta(row.text(0), row.text(1), "[" + BankReader.json(row.getOrNull(2)) + "]")
            if (out.size >= chunk) {
                each(out)
                out = ArrayList()
            }
        }
        if (out.isNotEmpty()) each(out)
    }

    fun parseKanjiBank(json: String, format: Int): List<KanjiEntry> {
        val out = ArrayList<KanjiEntry>()
        BankReader(json).forEachRow { row ->
            val meanings = if (format == 1) {
                (4 until row.size).map { row.str(it) }
            } else {
                (row.getOrNull(4) as? BankReader.Raw)?.let { JSONArray(it.json) }?.let { m -> List(m.length()) { m.getString(it) } }.orEmpty()
            }
            // Version 3 has the stats after the meanings; version 1 none.
            val stats = if (format == 1) "{}" else (row.getOrNull(5) as? BankReader.Raw)?.json?.takeIf { it.trimStart().startsWith("{") } ?: "{}"
            out += KanjiEntry(row.text(0), row.str(1), row.str(2), row.str(3), meanings, stats = stats)
        }
        return out
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

    /**
     * Structured content as text: block elements on their own lines, list items marked as the
     * dictionary says (①, ②, ...) or bulleted, ruby readings dropped.
     */
    private fun flatten(node: Any?, out: StringBuilder) {
        when (node) {
            is String -> out.append(node)
            is JSONArray -> for (i in 0 until node.length()) flatten(node.get(i), out)
            is JSONObject -> {
                val tag = node.optString("tag")
                val data = node.optJSONObject("data")
                when {
                    tag == "br" -> out.append('\n')
                    tag == "rt" || tag == "rp" || tag == "img" -> {}
                    // Jitendex: the glosses of one sense, on one line like Yomitan shows them
                    data?.optString("content") == "glossary" -> {
                        val glosses = children(node.opt("content")).map { child ->
                            val content = (child as? JSONObject)?.opt("content") ?: child
                            StringBuilder().also { flatten(content, it) }.toString().trim()
                        }
                        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ') // after a label
                        out.append(glosses.filter { it.isNotEmpty() }.joinToString("; "))
                    }
                    // Jitendex: part-of-speech and other labels are inline spans
                    data?.optString("class") == "tag" -> {
                        if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                        flatten(node.opt("content"), out)
                    }
                    tag == "li" -> {
                        newLine(out)
                        val marker = node.optJSONObject("style")?.optString("listStyleType")?.trim('"', '\'', ' ')
                            ?.takeIf { it.isNotEmpty() && it != "none" } ?: "•"
                        val item = StringBuilder().also { flatten(node.opt("content"), it) }.toString().trimStart()
                        out.append(marker).append(' ').append(item)
                        newLine(out)
                    }
                    tag == "td" || tag == "th" -> {
                        flatten(node.opt("content"), out)
                        out.append(' ')
                    }
                    tag in blockTags -> {
                        newLine(out)
                        flatten(node.opt("content"), out)
                        newLine(out)
                    }
                    else -> flatten(node.opt("content"), out)
                }
            }
        }
    }

    private fun children(content: Any?): List<Any> = when (content) {
        is JSONArray -> List(content.length()) { content.get(it) }
        null -> emptyList()
        else -> listOf(content)
    }

    private fun newLine(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    /**
     * A frequency for [reading] (null: any reading), or null if the meta row is for another reading
     * or not a frequency.
     */
    fun frequency(meta: TermMeta, reading: String?): Frequency? {
        if (meta.mode != "freq") return null
        (meta.parsed as? MetaData.Frequency)?.let { f ->
            if (reading != null && f.reading != null && f.reading != reading) return null
            return Frequency(f.value, f.display?.ifEmpty { null } ?: f.value.toString())
        }
        var data = JSONArray(meta.data).get(0)
        if (data is JSONObject && data.has("frequency")) {
            if (reading != null && data.optString("reading", reading) != reading) return null
            data = data.get("frequency")
        }
        return when (data) {
            is Number -> Frequency(data.toInt(), data.toInt().toString())
            is String -> Frequency(numberIn(data), data)
            is JSONObject -> {
                val value = if (data.has("value")) data.optDouble("value").takeIf { it.isFinite() }?.toInt() else null
                val display = data.optString("displayValue").ifEmpty { value?.toString() ?: return null }
                Frequency(value, display)
            }
            else -> null
        }
    }

    data class Frequency(val value: Int?, val display: String)

    /** n / (1/v1 + ... + 1/vn), rounded down as Yomitan does; for positive [values]. */
    fun harmonicMean(values: List<Int>): Int = (values.size / values.sumOf { 1.0 / it }).toInt()

    /** The first number in a text value ("1234", "~5000", "12㋕"), as Yomitan reads it; null if there's none. */
    private fun numberIn(text: String): Int? =
        Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?").find(text)?.value?.toDoubleOrNull()?.toInt()

    /** Downstep positions for [reading] as "[0]" / "[2]", or null if the meta row isn't pitch data for it. */
    fun pitch(meta: TermMeta, reading: String): String? {
        if (meta.mode != "pitch") return null
        (meta.parsed as? MetaData.Pitch)?.let { p ->
            if (p.reading != reading) return null
            return p.positions.takeIf { it.isNotEmpty() }?.joinToString(" ") { "[$it]" }
        }
        val data = JSONArray(meta.data).optJSONObject(0) ?: return null
        if (data.optString("reading") != reading) return null
        val pitches = data.optJSONArray("pitches") ?: return null
        val positions = (0 until pitches.length()).mapNotNull { pitches.optJSONObject(it)?.opt("position")?.toString() }
        return positions.takeIf { it.isNotEmpty() }?.joinToString(" ") { "[$it]" }
    }
}
