package io.github.kvth.hiroi

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Yomitan

/** Renders a [LookupResult] as styled text, laid out like a Yomitan popup. */
class ResultsFormatter(private val secondaryColor: Int, private val accentColor: Int) {

    fun format(result: LookupResult): CharSequence {
        val out = SpannableStringBuilder()
        for (group in result.terms) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.styled(group.expression, RelativeSizeSpan(1.5f), StyleSpan(Typeface.BOLD))
            if (group.reading != group.expression) {
                out.append("  ")
                out.styled(group.reading, ForegroundColorSpan(secondaryColor))
            }

            val badges = group.meta.mapNotNull { meta ->
                Yomitan.frequency(meta, group.reading)?.let { "${meta.dictionary} ${it.display}" }
                    ?: Yomitan.pitch(meta, group.reading)?.let { "${meta.dictionary} $it" }
            }.distinct()
            if (badges.isNotEmpty()) {
                out.append('\n')
                out.styled(badges.joinToString(" · "), ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
            }
            if (group.inflections.isNotEmpty()) {
                out.append('\n')
                out.styled(
                    "${group.source}: ${group.inflections.joinToString(" → ")}",
                    ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f),
                )
            }

            var dictionary: String? = null
            for (entry in group.entries) {
                if (entry.dictionary != dictionary) {
                    dictionary = entry.dictionary
                    out.append('\n')
                    out.styled(entry.dictionary, ForegroundColorSpan(accentColor), RelativeSizeSpan(0.85f))
                }
                if (entry.definitionTags.isNotBlank()) {
                    out.append('\n')
                    out.styled("(${entry.definitionTags})", ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
                }
                val items = Yomitan.glossaryItems(entry.glossary)
                for ((i, item) in items.withIndex()) {
                    out.append('\n')
                    if (items.size > 1) out.append("${i + 1}. ")
                    out.append(item)
                }
            }
        }

        for (kanji in result.kanji) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.styled(kanji.character, RelativeSizeSpan(1.8f))
            out.append("  ")
            out.styled(listOf(kanji.onyomi, kanji.kunyomi).filter { it.isNotBlank() }.joinToString(" / "), ForegroundColorSpan(secondaryColor))
            out.append('\n')
            out.styled(kanji.dictionary, ForegroundColorSpan(accentColor), RelativeSizeSpan(0.85f))
            out.append('\n')
            out.append(kanji.meanings.joinToString("; "))
        }
        return out
    }

    private fun SpannableStringBuilder.styled(text: CharSequence, vararg spans: Any) {
        val start = length
        append(text)
        for (span in spans) setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
