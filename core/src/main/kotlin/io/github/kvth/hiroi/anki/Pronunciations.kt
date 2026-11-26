package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.MetaData
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Pronunciations for the results page, as Yomitan shows them: IPA as it is, and pitch accents in
 * its three forms (pronunciation-generator.js), the reading with a line over its high morae that
 * drops where the pitch does (downstep notation), the downstep position in brackets, and a graph
 * of high and low dots.
 */
object Pronunciations {
    /** A pitch accent or a phonetic transcription, with its tags */
    sealed interface Pronunciation {
        val tags: List<String>
    }

    /** A phonetic transcription in IPA */
    data class Transcription(val ipa: String, override val tags: List<String>) : Pronunciation

    /**
     * A pitch accent: its downstep [position] (0: none), or Yomitan's [pattern] of H and L per
     * mora instead; its nasal and devoiced morae, counted from 1; and its tags.
     */
    data class Pitch(val position: Int, val pattern: String?, val nasal: Set<Int>, val devoice: Set<Int>, override val tags: List<String>) : Pronunciation {
        /** Whether mora [index] (from 0) is high, as Yomitan's isMoraPitchHigh */
        fun isHigh(index: Int): Boolean = when {
            pattern != null -> pattern.getOrNull(index) == 'H'
            position == 0 -> index > 0
            position == 1 -> index < 1
            else -> index in 1 until position
        }

        /** The downstep position as Yomitan writes it: a pattern's downsteps, comma-separated */
        val positionText: String
            get() = pattern?.let { p ->
                val downsteps = (1 until p.length).filter { p[it - 1] == 'H' && p[it] == 'L' }
                downsteps.ifEmpty { listOf(if (p.startsWith("L")) 0 else -1) }.joinToString(",")
            } ?: position.toString()
    }

    /** A dictionary's pronunciations of a reading */
    data class Group(val dictionary: String, val pronunciations: List<Pronunciation>)

    /** The forms pitch accents show in, as Yomitan's "Pitch accent" options (and their defaults) */
    data class PitchForms(val text: Boolean = true, val position: Boolean = true, val graph: Boolean = false) {
        val any: Boolean get() = text || position || graph
    }

    /**
     * The term's pronunciations of its reading, a group per dictionary as Yomitan shows them;
     * without its pitch accents unless [pitches].
     */
    fun groups(term: TermGroup, pitches: Boolean = true): List<Group> =
        term.meta.filter { (it.mode == "ipa" || it.mode == "pitch" && pitches) && it.expression == term.expression }
            .map { it.dictionary to pronunciations(it, term.reading) }
            .filter { it.second.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .map { (dictionary, lists) -> Group(dictionary, lists.flatten().distinct()) }

    /** [meta]'s pitch accents or transcriptions if they're [reading]'s */
    private fun pronunciations(meta: TermMeta, reading: String): List<Pronunciation> {
        (meta.parsed as? MetaData.Pitch)?.let { if (it.reading != reading) return emptyList() }
        val data = runCatching { JSONArray(meta.data).optJSONObject(0) }.getOrNull() ?: return emptyList()
        if (data.optString("reading") != reading) return emptyList()
        val list = data.optJSONArray(if (meta.mode == "pitch") "pitches" else "transcriptions") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            if (meta.mode == "ipa") return@mapNotNull item.optString("ipa").takeIf { it.isNotEmpty() }?.let { Transcription(it, tags(item)) }
            when (val position = item.opt("position")) {
                is Number -> Pitch(position.toInt(), null, morae(item, "nasal"), morae(item, "devoice"), tags(item))
                is String -> Pitch(0, position, morae(item, "nasal"), morae(item, "devoice"), tags(item))
                else -> null
            }
        }
    }

    /** A number or a list of them */
    private fun morae(pitch: JSONObject, key: String): Set<Int> = when (val value = pitch.opt(key)) {
        is Number -> setOf(value.toInt())
        is JSONArray -> (0 until value.length()).mapNotNullTo(HashSet()) { (value.opt(it) as? Number)?.toInt() }
        else -> emptySet()
    }

    private fun tags(item: JSONObject): List<String> =
        item.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    /**
     * [reading] with a line over its high morae that drops after the last one, as Yomitan's
     * createPronunciationText; a nasal mora's kana without its dakuten and a circle, a devoiced
     * one's in a dotted circle.
     */
    fun text(reading: String, pitch: Pitch): String = buildString {
        append("<span class=\"pronunciation-text\">")
        for ((i, mora) in morae(reading).withIndex()) {
            val nasal = (i + 1) in pitch.nasal
            val devoice = (i + 1) in pitch.devoice
            append("<span class=\"pronunciation-mora\" data-position=\"").append(i)
            append("\" data-pitch=\"").append(level(pitch.isHigh(i)))
            append("\" data-pitch-next=\"").append(level(pitch.isHigh(i + 1))).append('"')
            if (devoice) append(" data-devoice=\"true\"")
            if (nasal) append(" data-nasal=\"true\"")
            append('>')
            for ((k, c) in mora.withIndex()) {
                if (nasal && k == 0) {
                    append("<span class=\"pronunciation-character-group\"><span class=\"pronunciation-character\">")
                    append(Html.escape(withoutDakuten(c).toString())).append("</span><span class=\"pronunciation-nasal-diacritic\">゚</span>")
                    append("<span class=\"pronunciation-nasal-indicator\"></span></span>")
                } else {
                    append("<span class=\"pronunciation-character\">").append(Html.escape(c.toString())).append("</span>")
                }
            }
            if (devoice) append("<span class=\"pronunciation-devoice-indicator\"></span>")
            append("<span class=\"pronunciation-mora-line\"></span></span>")
        }
        append("</span>")
    }

    /** The downstep position in brackets, as Yomitan's createPronunciationDownstepPosition */
    fun position(pitch: Pitch): String {
        val n = Html.escape(pitch.positionText)
        return "<span class=\"pronunciation-downstep-notation\" data-downstep-position=\"$n\">" +
            "<span class=\"pronunciation-downstep-notation-prefix\">[</span>" +
            "<span class=\"pronunciation-downstep-notation-number\">$n</span>" +
            "<span class=\"pronunciation-downstep-notation-suffix\">]</span></span>"
    }

    /**
     * A dot per mora, high or low, joined by a line, and a triangle for the particle after the
     * word on a dashed line; the last high mora before a drop is a ringed dot. As Yomitan's
     * createPronunciationGraph.
     */
    fun graph(reading: String, pitch: Pitch): String = buildString {
        val count = morae(reading).size
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"pronunciation-graph\" focusable=\"false\" viewBox=\"0 0 ")
        append(50 * (count + 1)).append(" 100\">")
        if (count > 0) {
            val points = (0 until count).map { i -> i * 50 + 25 to if (pitch.isHigh(i)) 25 else 75 }
            val particle = count * 50 + 25 to if (pitch.isHigh(count)) 25 else 75
            append("<path class=\"pronunciation-graph-line\" d=\"M").append(points.joinToString(" L") { "${it.first} ${it.second}" }).append("\"/>")
            append("<path class=\"pronunciation-graph-line-tail\" d=\"M${points.last().first} ${points.last().second} L${particle.first} ${particle.second}\"/>")
            for ((i, point) in points.withIndex()) {
                val (x, y) = point
                if (pitch.isHigh(i) && !pitch.isHigh(i + 1)) {
                    append("<circle class=\"pronunciation-graph-dot-downstep1\" cx=\"$x\" cy=\"$y\" r=\"15\"/>")
                    append("<circle class=\"pronunciation-graph-dot-downstep2\" cx=\"$x\" cy=\"$y\" r=\"5\"/>")
                } else {
                    append("<circle class=\"pronunciation-graph-dot\" cx=\"$x\" cy=\"$y\" r=\"15\"/>")
                }
            }
            append("<path class=\"pronunciation-graph-triangle\" d=\"M0 13 L15 -13 L-15 -13 Z\" transform=\"translate(${particle.first},${particle.second})\"/>")
        }
        append("</svg>")
    }

    /** The forms [forms] asks for of [pitch], in Yomitan's order */
    fun html(reading: String, pitch: Pitch, forms: PitchForms): String = listOfNotNull(
        text(reading, pitch).takeIf { forms.text },
        position(pitch).takeIf { forms.position },
        graph(reading, pitch).takeIf { forms.graph },
    ).joinToString("")

    /** Yomitan's pronunciation styles (anki-template-renderer's), for the results page */
    val CSS = """
        .pronunciation-downstep-notation, .pronunciation-text, .pronunciation-character { display: inline; }
        .pronunciation-mora { display: inline-block; position: relative; }
        .pronunciation-mora-line { border-color: currentColor; }
        .pronunciation-mora[data-pitch=high] > .pronunciation-mora-line { display: block; user-select: none; pointer-events: none;
            position: absolute; top: 0.1em; left: 0; right: 0; height: 0; border-top-width: 0.1em; border-top-style: solid; }
        .pronunciation-mora[data-pitch=high][data-pitch-next=low] > .pronunciation-mora-line { right: -0.1em; height: 0.4em;
            border-right-width: 0.1em; border-right-style: solid; }
        .pronunciation-mora[data-pitch=high][data-pitch-next=low] { padding-right: 0.1em; margin-right: 0.1em; }
        .pronunciation-devoice-indicator { display: block; position: absolute; left: 50%; top: 50%; width: 1.125em; height: 1.125em;
            border-radius: 50%; box-sizing: border-box; z-index: 1; transform: translate(-50%, -50%); border: 1.5px dotted #c83c28; }
        .pronunciation-nasal-indicator { display: block; position: absolute; right: -0.125em; top: 0.125em; width: 0.375em; height: 0.375em;
            border-radius: 50%; box-sizing: border-box; z-index: 1; border: 1.5px solid #c83c28; }
        .pronunciation-nasal-diacritic { position: absolute; width: 0; height: 0; opacity: 0; }
        .pronunciation-character-group { display: inline-block; position: relative; }
        .pronunciation-graph { display: inline-block; vertical-align: middle; height: 1.5em; }
        .pronunciation-graph-line, .pronunciation-graph-line-tail { fill: none; stroke-width: 5; stroke: currentColor; }
        .pronunciation-graph-line-tail { stroke-dasharray: 5 5; }
        .pronunciation-graph-dot { stroke-width: 5; fill: currentColor; stroke: currentColor; }
        .pronunciation-graph-dot-downstep1 { fill: none; stroke-width: 5; stroke: currentColor; }
        .pronunciation-graph-dot-downstep2 { fill: currentColor; }
        .pronunciation-graph-triangle { fill: none; stroke-width: 5; stroke: currentColor; }
    """.trimIndent()

    private fun level(high: Boolean) = if (high) "high" else "low"

    /** [c] without its dakuten or handakuten (が → か), as Yomitan shows a nasal mora */
    private fun withoutDakuten(c: Char): Char {
        val parts = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return if (parts.length == 2 && parts[1] in "゙゚") parts[0] else c
    }

    /** Kana split into morae: small ゃゅょ etc. belong to the kana before them. */
    fun morae(reading: String): List<String> {
        val small = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ"
        val out = mutableListOf<String>()
        for (c in reading) if (c in small && out.isNotEmpty()) out[out.lastIndex] += c else out += c.toString()
        return out
    }
}
