package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan
import org.json.JSONArray

/**
 * What a card is made from: the looked-up [term] and the [text] it was found in, where
 * [start]..[end] is the matched word. [furigana] gives readings for any text (the sentence).
 */
class CardData(
    val term: TermGroup,
    val text: String,
    val start: Int,
    val end: Int,
    val documentTitle: String = "",
    val furigana: (String) -> List<Furigana.Segment> = { listOf(Furigana.Segment(it, null)) },
)

/**
 * Yomitan's Anki field markers ({expression}, {glossary}, ...), producing the same output as
 * Yomitan's default field templates where possible. {audio} and {screenshot} need media stored
 * in Anki first and are filled in by CardCreator.
 */
object Markers {
    val media = setOf("audio", "screenshot")

    val supported = media + setOf(
        "expression", "reading", "furigana", "furigana-plain",
        "glossary", "glossary-brief", "glossary-no-dictionary", "glossary-first", "glossary-first-brief",
        "glossary-first-no-dictionary", "glossary-plain", "glossary-plain-no-dictionary",
        "sentence", "sentence-furigana", "sentence-furigana-plain",
        "cloze-prefix", "cloze-body", "cloze-body-kana", "cloze-suffix", "search-query",
        "conjugation", "part-of-speech", "tags", "dictionary", "dictionary-alias", "document-title",
        "frequencies", "frequency-harmonic-rank", "frequency-average-rank",
        "frequency-harmonic-occurrence", "frequency-average-occurrence",
        "pitch-accents", "pitch-accent-positions", "pitch-accent-categories",
        // Empty in Hiroi, as they are in Yomitan without a selection, clipboard or URL
        "popup-selection-text", "selection-text", "clipboard-text", "clipboard-image", "url",
    )

    private val marker = Regex("\\{([a-z0-9-]+)\\}")
    private const val NO_FREQUENCY = "9999999" // what Yomitan writes when there is no frequency

    /** The markers used in [template]. */
    fun used(template: String): Set<String> = marker.findAll(template).mapTo(HashSet()) { it.groupValues[1] }

    /** Replaces every marker in [template]; [mediaValues] holds audio/screenshot, unsupported markers become empty. */
    fun render(template: String, data: CardData, mediaValues: Map<String, String> = emptyMap()): String =
        marker.replace(template) { match -> mediaValues[match.groupValues[1]] ?: value(match.groupValues[1], data).orEmpty() }

    fun value(name: String, data: CardData): String? {
        val term = data.term
        return when (name) {
            "expression" -> term.expression
            "reading" -> term.reading
            "furigana" -> Furigana.html(Furigana.distribute(term.expression, term.reading))
            "furigana-plain" -> Furigana.plain(Furigana.distribute(term.expression, term.reading))
            "glossary" -> glossary(term.entries)
            "glossary-brief" -> glossary(term.entries, brief = true)
            "glossary-no-dictionary" -> glossary(term.entries, noDictionary = true)
            "glossary-first" -> glossary(term.entries.take(1))
            "glossary-first-brief" -> glossary(term.entries.take(1), brief = true)
            "glossary-first-no-dictionary" -> glossary(term.entries.take(1), noDictionary = true)
            "glossary-plain" -> glossary(term.entries, plain = true)
            "glossary-plain-no-dictionary" -> glossary(term.entries, plain = true, noDictionary = true)
            "sentence" -> sentence(data).let { (s, e) -> data.text.substring(s, e) }
            "sentence-furigana" -> sentence(data).let { (s, e) -> Furigana.html(data.furigana(data.text.substring(s, e))) }
            "sentence-furigana-plain" -> sentence(data).let { (s, e) -> Furigana.plain(data.furigana(data.text.substring(s, e))) }
            "cloze-prefix" -> sentence(data).let { (s, _) -> data.text.substring(s, data.start) }
            "cloze-body", "search-query" -> data.text.substring(data.start, data.end)
            "cloze-body-kana" -> Furigana.kana(Furigana.forSource(data.text.substring(data.start, data.end), term.expression, term.reading))
            "cloze-suffix" -> sentence(data).let { (_, e) -> data.text.substring(data.end, e) }
            "conjugation" -> term.inflections.joinToString(" « ")
            "part-of-speech" -> partsOfSpeech(term.entries)
            "tags" -> term.entries.flatMap { (it.definitionTags + " " + it.termTags).split(' ') }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            "dictionary", "dictionary-alias" -> term.entries.firstOrNull()?.dictionary.orEmpty()
            "document-title" -> data.documentTitle
            "frequencies" -> frequencies(term)
            "frequency-harmonic-rank" -> ranks(term)?.let { values -> (values.size / values.sumOf { 1.0 / it }).toInt().toString() } ?: NO_FREQUENCY
            "frequency-average-rank" -> ranks(term)?.let { values -> (values.sum() / values.size).toInt().toString() } ?: NO_FREQUENCY
            "frequency-harmonic-occurrence", "frequency-average-occurrence" -> "0" // occurrence-based lists aren't told apart from ranks
            "pitch-accents" -> pitches(term).list { position -> pitchHtml(term.reading, position) }
            "pitch-accent-positions" -> pitches(term).list { it.toString() }
            "pitch-accent-categories" -> pitches(term).map { pitchCategory(term, it) }.distinct().joinToString(",")
            "popup-selection-text", "selection-text", "clipboard-text", "clipboard-image", "url" -> ""
            else -> null
        }
    }

    /** Like Yomitan's "glossary" template: (tags, dictionary) before each definition, a list when there are several. */
    private fun glossary(entries: List<TermEntry>, brief: Boolean = false, noDictionary: Boolean = false, plain: Boolean = false): String {
        if (entries.isEmpty()) return ""
        fun single(entry: TermEntry): String {
            val labels = if (brief) emptyList() else {
                entry.definitionTags.split(' ').filter { it.isNotBlank() } + (if (noDictionary) emptyList() else listOf(entry.dictionary))
            }
            val prefix = if (labels.isEmpty()) "" else "(<i>${labels.joinToString(", ") { Html.escape(it) }}</i>) "
            val items = if (plain) {
                Yomitan.glossaryItems(entry.glossary).map { Html.escape(it).replace("\n", "<br>") }
            } else {
                JSONArray(entry.glossary).let { array -> (0 until array.length()).map { StructuredHtml.item(array.get(it)) } }
            }.filter { it.isNotEmpty() }
            val body = if (items.size <= 1) items.joinToString("") else items.joinToString("", "<ul>", "</ul>") { "<li>$it</li>" }
            return prefix + body
        }
        val content = if (entries.size > 1) {
            entries.joinToString("", "<ol>", "</ol>") { "<li data-dictionary=\"${Html.escape(it.dictionary)}\">${single(it)}</li>" }
        } else {
            single(entries.single())
        }
        return "<div style=\"text-align: left;\" class=\"yomitan-glossary\">$content</div>"
    }

    /** The sentence around the match: from after the previous 。！？ or line break up to and including the next. */
    fun sentence(data: CardData): Pair<Int, Int> {
        val enders = "。！？!?\n"
        var start = data.start
        while (start > 0 && data.text[start - 1] !in enders) start--
        var end = data.end
        while (end < data.text.length && data.text[end] !in enders) end++
        if (end < data.text.length && data.text[end] != '\n') end++
        while (start < data.start && data.text[start].isWhitespace()) start++
        return start to end
    }

    private fun partsOfSpeech(entries: List<TermEntry>): String {
        val names = entries.flatMap { it.rules.split(' ') }.mapNotNull { rule ->
            when {
                rule.startsWith("v1") -> "Ichidan verb"
                rule.startsWith("v5") -> "Godan verb"
                rule == "vk" -> "Kuru verb"
                rule.startsWith("vs") -> "Suru verb"
                rule == "vz" -> "Zuru verb"
                rule.startsWith("adj-i") -> "I-adjective"
                else -> null
            }
        }.distinct()
        return if (names.isEmpty()) "Unknown" else names.joinToString(", ")
    }

    /** One frequency per dictionary (its first), as Yomitan's frequency markers use them. */
    private fun frequencyList(term: TermGroup) = term.meta
        .mapNotNull { meta -> Yomitan.frequency(meta, term.reading)?.let { meta.dictionary to it } }
        .distinctBy { it.first }

    private fun frequencies(term: TermGroup): String {
        val list = frequencyList(term)
        if (list.isEmpty()) return ""
        return list.joinToString("", "<ul style=\"text-align: left;\">", "</ul>") { (dictionary, frequency) ->
            "<li>${Html.escape(dictionary)}: ${Html.escape(frequency.display)}</li>"
        }
    }

    private fun ranks(term: TermGroup): List<Int>? =
        frequencyList(term).mapNotNull { it.second.value?.takeIf { value -> value > 0 } }.takeIf { it.isNotEmpty() }

    fun pitches(term: TermGroup): List<Int> =
        term.meta.mapNotNull { Yomitan.pitch(it, term.reading) }
            .flatMap { Regex("\\[(\\d+)]").findAll(it).map { match -> match.groupValues[1].toInt() } }
            .distinct()

    /** Single values as they are, several as a numbered list, like Yomitan. */
    private fun List<Int>.list(format: (Int) -> String): String = when (size) {
        0 -> ""
        1 -> format(single())
        else -> joinToString("", "<ol>", "</ol>") { "<li>${format(it)}</li>" }
    }

    private fun pitchCategory(term: TermGroup, position: Int): String {
        val verbOrAdjective = term.entries.any { entry -> entry.rules.split(' ').any { it.startsWith("v") || it.startsWith("adj-i") } }
        return when {
            position == 0 -> "heiban"
            verbOrAdjective -> "kifuku"
            position == 1 -> "atamadaka"
            position == morae(term.reading).size -> "odaka"
            else -> "nakadaka"
        }
    }

    /** The reading with the high part overlined and ꜜ where the pitch drops. */
    fun pitchHtml(reading: String, position: Int): String {
        val morae = morae(reading)
        val (highFrom, highTo) = when (position) {
            0 -> 1 to morae.size
            1 -> 0 to 1
            else -> 1 to minOf(position, morae.size)
        }
        return buildString {
            append(Html.escape(morae.take(highFrom).joinToString("")))
            if (highTo > highFrom) {
                append("<span style=\"text-decoration:overline;\">")
                append(Html.escape(morae.subList(highFrom, highTo).joinToString("")))
                append("</span>")
                if (position > 0) append("ꜜ")
            }
            append(Html.escape(morae.drop(highTo).joinToString("")))
        }
    }

    /** Kana split into morae: small ゃゅょ etc. belong to the kana before them. */
    fun morae(reading: String): List<String> {
        val small = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"
        val out = mutableListOf<String>()
        for (c in reading) if (c in small && out.isNotEmpty()) out[out.lastIndex] += c else out += c.toString()
        return out
    }
}
