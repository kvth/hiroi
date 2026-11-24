package io.github.kvth.hiroi

import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.Html
import io.github.kvth.hiroi.anki.PitchAccent
import io.github.kvth.hiroi.anki.StructuredHtml
import io.github.kvth.hiroi.dictionary.DictionaryMedia
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Meanings
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan
import org.json.JSONArray
import org.json.JSONObject

/** The looked-up words' notes in the chosen Anki deck, by word; [error] if Anki couldn't be asked. */
class AnkiStatus(val notes: Map<String, List<Long>>, val error: String? = null)

/**
 * A lookup result as HTML, laid out like Yomitan's popup: the headword with furigana, tags for
 * inflection, Anki, frequency and pitch, then the numbered definitions with their dictionary,
 * styled by the dictionary's own styles.css. "＋ Anki" buttons call Hiroi.addCard(index).
 */
class ResultsHtml(private val colors: Colors) {
    /** CSS colours from the app theme, so the results match the panel. */
    data class Colors(val text: String, val secondary: String, val accent: String)

    /**
     * [average]: one "Average" tag with the harmonic mean instead of a tag per frequency list,
     * as Yomitan's "Average frequencies" option; lists of [occurrenceBased] counts stay out of it.
     */
    data class Frequencies(val average: Boolean = false, val occurrenceBased: Set<String> = emptySet())

    fun document(
        result: LookupResult,
        anki: AnkiStatus?,
        styles: Map<String, String>,
        cards: Boolean,
        frequencies: Frequencies = Frequencies(),
        /** A dictionary's image by its path, for the definitions that show one */
        media: (dictionary: String, path: String) -> DictionaryMedia? = { _, _ -> null },
        /** A 🔊 button per result: Hiroi.playAudio(index), long-pressed Hiroi.audioMenu(index) */
        audio: Boolean = false,
    ): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        append("<style>").append(css())
        // Each dictionary's styles only apply inside its own definitions (CSS nesting, as Jitendex itself uses).
        for (dictionary in result.terms.flatMap { it.entries }.map { it.dictionary }.distinct()) {
            val sheet = styles[dictionary] ?: continue
            append(".glossary[data-dictionary=\"").append(cssString(dictionary)).append("\"] {\n").append(sheet).append("\n}\n")
        }
        append("</style><script>")
        append("function setCard(i, label, enabled) { var b = document.getElementById('card' + i); if (b) { b.textContent = label; b.disabled = !enabled; } }")
        append("function inAnki(i, label) { var t = document.getElementById('anki' + i); if (t) { t.className = 'tag anki'; t.firstChild.textContent = label; } ")
        append("var o = document.getElementById('open' + i); if (o) o.style.display = ''; }")
        // A note with the same first field exists: the add button says what it would do, "Open in Anki" how many there are.
        append("function duplicate(i, label, enabled, open) { var b = document.getElementById('card' + i); ")
        append("if (b) { b.textContent = label; b.disabled = !enabled; b.className = 'card duplicate'; } ")
        append("var o = document.getElementById('open' + i); if (o) { o.style.display = ''; o.textContent = open; } }")
        // The 🔊 button while its audio loads, and when there's none
        append("function audioState(i, state) { var b = document.getElementById('audio' + i); if (b) b.className = 'audio ' + state; }")
        // On a touch screen the button takes its touches itself: a tap plays, a long press opens the
        // audio menu, and the page doesn't start selecting the words next to it.
        append("document.addEventListener('touchstart', function (e) { var b = e.target.closest && e.target.closest('.audio'); if (!b) return; ")
        append("e.preventDefault(); b.timer = setTimeout(function () { b.timer = null; Hiroi.audioMenu(+b.dataset.index); }, 500); }, {passive: false});")
        append("document.addEventListener('touchend', function (e) { var b = e.target.closest && e.target.closest('.audio'); if (!b) return; ")
        append("e.preventDefault(); if (b.timer) { clearTimeout(b.timer); b.timer = null; Hiroi.playAudio(+b.dataset.index); } }, {passive: false});")
        // A tile opens into its result (and closes when its headword is tapped)
        append("function openTile(i) { var t = document.getElementById('tile' + i); t.classList.add('open'); t.scrollIntoView({block: 'nearest'}); }")
        append("document.addEventListener('click', function (e) { var h = e.target.closest && e.target.closest('.tile.open .headword'); ")
        append("if (h) h.closest('.tile').classList.remove('open'); });")
        append("</script></head><body>")
        anki?.error?.let { append("<div class=\"error\">").append(Html.escape(it)).append("</div>") }
        if (result.sections.isEmpty()) {
            for ((i, term) in result.terms.withIndex()) term(i, term, anki, cards, frequencies, media, audio)
        } else {
            // A search in sections (Latin text): each a grid of small tiles, as Akebi shows them, that open into the whole result
            for ((s, section) in result.sections.withIndex()) {
                append("<div class=\"section\">").append(Html.escape(section.title)).append("</div><div class=\"tiles\">")
                for (i in section.first until (result.sections.getOrNull(s + 1)?.first ?: result.terms.size)) {
                    tile(i, result.terms[i], frequencies, section.words)
                    term(i, result.terms[i], anki, cards, frequencies, media, audio)
                    append("</div></div>")
                }
                append("</div>")
            }
        }
        for (kanji in result.kanji) kanji(kanji, frequencies)
        append("</body></html>")
    }

    private fun StringBuilder.term(
        index: Int,
        term: TermGroup,
        anki: AnkiStatus?,
        cards: Boolean,
        frequencies: Frequencies,
        media: (String, String) -> DictionaryMedia?,
        audio: Boolean,
    ) {
        append("<div class=\"entry\"><div class=\"header\"><span class=\"headword\" lang=\"ja\">")
        // Grouped by term or related terms, a result can have several headwords, as in Yomitan.
        term.headwords.joinTo(this, "<span class=\"separator\">・</span>") { Furigana.html(Furigana.distribute(it.expression, it.reading)) }
        append("</span>")
        if (audio) {
            append("<button class=\"audio\" id=\"audio$index\" data-index=\"$index\" onclick=\"Hiroi.playAudio($index)\" ")
            append("oncontextmenu=\"Hiroi.audioMenu($index); return false;\">🔊</button>")
        }
        // Shown when the word has a note (or once one was added), like Yomitan's "view note" button.
        val hasNote = anki?.notes?.containsKey(term.expression) == true
        append("<button class=\"card\" id=\"open$index\" onclick=\"Hiroi.openNote($index)\"")
        if (!hasNote) append(" style=\"display:none\"")
        append(">Open in Anki</button>")
        if (cards) append("<button class=\"card\" id=\"card$index\" onclick=\"Hiroi.addCard($index)\">＋ Anki</button>")
        append("</div>")
        if (term.inflections.isNotEmpty()) {
            append("<div class=\"inflections\">« ").append(Html.escape(term.inflections.joinToString(" « "))).append("</div>")
        }

        val tags = StringBuilder()
        if (anki != null && anki.error == null) {
            if (term.expression in anki.notes) tags.tag("anki", "✓ in Anki", id = "anki$index") else tags.tag("not-anki", "not in Anki", id = "anki$index")
        }
        tags.frequencyTags(termFrequencies(term), frequencies)
        val pitches = PitchAccent.positions(term)
        if (pitches.isNotEmpty()) {
            val dictionary = term.meta.firstOrNull { it.mode == "pitch" }?.dictionary.orEmpty()
            tags.tag("pitch", dictionary, pitches.joinToString(" ") { PitchAccent.html(term.reading, it) })
        }
        if (tags.isNotEmpty()) append("<div class=\"tags\">").append(tags).append("</div>")

        append("<ol class=\"definitions\">")
        for (definition in term.definitions) {
            // For some of the headwords only, as Yomitan marks it ("見る only")
            val only = if (term.headwords.size > 1 && definition.headwordIndices.size < term.headwords.size) {
                definition.headwordIndices.joinToString("、") { headwordLabel(term, it) } + " only"
            } else {
                null
            }
            definition(definition.entry, media, only)
        }
        append("</ol>")
        kanjiOf(term)
        append("</div>")
    }

    /** The kanji of [term]'s word, folded: each kanji dictionary's meanings, readings and numbers for it */
    private fun StringBuilder.kanjiOf(term: TermGroup) {
        if (term.kanji.isEmpty()) return
        val characters = term.kanji.map { it.character }.distinct()
        append("<details class=\"kanji-breakdown\"><summary>Kanji <span lang=\"ja\">").append(Html.escape(characters.joinToString(" "))).append("</span></summary>")
        for (character in characters) {
            append("<div class=\"kanji-card\"><div class=\"kanji-character\" lang=\"ja\">").append(Html.escape(character)).append("</div><div>")
            for (kanji in term.kanji.filter { it.character == character }) {
                append("<div class=\"kanji-dictionary\">")
                tag("dictionary", kanji.dictionary)
                if (kanji.meanings.isNotEmpty()) append(" ").append(Html.escape(kanji.meanings.joinToString(", ")))
                append("</div>")
                val readings = listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi).filter { it.second.isNotBlank() }
                if (readings.isNotEmpty()) {
                    append("<div class=\"kanji-readings\" lang=\"ja\">")
                    readings.joinTo(this, " · ") { (label, r) -> "<span class=\"kanji-label\">$label</span> " + Html.escape(r.replace(" ", "、")) }
                    append("</div>")
                }
                val numbers = kanjiNumbers(kanji) + kanji.frequencies.map { (dictionary, f) -> "${Html.escape(dictionary)} ${Html.escape(f.display)}" }
                if (numbers.isNotEmpty()) append("<div class=\"kanji-numbers\">").append(numbers.joinToString(" · ")).append("</div>")
            }
            append("</div></div>")
        }
        append("</details>")
    }

    /** What a kanji dictionary's stats say that's worth a glance: strokes, grade, JLPT level, frequency, Kanken level */
    private fun kanjiNumbers(kanji: KanjiEntry): List<String> {
        val stats = runCatching { JSONObject(kanji.stats) }.getOrNull() ?: return emptyList()
        return listOf("strokes" to "%s strokes", "grade" to "grade %s", "jlpt" to "old JLPT %s", "freq" to "frequency %s", "漢字検定" to "Kanken %s")
            .mapNotNull { (key, format) -> stats.optString(key).takeIf { it.isNotEmpty() }?.let { format.format(Html.escape(it)) } }
    }

    /**
     * A result as a tile (its reading, its word, its glosses: first the one with [words], what
     * its definitions were searched for, if they were), darker for a common word; what follows,
     * up to two closing divs, is shown in its place once it's tapped.
     */
    private fun StringBuilder.tile(index: Int, term: TermGroup, frequencies: Frequencies, words: List<String>) {
        val ranks = term.meta.filter { it.dictionary !in frequencies.occurrenceBased }.mapNotNull { Yomitan.frequency(it, term.reading)?.value }
        val common = ranks.isNotEmpty() && ranks.min() <= COMMON_RANK
        val glosses = term.entries.take(TILE_ENTRIES).flatMap { Meanings.glosses(it.glossary) }.distinct()
        val matching = if (words.isEmpty()) null else glosses.firstOrNull { gloss ->
            val its = Meanings.words(gloss)
            words.dropLast(1).all { it in its } && its.any { it.startsWith(words.last()) }
        }
        val gloss = (listOfNotNull(matching) + glosses.filter { it != matching }).take(2).joinToString("; ")
        append("<div class=\"tile").append(if (common) " common" else "").append("\" id=\"tile$index\">")
        append("<div class=\"tile-head\" onclick=\"openTile($index)\">")
        append("<div class=\"tile-reading\" lang=\"ja\">").append(if (term.reading != term.expression) Html.escape(term.reading) else "&nbsp;").append("</div>")
        append("<div class=\"tile-word\" lang=\"ja\">").append(Html.escape(term.expression)).append("</div>")
        append("<div class=\"tile-gloss\">").append(Html.escape(gloss)).append("</div></div><div class=\"tile-full\">")
    }

    /** A headword as it's told apart from the others: its spelling, or its reading where the spellings are the same. */
    private fun headwordLabel(term: TermGroup, index: Int): String {
        val headword = term.headwords[index]
        return if (term.headwords.count { it.expression == headword.expression } > 1) headword.reading else headword.expression
    }

    /** Each headword's frequencies (for its reading); with several headwords, each says which it's for. */
    private fun termFrequencies(term: TermGroup): List<Pair<String, Yomitan.Frequency>> = term.headwords.indices.flatMap { i ->
        val headword = term.headwords[i]
        term.meta.filter { it.expression == headword.expression }.mapNotNull { meta ->
            Yomitan.frequency(meta, headword.reading)?.let { frequency ->
                meta.dictionary to if (term.headwords.size > 1) frequency.copy(display = "${headwordLabel(term, i)} ${frequency.display}") else frequency
            }
        }
    }

    private fun StringBuilder.definition(entry: TermEntry, media: (String, String) -> DictionaryMedia?, only: String? = null) {
        append("<li><div class=\"definition-tags\">")
        if (only != null) tag("only", only)
        for (tag in entry.definitionTags.split(' ').filter { it.isNotBlank() }) tag("label", tag)
        tag("dictionary", entry.dictionary)
        append("</div><div class=\"glossary\" lang=\"en\" data-dictionary=\"").append(Html.escape(entry.dictionary)).append("\">")
        val items = JSONArray(entry.glossary).let { array -> (0 until array.length()).map { StructuredHtml.item(array.get(it)) { path -> media(entry.dictionary, path) } } }
            .filter { it.isNotEmpty() }
        if (items.size == 1) append(items.single()) else items.joinTo(this, "", "<ul>", "</ul>") { "<li>$it</li>" }
        append("</div></li>")
    }

    /**
     * One tag per dictionary with all its values, as Yomitan groups them; or, with
     * [Frequencies.average], one "Average" tag: the harmonic mean of each list's lowest value.
     */
    private fun StringBuilder.frequencyTags(list: List<Pair<String, Yomitan.Frequency>>, frequencies: Frequencies) {
        val byDictionary = list.groupBy({ it.first }, { it.second })
        if (frequencies.average) {
            val ranks = byDictionary.filterKeys { it !in frequencies.occurrenceBased }
                .values.mapNotNull { values -> values.mapNotNull { it.value?.takeIf { v -> v > 0 } }.minOrNull() }
            if (ranks.isNotEmpty()) {
                tag("frequency", "Average", Yomitan.harmonicMean(ranks).toString())
                return
            }
        }
        for ((dictionary, values) in byDictionary) tag("frequency", dictionary, Html.escape(values.map { it.display }.distinct().joinToString(", ")))
    }

    private fun StringBuilder.kanji(kanji: KanjiEntry, frequencies: Frequencies) {
        append("<div class=\"entry kanji\"><div class=\"header\"><span class=\"character\" lang=\"ja\">")
        append(Html.escape(kanji.character)).append("</span></div><div class=\"tags\">")
        tag("dictionary", kanji.dictionary)
        frequencyTags(kanji.frequencies, frequencies)
        append("</div><table>")
        for ((label, readings) in listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi)) {
            if (readings.isNotBlank()) append("<tr><th>$label</th><td lang=\"ja\">").append(Html.escape(readings.replace(" ", "、"))).append("</td></tr>")
        }
        append("</table>")
        kanji.meanings.joinTo(this, "", "<ol class=\"definitions\">", "</ol>") { "<li>${Html.escape(it)}</li>" }
        append("</div>")
    }

    /** A tag like Yomitan's: a coloured label, optionally followed by a value (frequency, pitch). */
    private fun StringBuilder.tag(kind: String, label: String, valueHtml: String? = null, id: String? = null) {
        append("<span class=\"tag $kind\"")
        if (id != null) append(" id=\"").append(id).append('"')
        append("><span class=\"label\">").append(Html.escape(label)).append("</span>")
        if (valueHtml != null) append("<span class=\"value\">").append(valueHtml).append("</span>")
        append("</span>")
    }

    private companion object {
        /** Up to which frequency rank a word's tile is marked as common */
        const val COMMON_RANK = 10_000

        /** The entries of a result whose glosses its tile looks through */
        const val TILE_ENTRIES = 4
    }

    private fun cssString(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun css() = """
        body { margin: 0; color: ${colors.text}; background: transparent; font-family: sans-serif; font-size: 15px; line-height: 1.45; }
        .error { color: ${colors.secondary}; font-size: 0.85em; margin-bottom: 8px; }
        .entry + .entry { margin-top: 14px; padding-top: 12px; border-top: 1px solid ${colors.secondary}40; }
        .section { margin: 18px 0 8px; padding-bottom: 3px; border-bottom: 2px solid ${colors.accent}80; color: ${colors.accent}; font-size: 0.85em; font-weight: bold; }
        .section:first-child { margin-top: 0; }
        .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(8.5em, 1fr)); gap: 4px; }
        .tile-head { padding: 6px 4px; border-radius: 6px; background: ${colors.accent}14; text-align: center; overflow: hidden; }
        .tile.common .tile-head { background: ${colors.accent}33; }
        .tile-reading { color: ${colors.secondary}; font-size: 0.75em; line-height: 1.3; }
        .tile-word { font-size: 1.45em; line-height: 1.25; overflow-wrap: anywhere; }
        .tile-gloss { font-size: 0.8em; line-height: 1.3; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
        .tile-full { display: none; }
        .tile.open { grid-column: 1 / -1; padding: 8px 0 12px; border-bottom: 1px solid ${colors.secondary}40; }
        .tile.open .tile-head { display: none; }
        .tile.open .tile-full { display: block; }
        .kanji-breakdown { margin-top: 8px; }
        .kanji-breakdown > summary { color: ${colors.accent}; font-size: 0.9em; }
        .kanji-card { display: flex; gap: 10px; margin-top: 6px; padding: 6px 8px; border-radius: 6px; background: ${colors.secondary}14; }
        .kanji-character { font-size: 2.2em; line-height: 1.1; }
        .kanji-dictionary { font-size: 0.9em; }
        .kanji-dictionary + .kanji-readings, .kanji-numbers + .kanji-dictionary { margin-top: 2px; }
        .kanji-readings, .kanji-numbers { color: ${colors.secondary}; font-size: 0.8em; }
        .kanji-label { font-weight: bold; }
        .header { display: flex; align-items: flex-end; gap: 8px; }
        .headword { flex: 1; font-size: 2em; line-height: 1.25; }
        .headword .separator { color: ${colors.secondary}; }
        .tag.only .label { background: none; color: ${colors.text}; border: 1px solid ${colors.secondary}80; border-radius: 4px; font-weight: normal; }
        .headword rt, .glossary rt { color: ${colors.secondary}; }
        .card { flex: none; margin: 0 1px 4px 0; padding: 4px 10px; border: 1px solid ${colors.accent}; border-radius: 6px;
            background: none; color: ${colors.accent}; font: inherit; font-size: 0.9em; }
        .card:disabled { opacity: 0.6; }
        .audio { flex: none; margin-bottom: 4px; padding: 2px 6px; border: none; background: none; font-size: 1.3em; line-height: 1;
            -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }
        .audio.loading { opacity: 0.4; }
        .audio.none { opacity: 0.4; filter: grayscale(1); }
        .card.duplicate { border-style: dashed; }
        .inflections { color: ${colors.secondary}; font-size: 0.85em; font-style: italic; }
        .tags, .definition-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
        .tag { display: inline-flex; border-radius: 4px; overflow: hidden; font-size: 0.75em; line-height: 1.7; white-space: nowrap; }
        .tag > span { padding: 0 6px; }
        .tag .label { background: #8a8a91; color: white; font-weight: bold; }
        .tag .value { background: ${colors.secondary}26; color: ${colors.text}; }
        .tag.dictionary .label { background: #9057ad; }
        .tag.frequency .label { background: #489148; }
        .tag.pitch .label { background: #6640be; }
        .tag.anki .label { background: ${colors.accent}; }
        .tag.not-anki { border: 1px solid ${colors.secondary}80; }
        .tag.not-anki .label { background: none; color: ${colors.secondary}; font-weight: normal; }
        ol.definitions { margin: 6px 0 0; padding-left: 1.5em; }
        ol.definitions > li + li { margin-top: 8px; }
        .glossary { margin-top: 3px; }
        .gloss-image-description { color: ${colors.secondary}; font-size: 0.85em; }
        details.gloss-image > summary { color: ${colors.accent}; }
        .glossary ul, .glossary ol { margin: 2px 0; padding-left: 1.3em; }
        .kanji .character { font-size: 3em; line-height: 1.1; }
        .kanji table { margin-top: 6px; border-collapse: collapse; }
        .kanji th { padding-right: 10px; color: ${colors.secondary}; font-weight: normal; text-align: left; }
    """.trimIndent()
}
