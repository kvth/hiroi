package io.github.kvth.hiroi

import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.Html
import io.github.kvth.hiroi.anki.Pronunciations
import io.github.kvth.hiroi.anki.StructuredHtml
import io.github.kvth.hiroi.dictionary.DictionaryMedia
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Meanings
import io.github.kvth.hiroi.dictionary.ResultTabs
import io.github.kvth.hiroi.dictionary.TagInfo
import io.github.kvth.hiroi.dictionary.Tags
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan
import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Japanese
import io.github.kvth.hiroi.japanese.KanjiParts
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
 * A lookup result as HTML, laid out like Yomitan's popup: the headword with furigana and its tags,
 * the inflections, tags for Anki and frequency, the pronunciations (pitch accents, IPA), then the numbered definitions with their dictionary,
 * styled by the dictionary's own styles.css. What's tapped goes to Hiroi as a message ([SCRIPT]'s
 * hiroi(), which [LookupSession.handle] takes): "＋ Anki" buttons send {type: "addCard", index}.
 */
class ResultsHtml(
    private val colors: Colors,
    /** The names dictionaries were given (their aliases, Yomitan's "Rename…") by title, read for each page */
    private val aliases: () -> Map<String, String> = { emptyMap() },
    /** What kanji are built from, for their cards' "Parts" and "Used in"; null without */
    private val kanjiParts: () -> KanjiParts? = { null },
) {
    /** The names of the page being made ([Page]) */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    /** What dictionary [title] is called on the page */
    private fun name(title: String) = names[title] ?: title

    /** CSS colours from the app theme, so the results match the panel; [background] behind the tabs, which stay at the top. */
    data class Colors(val text: String, val secondary: String, val accent: String, val background: String = "transparent")

    /**
     * The tabs ([ResultTabs]) to show the results in: each term dictionary's, by title
     * ([byDictionary]), in [order]; and 漢字 if a kanji dictionary is on ([kanji]).
     */
    data class Tabs(val byDictionary: Map<String, String>, val order: List<String>, val kanji: Boolean) {
        /** Whether there's more than one, so choosing one shows less */
        val shown: Boolean get() = order.size + (if (kanji) 1 else 0) > 1
    }

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
        tabs: Tabs? = null,
        pitchAccents: Pronunciations.PitchForms = Pronunciations.PitchForms(),
        tags: (dictionary: String, names: Collection<String>) -> List<TagInfo> = { _, _ -> emptyList() },
    ): String = Page(result, anki, styles, cards, frequencies, media, audio, tabs = tabs, pitchAccents = pitchAccents, findTags = tags).first()

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
        /** A 🔊 button per result: playAudio, long-pressed audioMenu */
        private val audio: Boolean = false,
        /** Whether the page that stays loaded ([shell]) has a dictionary's styles already */
        private val hasStyles: (String) -> Boolean = { false },
        /** The tabs to choose from, if there's more than one ([Tabs.shown]) */
        tabs: Tabs? = null,
        /** The forms pitch accents are shown in */
        private val pitchAccents: Pronunciations.PitchForms = Pronunciations.PitchForms(),
        /** What a dictionary's tags mean, from its tag bank ([io.github.kvth.hiroi.dictionary.DictionaryStore.findTags]) */
        private val findTags: (dictionary: String, names: Collection<String>) -> List<TagInfo> = { _, _ -> emptyList() },
    ) {
        private val tabs = tabs?.takeIf { it.shown }

        /** A dictionary's tab */
        private fun tabOf(dictionary: String) = tabs?.byDictionary?.get(dictionary)

        /** The kanji of the results' words */
        private val wordKanji = result.terms.flatMapTo(LinkedHashSet()) { term -> cardKanji(term) }

        /** The tabs [term] is in: those of its definitions' dictionaries, and 漢字 if its word has kanji */
        private fun tabsOf(term: TermGroup): List<String> =
            term.definitions.mapNotNull { tabOf(it.entry.dictionary) }.distinct() + listOfNotNull(ResultTabs.KANJI.takeIf { cardKanji(term).isNotEmpty() })

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
            body.tabBar()
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

        /**
         * The tabs, each with how many results it has (漢字: how many kanji), the empty ones
         * greyed; the page shows All until [show]'s script chooses one.
         */
        private fun StringBuilder.tabBar() {
            val tabs = tabs ?: return
            val counts = LinkedHashMap<String, Int>()
            for (tab in tabs.order) counts[tab] = result.terms.count { term -> term.definitions.any { tabOf(it.entry.dictionary) == tab } }
            if (tabs.kanji) counts[ResultTabs.KANJI] = (wordKanji + result.kanji.map { it.character }).size
            append("<div class=\"tabs\" id=\"tabs\" data-selected=\"\" role=\"tablist\">")
            tab("", "All", result.terms.size + result.kanji.count { it.character !in wordKanji })
            for ((tab, count) in counts) tab(tab, tab, count)
            append("</div>")
        }

        private fun StringBuilder.tab(tab: String, label: String, count: Int) {
            append("<button class=\"tab\" role=\"tab\" data-tab=\"").append(Html.escape(tab)).append("\" onclick=\"selectTab(this)\"")
            if (count == 0) append(" disabled")
            append('>').append(Html.escape(label)).append(" <span class=\"count\">").append(count).append("</span></button>")
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
            // The styles first, so the definitions never show without them; what's added shown as the chosen tab says
            return "addStyles(" + sheets() + ");" + content + "applyTab();"
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
            // The kanji of the text: in 漢字 too, unless a word's kanji there show it already
            for (kanji in result.kanji) {
                kanji(kanji, Tags.expand(findTags, kanji.dictionary, kanji.tags.split(' ')), frequencies, if (tabs?.kanji == true && kanji.character !in wordKanji) ResultTabs.KANJI else "")
            }
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
            append("<div class=\"entry\"")
            if (tabs != null) append(" data-tabs=\"").append(Html.escape(tabsOf(term).joinToString(" "))).append('"')
            append("><div class=\"header\"><span class=\"headword\" lang=\"ja\">")
            // Grouped by term or related terms, a result can have several headwords, as in Yomitan.
            // Each one breaks inside only if it doesn't fit a line of its own, so a narrow panel moves the buttons instead.
            term.headwords.joinTo(this, "<span class=\"separator\">・</span>") {
                "<span class=\"word\">" + Furigana.html(Furigana.distribute(it.expression, it.reading)) + "</span>"
            }
            append("</span><div class=\"actions\">")
            if (audio) {
                append("<button class=\"audio\" id=\"audio$index\" data-index=\"$index\" onclick=\"hiroi({type: 'playAudio', index: $index})\" ")
                append("oncontextmenu=\"hiroi({type: 'audioMenu', index: $index}); return false;\">🔊</button>")
            }
            // Shown when the word has a note (or once one was added), like Yomitan's "view note" button.
            val hasNote = anki?.notes?.containsKey(term.expression) == true
            append("<button class=\"card\" id=\"open$index\" onclick=\"hiroi({type: 'openNote', index: $index})\"")
            if (!hasNote) append(" style=\"display:none\"")
            append(">Open in Anki</button>")
            if (cards) append("<button class=\"card\" id=\"card$index\" onclick=\"hiroi({type: 'addCard', index: $index})\">＋ Anki</button>")
            append("</div></div>")
            headwordTags(term)
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
            if (tags.isNotEmpty()) append("<div class=\"tags\">").append(tags).append("</div>")
            kanjiOf(index, term)
            pronunciations(term)
            append("<ol class=\"definitions\" id=\"definitions$index\">")
        }

        /**
         * The tags of [term]'s headwords, from their entries' term tags, as Yomitan groups them
         * (groupTermTags): a tag for only some of the headwords says which they are when tapped.
         */
        private fun StringBuilder.headwordTags(term: TermGroup) {
            val byHeadword = term.headwords.indices.map { i ->
                val names = term.entries.filter { term.headwordIndex(it) == i }.groupBy({ it.dictionary }, { it.termTags })
                Tags.expand(findTags, names.mapValues { (_, tags) -> tags.flatMap { it.split(' ') } })
            }
            val grouped = LinkedHashMap<Tags.Tag, MutableList<Int>>()
            byHeadword.forEachIndexed { i, tags -> for (tag in tags) grouped.getOrPut(tag) { mutableListOf() } += i }
            if (grouped.isEmpty()) return
            append("<div class=\"tags headword-tags\">")
            for ((tag, indices) in grouped) {
                val only = if (indices.size < term.headwords.size) {
                    indices.joinToString("") { "<span class=\"tag-details-disambiguation\">" + Furigana.html(Furigana.distribute(term.headwords[it].expression, term.headwords[it].reading)) + "</span>" }
                } else {
                    null
                }
                tag(tag, only)
            }
            append("</div>")
        }

        /**
         * [term]'s pronunciations of its reading: a line per dictionary, its tag then each
         * pronunciation with its own tags, as Yomitan's pronunciation groups. Pitch accents in the
         * forms [pitchAccents] asks for (none without any), IPA as it is.
         */
        private fun StringBuilder.pronunciations(term: TermGroup) {
            val groups = Pronunciations.groups(term, pitches = pitchAccents.any).ifEmpty { return }
            append("<ul class=\"pronunciation-group-list\">")
            for (group in groups) {
                append("<li class=\"pronunciation-group\">")
                tag("pitch", name(group.dictionary))
                append("<ul class=\"pronunciation-list\">")
                for (pronunciation in group.pronunciations) {
                    when (pronunciation) {
                        is Pronunciations.Pitch -> append("<li class=\"pronunciation\" data-pronunciation-type=\"pitch-accent\" lang=\"ja\">")
                        is Pronunciations.Transcription -> append("<li class=\"pronunciation\" data-pronunciation-type=\"phonetic-transcription\">")
                    }
                    for (tag in Tags.expand(findTags, group.dictionary, pronunciation.tags)) tag(tag)
                    when (pronunciation) {
                        is Pronunciations.Pitch -> append(Pronunciations.html(term.reading, pronunciation, pitchAccents))
                        is Pronunciations.Transcription -> append("<span class=\"pronunciation-text-container\">").append(Html.escape(pronunciation.ipa)).append("</span>")
                    }
                    append("</li>")
                }
                append("</ul></li>")
            }
            append("</ul>")
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
                definition(item.entry, Tags.expand(findTags, item.entry.dictionary, item.entry.definitionTags.split(' ')), media, only, tabOf(item.entry.dictionary))
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

    /**
     * The kanji of [term]'s word that get a card, in its order: those a kanji dictionary has, and
     * the others whose parts are known ([kanjiParts]), which get a card with just those.
     */
    private fun cardKanji(term: TermGroup): List<String> {
        val inDictionaries = term.kanji.mapTo(HashSet()) { it.character }
        val parts = kanjiParts()
        return term.expression.filter(Japanese::isKanji).map { it.toString() }.distinct()
            .filter { it in inDictionaries || parts?.has(it) == true }
    }

    /** A tag among the frequencies naming the kanji of [term]'s word; tapping it opens or closes them ([kanjiOf]). */
    private fun StringBuilder.kanjiTag(index: Int, term: TermGroup) {
        val characters = cardKanji(term)
        if (characters.isEmpty()) return
        append("<span class=\"tag kanji-toggle\" role=\"button\" tabindex=\"0\" aria-expanded=\"false\" aria-controls=\"kanji$index\" ")
        append("onclick=\"var k = document.getElementById('kanji$index'); k.hidden = !k.hidden; this.setAttribute('aria-expanded', !k.hidden)\">")
        append("<span class=\"label\">Kanji</span><span class=\"value\" lang=\"ja\">").append(Html.escape(characters.joinToString(" "))).append("</span></span>")
    }

    /** The kanji of [term]'s word ([cardKanji]), under its tags until its [kanjiTag] opens them: each kanji dictionary's meanings, readings and numbers for it, and its parts */
    private fun StringBuilder.kanjiOf(index: Int, term: TermGroup) {
        val characters = cardKanji(term)
        if (characters.isEmpty()) return
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
            kanjiParts(character)
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

    /** A definition of [entry] with its [tags]; [tab]: its dictionary's, if the page has tabs */
    private fun StringBuilder.definition(entry: TermEntry, tags: List<Tags.Tag>, media: (String, String) -> DictionaryMedia?, only: String? = null, tab: String? = null) {
        append("<li")
        if (tab != null) append(" data-tab=\"").append(Html.escape(tab)).append('"')
        append("><div class=\"definition-tags\">")
        if (only != null) tag("only", only)
        for (tag in tags) tag(tag)
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

    /** A kanji of the text with its [tags]; [tabs]: the tabs it shows in besides All, if the page has tabs */
    private fun StringBuilder.kanji(kanji: KanjiEntry, tags: List<Tags.Tag>, frequencies: Frequencies, tabs: String? = null) {
        append("<div class=\"entry kanji\"")
        if (tabs != null) append(" data-tabs=\"").append(Html.escape(tabs)).append('"')
        append("><div class=\"header\"><span class=\"character\" lang=\"ja\">")
        append(Html.escape(kanji.character)).append("</span></div><div class=\"tags\">")
        for (tag in tags) tag(tag)
        // A kanji no dictionary has (only its parts are known) has no dictionary to name
        if (kanji.dictionary.isNotEmpty()) tag("dictionary", name(kanji.dictionary))
        frequencyTags(kanji.frequencies, frequencies)
        append("</div><table>")
        for ((label, readings) in listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi)) {
            if (readings.isNotBlank()) append("<tr><th>$label</th><td lang=\"ja\">").append(Html.escape(readings.replace(" ", "、"))).append("</td></tr>")
        }
        append("</table>")
        kanjiParts(kanji.character)
        kanji.meanings.joinTo(this, "", "<ol class=\"definitions\">", "</ol>") { "<li>${Html.escape(it)}</li>" }
        append("</div>")
    }

    /**
     * What [character] is built from ("Parts 亻(人) + 動") and the kanji built with it ("Used in",
     * the commonest first, the rest folded), each a link to that kanji's card.
     */
    private fun StringBuilder.kanjiParts(character: String) {
        val data = kanjiParts() ?: return
        val parts = data.parts(character)
        val usedIn = data.usedIn(character)
        if (parts.isEmpty() && usedIn.isEmpty()) return
        append("<div class=\"kanji-parts\" lang=\"ja\">")
        if (parts.isNotEmpty()) {
            append("<div><span class=\"kanji-label\">Parts</span> ")
            parts.joinTo(this, " + ") { kanjiLink(it.kanji, it.label) }
            append("</div>")
        }
        if (usedIn.isNotEmpty()) {
            append("<div><span class=\"kanji-label\">Used in</span> ")
            usedIn.take(USED_IN).joinTo(this, " ") { kanjiLink(it, it) }
            if (usedIn.size > USED_IN) {
                append(" <details class=\"used-in-more\"><summary>+").append(usedIn.size - USED_IN).append("</summary>")
                usedIn.drop(USED_IN).joinTo(this, " ") { kanjiLink(it, it) }
                append("</details>")
            }
            append("</div>")
        }
        append("</div>")
    }

    /** [label], a link to [kanji]'s card (a part without a character has none) */
    private fun kanjiLink(kanji: String?, label: String): String =
        if (kanji == null) Html.escape(label) else "<span class=\"link\" data-kanji=\"${Html.escape(kanji)}\">${Html.escape(label)}</span>"

    /** A tag like Yomitan's: a coloured label, optionally followed by a value (frequency, pitch). */
    private fun StringBuilder.tag(kind: String, label: String, valueHtml: String? = null, id: String? = null) {
        append("<span class=\"tag $kind\"")
        if (id != null) append(" id=\"").append(id).append('"')
        append("><span class=\"label\">").append(Html.escape(label)).append("</span>")
        if (valueHtml != null) append("<span class=\"value\">").append(valueHtml).append("</span>")
        append("</span>")
    }

    /**
     * A tag from a tag bank ([Tags]), coloured by its category as Yomitan colours it. Tapping it
     * shows its notes, or its name without any, at the bottom of the page (showTag), as Yomitan's
     * tag notification; with [headwordsHtml], the headwords it's for when it's only for some.
     */
    private fun StringBuilder.tag(tag: Tags.Tag, headwordsHtml: String? = null) {
        val notes = tag.notes.joinToString("\n")
        append("<span class=\"tag label\" data-category=\"").append(Html.escape(tag.category)).append('"')
        if (notes.isNotEmpty()) append(" title=\"").append(Html.escape(notes)).append('"')
        append(" data-details=\"").append(Html.escape(notes.ifEmpty { tag.name })).append('"')
        if (headwordsHtml != null) append(" data-headwords=\"").append(Html.escape(headwordsHtml)).append('"')
        append(" role=\"button\" tabindex=\"0\" onclick=\"showTag(this)\"><span class=\"label\">").append(Html.escape(tag.name)).append("</span></span>")
    }

    private companion object {
        /**
         * A tap in a definition or a kanji's meanings looks up the word starting at the character
         * tapped, in the text of the whole definition (without furigana or hidden parts, a line
         * break between blocks); a tap on a link to another word looks that up. Not while text is selected, nor
         * on buttons, images or the inflections.
         */
        val NESTED_LOOKUP = """
            document.addEventListener('click', function (e) {
              var t = e.target;
              if (!t.closest || !window.getSelection().isCollapsed) return;
              var g = t.closest('.glossary, .kanji .definitions, .kanji-card, .kanji-parts');
              if (!g || t.closest('button, summary, img, .inflection, .tag')) return;
              var kanji = t.closest('[data-kanji]');
              if (kanji) { hiroi({type: 'lookUpKanji', character: kanji.dataset.kanji}); return; }
              var link = t.closest('[data-query]');
              if (link) { hiroi({type: 'lookUpIn', text: link.dataset.query, offset: 0}); return; }
              var r = document.caretRangeFromPoint(e.clientX, e.clientY);
              if (!r || r.startContainer.nodeType !== 3 || !g.contains(r.startContainer)) return;
              var node = r.startContainer, at = r.startOffset;
              function hit(o) {
                if (o < 0 || o >= node.length) return false;
                var q = document.createRange(); q.setStart(node, o); q.setEnd(node, o + 1);
                var b = q.getBoundingClientRect();
                return e.clientX >= b.left && e.clientX <= b.right && e.clientY >= b.top && e.clientY <= b.bottom;
              }
              if (!hit(at)) { if (hit(at - 1)) at--; else return; }
              var text = '', offset = -1;
              (function walk(n) {
                if (n.nodeType === 3) { if (n === node) offset = text.length + at; text += n.nodeValue; return; }
                if (n.nodeType !== 1 || n.tagName === 'RT' || n.tagName === 'RP') return;
                if (n.tagName === 'BR') { text += '\n'; return; }
                var display = n === g ? 'block' : getComputedStyle(n).display;
                if (display === 'none') return;
                var block = n !== g && !/^(inline|ruby|contents)/.test(display);
                if (block) text += '\n';
                for (var c = n.firstChild; c; c = c.nextSibling) walk(c);
                if (block) text += '\n';
              })(g);
              if (offset >= 0) hiroi({type: 'lookUpIn', text: text, offset: offset});
            });
        """.trimIndent().replace("\n", " ")

        /**
         * Tabs ([Tabs]): the one chosen shows the results it has, with only its definitions (漢字:
         * the words' kanji instead of their definitions), as the bar's data-selected says. Choosing
         * one is kept for the next results; useTab() chooses it, or All if the page has none in it.
         */
        val TABS = """
            function applyTab() {
              var bar = document.getElementById('tabs'), tab = bar ? bar.dataset.selected : '';
              document.body.classList.toggle('kanji-tab', tab === '${ResultTabs.KANJI}');
              if (bar) bar.querySelectorAll('.tab').forEach(function (b) { b.setAttribute('aria-selected', b.dataset.tab === tab); });
              document.querySelectorAll('.entry[data-tabs]').forEach(function (e) {
                e.hidden = tab !== '' && e.dataset.tabs.split(' ').indexOf(tab) < 0;
                var tile = e.closest('.tile'); if (tile) tile.hidden = e.hidden;
              });
              document.querySelectorAll('ol.definitions > li[data-tab]').forEach(function (li) {
                li.hidden = tab !== '' && tab !== '${ResultTabs.KANJI}' && li.dataset.tab !== tab;
              });
            }
            function useTab(tab) {
              var bar = document.getElementById('tabs'); if (!bar) return;
              var b = Array.prototype.find.call(bar.querySelectorAll('.tab'), function (b) { return b.dataset.tab === tab && !b.disabled; });
              bar.dataset.selected = b ? tab : '';
              applyTab();
            }
            function selectTab(b) {
              if (b.disabled) return;
              document.getElementById('tabs').dataset.selected = b.dataset.tab;
              applyTab(); window.scrollTo(0, 0); hiroi({type: 'selectTab', tab: b.dataset.tab});
            }
        """.trimIndent().replace("\n", " ")

        /**
         * The page's functions, for Hiroi to change it once it shows. What's tapped goes to Hiroi
         * with hiroi(): a message, as JSON, to the Hiroi object the platform gives the page.
         */
        val SCRIPT = buildString {
            append("function hiroi(message) { Hiroi.post(JSON.stringify(message)); }")
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
            // A tag's notes (and the headwords it's for, if only some) at the bottom of the page, as Yomitan's tag notification; another tag's replace them
            append("function showTag(t) { var n = document.getElementById('tag-notification'); ")
            append("if (!n) { n = document.createElement('div'); n.id = 'tag-notification'; n.className = 'footer-notification'; n.setAttribute('role', 'status'); ")
            append("n.innerHTML = '<div class=\"footer-notification-body\"><div class=\"tag-details\"></div><div class=\"tag-details-disambiguation-list\"></div></div>' + ")
            append("'<button class=\"footer-notification-close\" aria-label=\"Close\" onclick=\"closeTag()\">✕</button>'; document.body.appendChild(n); } ")
            append("n.querySelector('.tag-details').textContent = t.dataset.details; n.querySelector('.tag-details-disambiguation-list').innerHTML = t.dataset.headwords || ''; }")
            append("function closeTag() { var n = document.getElementById('tag-notification'); if (n) n.remove(); }")
            // The 🔊 button while its audio loads, and when there's none
            append("function audioState(i, state) { var b = document.getElementById('audio' + i); if (b) b.className = 'audio ' + state; }")
            // On a touch screen the button takes its touches itself: a tap plays, a long press opens the
            // audio menu, and the page doesn't start selecting the words next to it.
            append("document.addEventListener('touchstart', function (e) { var b = e.target.closest && e.target.closest('.audio'); if (!b) return; ")
            append("e.preventDefault(); b.timer = setTimeout(function () { b.timer = null; hiroi({type: 'audioMenu', index: +b.dataset.index}); }, 500); }, {passive: false});")
            append("document.addEventListener('touchend', function (e) { var b = e.target.closest && e.target.closest('.audio'); if (!b) return; ")
            append("e.preventDefault(); if (b.timer) { clearTimeout(b.timer); b.timer = null; hiroi({type: 'playAudio', index: +b.dataset.index}); } }, {passive: false});")
            // A tile opens into its result (and closes when its headword is tapped)
            append("function openTile(i) { var t = document.getElementById('tile' + i); t.classList.add('open'); t.scrollIntoView({block: 'nearest'}); }")
            append("document.addEventListener('click', function (e) { var h = e.target.closest && e.target.closest('.tile.open .headword'); ")
            append("if (h) h.closest('.tile').classList.remove('open'); });")
            // Results looked up from a definition (Yomitan's nested popups) go in place of these, which wait on a stack to come back
            append("var stack = [];")
            append("function pushResults() { closeTag(); stack.push({html: document.body.innerHTML, y: window.scrollY}); }")
            append("function popResults() { var s = stack.pop(); if (!s) return; document.body.innerHTML = s.html; window.scrollTo(0, s.y); }")
            append("function clearStack() { stack = []; }")
            append("function backBar(label) { var b = document.createElement('div'); b.className = 'back'; b.setAttribute('role', 'button'); ")
            append("b.textContent = '‹ ' + label; b.onclick = function () { hiroi({type: 'back'}); }; document.body.insertBefore(b, document.body.firstChild); }")
            append(NESTED_LOOKUP)
            append(TABS)
        }


        /** How many kanji built with a kanji its card shows before the others are folded */
        const val USED_IN = 24

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
        /* Between the results a tab shows: hidden ones in between don't count */
        .entry:not([hidden]) ~ .entry:not([hidden]) { margin-top: 14px; padding-top: 12px; border-top: 1px solid ${colors.secondary}40; }
        .tabs { position: sticky; top: 0; z-index: 1; display: flex; gap: 4px; overflow-x: auto; margin: 0 0 8px; padding: 4px 0; background: ${colors.background}; }
        .tab { flex: none; padding: 3px 10px; border: none; border-radius: 14px; background: none; color: ${colors.secondary}; font: inherit; font-size: 0.9em; }
        .tab[aria-selected="true"] { background: ${colors.accent}1f; color: ${colors.accent}; font-weight: bold; }
        .tab:disabled { opacity: 0.4; }
        .tab .count { font-size: 0.8em; font-weight: normal; opacity: 0.75; }
        /* 漢字: each word with its kanji, open, instead of its definitions */
        .kanji-tab ol.definitions, .kanji-tab .inflections, .kanji-tab .inflection-infos, .kanji-tab .tag.kanji-toggle { display: none; }
        .kanji-tab .kanji-breakdown { display: block; }
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
        .kanji-parts { margin-top: 4px; font-size: 0.9em; line-height: 1.7; }
        .kanji-parts .kanji-label { color: ${colors.secondary}; font-size: 0.85em; margin-right: 4px; }
        .kanji-parts .link { font-size: 1.2em; text-decoration: none; }
        .used-in-more { display: inline; }
        .used-in-more > summary { display: inline; color: ${colors.accent}; cursor: pointer; }
        .used-in-more[open] > summary { display: none; }
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
        /* Tag-bank tags by their category, in Yomitan's colours */
        .tag[data-category="name"] .label { background: #992a67; }
        .tag[data-category="expression"] .label { background: #b07f39; }
        .tag[data-category="popular"] .label { background: #025caa; }
        .tag[data-category="frequent"] .label { background: #4490a7; }
        .tag[data-category="archaism"] .label { background: #b04340; }
        .tag[data-category="dictionary"] .label { background: #9057ad; }
        .tag[data-category="frequency"] .label { background: #489148; }
        .tag[data-category="partOfSpeech"] .label { background: #565656; }
        .tag[data-category="pronunciation-dictionary"] .label { background: #6640be; }
        .tag[data-details] { cursor: pointer; }
        .footer-notification { position: fixed; left: 8px; right: 8px; bottom: 8px; z-index: 2; display: flex; align-items: flex-start;
            max-height: calc(1em + 4 * 1.45em); overflow: auto; border: 1px solid ${colors.secondary}40; border-radius: 6px;
            background: ${colors.background.takeIf { it != "transparent" } ?: "white"}; box-shadow: 0 2px 4px #0000004d; font-size: 0.9em; }
        .footer-notification-body { flex: 1 1 auto; padding: 0.5em 0.75em; white-space: pre-line; }
        .footer-notification-close { position: sticky; top: 0; flex: none; padding: 0.5em 0.75em; border: none; background: none; color: inherit; font: inherit; }
        .tag-details-disambiguation:not(:last-child)::after { content: ", "; }
        .pronunciation-group-list, .pronunciation-list { margin: 0; padding: 0; list-style: none; }
        .pronunciation-group-list { margin-top: 4px; }
        .pronunciation-group { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 10px; }
        .pronunciation-group + .pronunciation-group { margin-top: 2px; }
        .pronunciation-list { display: contents; }
        .pronunciation { display: inline-flex; align-items: center; gap: 6px; }
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
        .back { display: inline-block; margin-bottom: 10px; padding: 3px 12px; border-radius: 14px; background: ${colors.accent}1f; color: ${colors.accent}; font-size: 0.9em; }
        .link { color: ${colors.accent}; text-decoration: underline; text-underline-offset: 2px; }
        .kanji table { margin-top: 6px; border-collapse: collapse; }
        .kanji th { padding-right: 10px; color: ${colors.secondary}; font-weight: normal; text-align: left; }
    """.trimIndent() + "\n" + Pronunciations.CSS
}
