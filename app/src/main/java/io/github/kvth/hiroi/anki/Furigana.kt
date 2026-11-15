package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.japanese.Japanese

/** Readings for the kanji parts of a word, like Yomitan's distributeFurigana. */
object Furigana {
    /** A run of text with its reading; [reading] is null for kana (no ruby needed). */
    data class Segment(val text: String, val reading: String?)

    /** 食べる + たべる -> 食(た) べる; falls back to one ruby over the whole word. */
    fun distribute(expression: String, reading: String): List<Segment> {
        if (reading.isEmpty() || reading == expression) return listOf(Segment(expression, null))
        val runs = runs(expression)
        if (runs.all { it.second }) return listOf(Segment(expression, null)) // kana only (e.g. katakana word)
        val pattern = runs.joinToString("") { (text, kana) -> if (kana) Regex.escape(Japanese.toHiragana(text)) else "(.+?)" }
        val match = Regex("^$pattern$").matchEntire(Japanese.toHiragana(reading))
            ?: return listOf(Segment(expression, reading))
        var group = 1
        return runs.map { (text, kana) -> if (kana) Segment(text, null) else Segment(text, match.groupValues[group++]) }
    }

    /**
     * Furigana for [source], an inflected form of [expression] (食べた of 食べる): the part shared
     * with the dictionary form keeps its readings, the inflected tail is kana anyway.
     */
    fun forSource(source: String, expression: String, reading: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var position = 0
        for (segment in distribute(expression, reading)) {
            if (!source.startsWith(segment.text, position)) break
            out += segment
            position += segment.text.length
        }
        if (position < source.length) out += Segment(source.substring(position), null)
        return out
    }

    /**
     * Furigana for running text (a sentence), word by word: [lookUp] gives the dictionary word at a
     * position, as the overlay finds it. Only words starting with kanji are looked up.
     */
    fun forText(text: String, lookUp: (String, Int) -> TermGroupLike?): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < text.length) {
            val term = if (Japanese.isKanji(text[i])) lookUp(text, i) else null
            if (term != null && term.source.isNotEmpty() && term.source.any(Japanese::isKanji)) {
                out += forSource(term.source, term.expression, term.reading)
                i += term.source.length
            } else {
                out += Segment(text[i].toString(), null)
                i++
            }
        }
        // Join the kana in between, so the plain form doesn't get stray spaces.
        return out.fold(mutableListOf()) { merged, segment ->
            val last = merged.lastOrNull()
            if (last != null && last.reading == null && segment.reading == null) merged[merged.lastIndex] = Segment(last.text + segment.text, null)
            else merged += segment
            merged
        }
    }

    /** The parts of a looked-up word [forText] needs. */
    data class TermGroupLike(val source: String, val expression: String, val reading: String)

    /** <ruby>食<rt>た</rt></ruby>べる */
    fun html(segments: List<Segment>): String = segments.joinToString("") {
        if (it.reading == null) Html.escape(it.text) else "<ruby>${Html.escape(it.text)}<rt>${Html.escape(it.reading)}</rt></ruby>"
    }

    /** Anki's furigana syntax: 食[た]べる, a space before each reading group but the first */
    fun plain(segments: List<Segment>): String = buildString {
        for (segment in segments) {
            if (segment.reading == null) append(segment.text) else append(' ').append(segment.text).append('[').append(segment.reading).append(']')
        }
    }.trimStart()

    fun kana(segments: List<Segment>): String = segments.joinToString("") { it.reading ?: it.text }

    private fun isKana(c: Char) = c in '぀'..'ヿ' && c != '・'

    private fun runs(text: String): List<Pair<String, Boolean>> {
        val runs = mutableListOf<Pair<String, Boolean>>()
        for (c in text) {
            val kana = isKana(c)
            if (runs.isNotEmpty() && runs.last().second == kana) runs[runs.lastIndex] = (runs.last().first + c) to kana
            else runs += c.toString() to kana
        }
        return runs
    }
}

object Html {
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
