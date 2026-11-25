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
import io.github.kvth.hiroi.japanese.Deinflector
import org.json.JSONArray
import org.json.JSONObject

/** The looked-up words' notes in the chosen Anki deck, by word; [error] if Anki couldn't be asked. */
class AnkiStatus(val notes: Map<String, List<Long>>, val error: String? = null) {
    companion object {
        /** Anki is asked once the results show: their Anki tags wait, hidden, for the page's ankiChecked() */
        val ASKING = AnkiStatus(emptyMap())
    }
}

/**
 * A lookup result as HTML, laid out like Yomitan's popup: the headword with furigana, tags for
 * inflection, Anki, frequency and pitch, then the numbered definitions with their dictionary,
 * styled by the dictionary's own styles.css. "＋ Anki" buttons call Hiroi.addCard(index).
 */
class ResultsHtml(
    private val colors: Colors,
    /** The names dictionaries were given (their aliases, Yomitan's "Rename…") by title, read for each page */
    private val aliases: () -> Map<String, String> = { emptyMap() },
) {
    /** The names of the page being made ([Page]) */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    /** What dictionary [title] is called on the page */
    private fun name(title: String) = names[title] ?: title

    /** CSS colours from the app theme, so the results match the panel. */
    data class Colors(val text: String, val secondary: String, val accent: String)

    /**
     * [average]: one "Average" tag with the harmonic mean instead of a tag per frequency list,
     * as Yomitan's "Average frequencies" option; lists of [occurrenceBased] counts stay out of it.
     */
    data class Frequencies(val average: Boolean = false, val occurrenceBased: Set<String> = emptySet())

    /** [result] as a whole page; see [Page]. */
    fun document(
        result: LookupResult,
        anki: AnkiStatus?,
        styles: Map<String, String>,
        cards: Boolean,
        frequencies: Frequencies = Frequencies(),
        media: (dictionary: String, path: String) -> DictionaryMedia? = { _, _ -> null },
        audio: Boolean = false,
    ): String = Page(result, anki, styles, cards, frequencies, media, audio).first()

    /**
     * An empty results page that stays loaded: [Page.show] shows each result in it, so a lookup
     * needn't load a page, and each dictionary's styles are only parsed the first time.
     */
    fun shell(): String = page("", "")

    private fun page(styles: String, body: String) = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        append("<style>").append(css()).append(styles).append("</style><script>").append(SCRIPT)
        append("</script></head><body>").append(body).append("</body></html>")
    }

    /**
     * [result] as a page made in two parts, so that its beginning shows sooner: [first] or
     * [show], the page with the definitions that fit a budget, then [rest], a script that adds
     * the others to it.
     */
    inner class Page(
        private val result: LookupResult,
        private val anki: AnkiStatus?,
        private val styles: Map<String, String>,
        private val cards: Boolean,
        private val frequencies: Frequencies = Frequencies(),
        /** A dictionary's image by its path, for the definitions that show one */
        private val media: (dictionary: String, path: String) -> DictionaryMedia? = { _, _ -> null },
        /** A 🔊 button per result: Hiroi.playAudio(index), long-pressed Hiroi.audioMenu(index) */
        private val audio: Boolean = false,
        /** Whether the page that stays loaded ([shell]) has a dictionary's styles already */
        private val hasStyles: (String) -> Boolean = { false },
    ) {
        init {
            names = aliases()
        }

        /** The result made next, and its next definition (0: the result isn't begun) */
        private var term = 0
        private var definition = 0

        /** Whether the page has all of the results */
        private var complete = false

        /** The dictionaries the page has definitions of, and those whose styles it was given */
        private val used = LinkedHashSet<String>()
        private val styled = HashSet<String>()

        /** The dictionaries whose styles [first], [show] and [rest] have given the page so far */
        val styledSoFar: Set<String> get() = styled.toSet()

        /**
         * The page, with the results' definitions in order until they take about [budget]
         * characters (at least one); a search in sections (Latin text) all of it.
         */
        fun first(budget: Int = Int.MAX_VALUE): String {
            val body = body(budget)
            return page(StringBuilder().apply { styles() }.toString(), body)
        }

        /** As [first], a script that shows it in the [shell] page instead, in place of what it showed. */
        fun show(budget: Int = Int.MAX_VALUE): String {
            val body = body(budget)
            return "showResults(" + sheets() + "," + JSONObject.quote(body) + ");"
        }

        private fun body(budget: Int): String {
            val body = StringBuilder()
            anki?.error?.let { body.append("<div class=\"error\">").append(Html.escape(it)).append("</div>") }
            if (result.sections.isEmpty()) {
                body.results(budget)
            } else {
                // Each section a grid of small tiles, as Akebi shows them, that open into the whole result
                for ((s, section) in result.sections.withIndex()) {
                    body.append("<div class=\"section\">").append(Html.escape(section.title)).append("</div><div class=\"tiles\">")
                    for (i in section.first until (result.sections.getOrNull(s + 1)?.first ?: result.terms.size)) {
                        body.tile(i, result.terms[i], frequencies, section.words)
                        definition = 0
                        body.term(i, result.terms[i])
                        body.append("</div></div>")
                    }
                    body.append("</div>")
                }
                term = result.terms.size
                definition = 0
                body.results(Int.MAX_VALUE)
            }
            return body.toString()
        }

        /** A script that adds the rest of the results to the page [first] or [show] made, and their styles; null if it has them all. */
        fun rest(): String? {
            if (complete) return null
            val content = StringBuilder()
            if (definition > 0) {
                // The result the page ends in the middle of: its other definitions
                val items = StringBuilder().apply { definitions(result.terms[term]) }
                content.append("addDefinitions(").append(term).append(',').append(JSONObject.quote(items.toString())).append(");")
                term++
                definition = 0
            }
            val more = StringBuilder().apply { results(Int.MAX_VALUE) }
            if (more.isNotEmpty()) content.append("addResults(").append(JSONObject.quote(more.toString())).append(");")
            // The styles first, so the definitions never show without them
            return "addStyles(" + sheets() + ");" + content
        }

        /** Each dictionary's styles that the page needs and wasn't given, only for its own definitions (CSS nesting, as Jitendex itself uses) */
        private fun StringBuilder.styles() {
            for ((_, sheet) in newSheets()) append(sheet)
        }

        /** [styles] as a script's object of each dictionary's styles; the page keeps those it has, so it only parses them once. */
        private fun sheets(): String = JSONObject(newSheets().toMap()).toString()

        private fun newSheets(): List<Pair<String, String>> = used.filter { !hasStyles(it) && styled.add(it) }.mapNotNull { dictionary ->
            styles[dictionary]?.let { dictionary to ".glossary[data-dictionary=\"${cssString(dictionary)}\"] {\n$it\n}\n" }
        }

        /** The results from [term] on, until their definitions take [budget] characters, then the kanji results. */
        private fun StringBuilder.results(budget: Int) {
            val start = length
            while (term < result.terms.size) {
                if (definition == 0 && length - start >= budget) return
                if (!term(term, result.terms[term], start, budget)) return
                term++
                definition = 0
            }
            for (kanji in result.kanji) kanji(kanji, frequencies)
            complete = true
        }

        /**
         * Result [index] from its [definition] on, until the definitions since [start] take
         * [budget] characters: false if they do, the result then ending there.
         */
        private fun StringBuilder.term(index: Int, term: TermGroup, start: Int = length, budget: Int = Int.MAX_VALUE): Boolean {
            if (definition == 0) header(index, term)
            if (!definitions(term, start, budget)) {
                append("</ol></div>")
                return false
            }
            append("</ol></div>")
            return true
        }

        private fun StringBuilder.header(index: Int, term: TermGroup) {
            append("<div class=\"entry\"><div class=\"header\"><span class=\"headword\" lang=\"ja\">")
            // Grouped by term or related terms, a result can have several headwords, as in Yomitan.
            // Each one breaks inside only if it doesn't fit a line of its own, so a narrow panel moves the buttons instead.
            term.headwords.joinTo(this, "<span class=\"separator\">・</span>") {
                "<span class=\"word\">" + Furigana.html(Furigana.distribute(it.expression, it.reading)) + "</span>"
            }
            append("</span><div class=\"actions\">")
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
            append("</div></div>")
            if (term.inflections.isNotEmpty()) inflections(index, term.inflections)

            val tags = StringBuilder()
            if (anki != null && anki.error == null) {
                when {
                    anki === AnkiStatus.ASKING -> tags.tag("not-anki asking", "not in Anki", id = "anki$index")
                    term.expression in anki.notes -> tags.tag("anki", "✓ in Anki", id = "anki$index")
                    else -> tags.tag("not-anki", "not in Anki", id = "anki$index")
                }
            }
            tags.frequencyTags(termFrequencies(term), frequencies)
            tags.kanjiTag(index, term)
            val pitches = PitchAccent.positions(term)
            if (pitches.isNotEmpty()) {
                val dictionary = term.meta.firstOrNull { it.mode == "pitch" }?.dictionary.orEmpty()
                tags.tag("pitch", name(dictionary), pitches.joinToString(" ") { PitchAccent.html(term.reading, it) })
            }
            if (tags.isNotEmpty()) append("<div class=\"tags\">").append(tags).append("</div>")
            kanjiOf(index, term)
            append("<ol class=\"definitions\" id=\"definitions$index\">")
        }

        /**
         * [term]'s definitions from [definition] on, until those since [start] take [budget]
         * characters (after at least one): false if they do.
         */
        private fun StringBuilder.definitions(term: TermGroup, start: Int = length, budget: Int = Int.MAX_VALUE): Boolean {
            val all = term.definitions
            while (definition < all.size) {
                if (definition > 0 && length - start >= budget) return false
                val item = all[definition]
                // For some of the headwords only, as Yomitan marks it ("見る only")
                val only = if (term.headwords.size > 1 && item.headwordIndices.size < term.headwords.size) {
                    item.headwordIndices.joinToString("、") { headwordLabel(term, it) } + " only"
                } else {
                    null
                }
                definition(item.entry, media, only)
                used += item.entry.dictionary
                definition++
            }
            return true
        }
    }

    /**
     * The inflections from the dictionary form to the text, as Yomitan shows them (« -て « -いる);
     * tapping one shows what it means under them, as Yomitan describes it.
     */
    private fun StringBuilder.inflections(index: Int, names: List<String>) {
        val boxes = StringBuilder()
        append("<div class=\"inflections\">")
        names.withIndex().joinTo(this, " « ", "« ") { (k, name) ->
            val description = Deinflector.description(name) ?: return@joinTo Html.escape(name)
            val id = "inflection$index-$k"
            boxes.append("<div class=\"inflection-info\" id=\"").append(id).append("\" hidden><div class=\"inflection-name\">")
                .append(Html.escape(name)).append("</div><div class=\"inflection-text\">").append(Html.escape(description)).append("</div></div>")
            "<span class=\"inflection\" role=\"button\" tabindex=\"0\" aria-expanded=\"false\" aria-controls=\"$id\" onclick=\"inflection(this, '$id')\">${Html.escape(name)}</span>"
        }
        append("</div>")
        if (boxes.isNotEmpty()) append("<div class=\"inflection-infos\">").append(boxes).append("</div>")
    }

    /** A tag among the frequencies naming the kanji of [term]'s word; tapping it opens or closes them ([kanjiOf]). */
    private fun StringBuilder.kanjiTag(index: Int, term: TermGroup) {
        if (term.kanji.isEmpty()) return
        val characters = term.kanji.map { it.character }.distinct()
        append("<span class=\"tag kanji-toggle\" role=\"button\" tabindex=\"0\" aria-expanded=\"false\" aria-controls=\"kanji$index\" ")
        append("onclick=\"var k = document.getElementById('kanji$index'); k.hidden = !k.hidden; this.setAttribute('aria-expanded', !k.hidden)\">")
        append("<span class=\"label\">Kanji</span><span class=\"value\" lang=\"ja\">").append(Html.escape(characters.joinToString(" "))).append("</span></span>")
    }

    /** The kanji of [term]'s word, under its tags until its [kanjiTag] opens them: each kanji dictionary's meanings, readings and numbers for it */
    private fun StringBuilder.kanjiOf(index: Int, term: TermGroup) {
        if (term.kanji.isEmpty()) return
        val characters = term.kanji.map { it.character }.distinct()
        append("<div class=\"kanji-breakdown\" id=\"kanji$index\" hidden>")
        for (character in characters) {
            append("<div class=\"kanji-card\"><div class=\"kanji-character\" lang=\"ja\">").append(Html.escape(character)).append("</div><div>")
            for (kanji in term.kanji.filter { it.character == character }) {
                append("<div class=\"kanji-dictionary\">")
                tag("dictionary", name(kanji.dictionary))
                if (kanji.meanings.isNotEmpty()) append(" ").append(Html.escape(kanji.meanings.joinToString(", ")))
                append("</div>")
                val readings = listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi).filter { it.second.isNotBlank() }
                if (readings.isNotEmpty()) {
                    append("<div class=\"kanji-readings\" lang=\"ja\">")
                    readings.joinTo(this, " · ") { (label, r) -> "<span class=\"kanji-label\">$label</span> " + Html.escape(r.replace(" ", "、")) }
                    append("</div>")
                }
                val numbers = kanjiNumbers(kanji) + kanji.frequencies.map { (dictionary, f) -> "${Html.escape(name(dictionary))} ${Html.escape(f.display)}" }
                if (numbers.isNotEmpty()) append("<div class=\"kanji-numbers\">").append(numbers.joinToString(" · ")).append("</div>")
            }
            append("</div></div>")
        }
        append("</div>")
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
        tag("dictionary", name(entry.dictionary))
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
        for ((dictionary, values) in byDictionary) tag("frequency", name(dictionary), Html.escape(values.map { it.display }.distinct().joinToString(", ")))
    }

    private fun StringBuilder.kanji(kanji: KanjiEntry, frequencies: Frequencies) {
        append("<div class=\"entry kanji\"><div class=\"header\"><span class=\"character\" lang=\"ja\">")
        append(Html.escape(kanji.character)).append("</span></div><div class=\"tags\">")
        tag("dictionary", name(kanji.dictionary))
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
        /** The page's functions, for Hiroi to change it once it shows. */
        val SCRIPT = buildString {
            append("function setCard(i, label, enabled) { var b = document.getElementById('card' + i); if (b) { b.textContent = label; b.disabled = !enabled; } }")
            append("function inAnki(i, label) { var t = document.getElementById('anki' + i); if (t) { t.className = 'tag anki'; t.firstChild.textContent = label; } ")
            append("var o = document.getElementById('open' + i); if (o) o.style.display = ''; }")
            // Anki was asked (inAnki() marked the words it has): the other words' tags show, or none and why
            append("function ankiChecked(error) { document.querySelectorAll('.tag.asking').forEach(function (t) { if (error) t.remove(); else t.classList.remove('asking'); }); ")
            append("if (error) { var e = document.createElement('div'); e.className = 'error'; e.textContent = error; document.body.insertBefore(e, document.body.firstChild); } }")
            // A note with the same first field exists: the add button says what it would do, "Open in Anki" how many there are.
            append("function duplicate(i, label, enabled, open) { var b = document.getElementById('card' + i); ")
            append("if (b) { b.textContent = label; b.disabled = !enabled; b.className = 'card duplicate'; } ")
            append("var o = document.getElementById('open' + i); if (o) { o.style.display = ''; o.textContent = open; } }")
            // A result in the page that stays loaded (Page.show), with the dictionaries' styles it doesn't have yet
            append("var styled = {};")
            append("function addStyles(sheets) { for (var name in sheets) { if (styled[name]) continue; styled[name] = true; ")
            append("var s = document.createElement('style'); s.textContent = sheets[name]; document.head.appendChild(s); } }")
            append("function showResults(sheets, html) { addStyles(sheets); document.body.innerHTML = html; window.scrollTo(0, 0); }")
            // The rest of a page made in parts (Page.rest): styles, the other definitions of the result it ended in, the other results
            append("function addDefinitions(i, items) { document.getElementById('definitions' + i).insertAdjacentHTML('beforeend', items); }")
            // An inflection's description: tapping one shows it in place of another's, tapping it again closes it
            append("function inflection(step, id) { var box = document.getElementById(id); var show = box.hidden; ")
            append("step.parentNode.querySelectorAll('.inflection').forEach(function (s) { s.setAttribute('aria-expanded', 'false'); }); ")
            append("box.parentNode.querySelectorAll('.inflection-info').forEach(function (b) { b.hidden = true; }); ")
            append("box.hidden = !show; step.setAttribute('aria-expanded', show); }")
            append("function addResults(html) { document.body.insertAdjacentHTML('beforeend', html); }")
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
        }

        /** Up to which frequency rank a word's tile is marked as common */
        const val COMMON_RANK = 10_000

        /** The entries of a result whose glosses its tile looks through */
        const val TILE_ENTRIES = 4
    }

    private fun cssString(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun css() = """
        body { margin: 0; color: ${colors.text}; background: transparent; font-family: sans-serif; font-size: 15px; line-height: 1.45; }
        /* Chrome trims the blank half of 。」 at a line's end but still counts it as overflow, so the page could scroll sideways a bit */
        body { text-spacing-trim: space-all; }
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
        .kanji-breakdown { margin-top: 4px; }
        .tag.kanji-toggle .label { background: #2a7f8a; }
        .tag.kanji-toggle .value::after { content: " ▸"; }
        .tag.kanji-toggle[aria-expanded="true"] .value::after { content: " ▾"; }
        .kanji-card { display: flex; gap: 10px; margin-top: 6px; padding: 6px 8px; border-radius: 6px; background: ${colors.secondary}14; }
        .kanji-character { font-size: 2.2em; line-height: 1.1; }
        .kanji-dictionary { font-size: 0.9em; }
        .kanji-dictionary + .kanji-readings, .kanji-numbers + .kanji-dictionary { margin-top: 2px; }
        .kanji-readings, .kanji-numbers { color: ${colors.secondary}; font-size: 0.8em; }
        .kanji-label { font-weight: bold; }
        /* The buttons go under the word, kept together on the right, when they don't fit beside it */
        .header { display: flex; flex-wrap: wrap; align-items: flex-end; column-gap: 8px; }
        .headword { flex: 1 1 auto; font-size: 2em; line-height: 1.25; }
        .headword .word { display: inline-block; }
        .actions { display: flex; align-items: flex-end; gap: 8px; margin-left: auto; }
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
        .inflection { padding: 2px 0; text-decoration: underline dotted; text-underline-offset: 3px; }
        .inflection[aria-expanded="true"] { color: ${colors.accent}; text-decoration-style: solid; }
        .inflection-info { margin-top: 4px; padding: 6px 8px; border-radius: 6px; background: ${colors.secondary}14; font-size: 0.85em; }
        .inflection-name { font-weight: bold; }
        .inflection-text { white-space: pre-line; }
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
        .tag.asking { display: none; }
        ol.definitions { margin: 6px 0 0; padding-left: 1.5em; }
        ol.definitions > li + li { margin-top: 8px; }
        /* Definitions out of sight aren't laid out or drawn until they come near (the glossary, not the li, so its number isn't clipped) */
        .glossary { margin-top: 3px; content-visibility: auto; contain-intrinsic-size: auto 100px; }
        .gloss-image-description { color: ${colors.secondary}; font-size: 0.85em; }
        details.gloss-image > summary { color: ${colors.accent}; }
        .glossary ul, .glossary ol { margin: 2px 0; padding-left: 1.3em; }
        .kanji .character { font-size: 3em; line-height: 1.1; }
        .kanji table { margin-top: 6px; border-collapse: collapse; }
        .kanji th { padding-right: 10px; color: ${colors.secondary}; font-weight: normal; text-align: left; }
    """.trimIndent()
}
