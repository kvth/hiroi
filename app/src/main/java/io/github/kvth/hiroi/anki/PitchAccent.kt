package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan

/** Pitch accents for the results page: the downstep positions and the reading drawn with them. */
object PitchAccent {
    /** The term's downstep positions for its reading, from all pitch dictionaries. */
    fun positions(term: TermGroup): List<Int> =
        term.meta.mapNotNull { Yomitan.pitch(it, term.reading) }
            .flatMap { Regex("\\[(\\d+)]").findAll(it).map { match -> match.groupValues[1].toInt() } }
            .distinct()

    /** The reading with the high part overlined and ꜜ where the pitch drops. */
    fun html(reading: String, position: Int): String {
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
