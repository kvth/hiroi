package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.DictionaryInfo

/** The markers card formats' fields can use: Yomitan's templates', the dictionaries' and Hiroi's own */
object AnkiMarkers {
    /**
     * Inline templates ({{#*inline "name"}}) defined in [templates]: each is a marker, as in
     * Yomitan. Some are only helpers of other templates, but can still be used.
     */
    fun inlineMarkers(templates: String): List<String> =
        Regex("\\{\\{~?#\\*inline\\s+\"([^\"]+)\"").findAll(templates).map { it.groupValues[1] }.distinct().toList()

    /** Yomitan's per-dictionary markers (anki-template-util.js, getDynamicFieldMarkers). */
    fun dynamicMarkers(dictionaries: List<DictionaryInfo>): List<String> = dictionaries.flatMap { info ->
        listOfNotNull(
            "single-glossary-${kebabCase(info.title)}".takeIf { info.terms > 0 },
            "single-frequency-number-${kebabCase(info.title)}".takeIf { info.frequencies > 0 },
        )
    }

    /** {sentence-translation}: Hiroi's own marker (Yomitan has none for it), the sentence translated. */
    const val SENTENCE_TRANSLATION = "{sentence-translation}"

    /** {screenshot-region}: the screenshot cut to the box tapped or the region dragged out, whatever {screenshot} does */
    const val SCREENSHOT_REGION = "{screenshot-region}"

    /**
     * Hiroi's own markers, besides Yomitan's ([hiroiMarkers] fills them): the sentence translated;
     * each kanji of the word with its meanings, with its readings, and with its parts; the
     * inflections with what each means; the text the word was looked up in (the box, the region,
     * all of them, or what was searched), and that text before and after the word; the rank from
     * the frequency dictionary results are sorted by; the screenshot of just the box or region;
     * and when the card was made.
     */
    val HIROI_MARKERS = listOf(
        "sentence-translation", "kanji-meanings", "kanji-readings", "kanji-parts", "conjugation-description", "text-block",
        "text-block-prefix", "text-block-suffix", "frequency-sort-rank", "screenshot-region", "local-time",
    )

    /** Markers [format]'s fields use that neither [templates] nor the dictionaries define: they'd come out as {name-render-error}. */
    fun unknownMarkers(format: CardFormat, templates: String, dictionaries: List<DictionaryInfo>): List<String> {
        val known = inlineMarkers(templates).toSet() + dynamicMarkers(dictionaries) + HIROI_MARKERS
        return format.fields.values.flatMap { MARKER.findAll(it).map { m -> m.groupValues[1] } }.filter { it !in known }.distinct()
    }

    /** Yomitan's marker pattern (anki-util.js): {name} with letters, digits, - and _. */
    val MARKER = Regex("\\{([\\p{L}\\p{N}_-]+)\\}")

    fun kebabCase(name: String): String = name
        .replace(Regex("[\\s_　]"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .replace(Regex("--+"), "-")
        .trim('-')
        .lowercase()
}
