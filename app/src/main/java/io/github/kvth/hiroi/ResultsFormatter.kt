package io.github.kvth.hiroi

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan

/** Whether the looked-up words have notes in the chosen Anki deck; [error] if Anki couldn't be asked. */
class AnkiStatus(val words: Set<String>, val error: String? = null)

/**
 * Renders a [LookupResult] as styled text, laid out like a Yomitan popup: per word a [header]
 * (headword, reading, Anki status) and a [body] (badges, inflection, definitions), then the kanji.
 */
class ResultsFormatter(private val secondaryColor: Int, private val accentColor: Int) {

    /** All results as one text: for copying, and a message-only fallback. */
    fun format(result: LookupResult, anki: AnkiStatus? = null): CharSequence {
        val out = SpannableStringBuilder()
        anki?.error?.let { out.append(error(it)) }
        for (group in result.terms) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(header(group, anki)).append('\n').append(body(group))
        }
        kanji(result).takeIf { it.isNotEmpty() }?.let { if (out.isNotEmpty()) out.append("\n\n"); out.append(it) }
        return out
    }

    fun error(text: String): CharSequence = SpannableStringBuilder().apply { styled(text, ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f)) }

    fun header(group: TermGroup, anki: AnkiStatus?): CharSequence = SpannableStringBuilder().apply {
        styled(group.expression, RelativeSizeSpan(1.5f), StyleSpan(Typeface.BOLD))
        if (group.reading != group.expression) {
            append("  ")
            styled(group.reading, ForegroundColorSpan(secondaryColor))
        }
        if (anki != null && anki.error == null) {
            append("  ")
            if (group.expression in anki.words) {
                styled("✓ in Anki", ForegroundColorSpan(accentColor), StyleSpan(Typeface.BOLD), RelativeSizeSpan(0.85f))
            } else {
                styled("not in Anki", ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
            }
        }
    }

    fun body(group: TermGroup): CharSequence = SpannableStringBuilder().apply {
        val badges = group.meta.mapNotNull { meta ->
            Yomitan.frequency(meta, group.reading)?.let { "${meta.dictionary} ${it.display}" }
                ?: Yomitan.pitch(meta, group.reading)?.let { "${meta.dictionary} $it" }
        }.distinct()
        if (badges.isNotEmpty()) {
            styled(badges.joinToString(" · "), ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
        }
        if (group.inflections.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            styled("${group.source}: ${group.inflections.joinToString(" → ")}", ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
        }
        var dictionary: String? = null
        for (entry in group.entries) {
            if (entry.dictionary != dictionary) {
                dictionary = entry.dictionary
                if (isNotEmpty()) append('\n')
                styled(entry.dictionary, ForegroundColorSpan(accentColor), RelativeSizeSpan(0.85f))
            }
            if (entry.definitionTags.isNotBlank()) {
                append('\n')
                styled("(${entry.definitionTags})", ForegroundColorSpan(secondaryColor), RelativeSizeSpan(0.85f))
            }
            val items = Yomitan.glossaryItems(entry.glossary)
            for ((i, item) in items.withIndex()) {
                append('\n')
                if (items.size > 1) append("${i + 1}. ")
                append(item)
            }
        }
    }

    fun kanji(result: LookupResult): CharSequence = SpannableStringBuilder().apply {
        for (kanji in result.kanji) {
            if (isNotEmpty()) append("\n\n")
            styled(kanji.character, RelativeSizeSpan(1.8f))
            append("  ")
            styled(listOf(kanji.onyomi, kanji.kunyomi).filter { it.isNotBlank() }.joinToString(" / "), ForegroundColorSpan(secondaryColor))
            append('\n')
            styled(kanji.dictionary, ForegroundColorSpan(accentColor), RelativeSizeSpan(0.85f))
            append('\n')
            append(kanji.meanings.joinToString("; "))
        }
    }

    private fun SpannableStringBuilder.styled(text: CharSequence, vararg spans: Any) {
        val start = length
        append(text)
        for (span in spans) setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
