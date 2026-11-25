package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.japanese.Deinflection
import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Fuseji
import io.github.kvth.hiroi.japanese.Japanese
import io.github.kvth.hiroi.japanese.Mazegaki
import io.github.kvth.hiroi.japanese.Romaji
import io.github.kvth.hiroi.japanese.TextVariants

interface DictionaryStore {
    /** Entries whose expression or reading is one of [forms]; their glossaries may be left out ([withGlossaries]). */
    fun findTerms(forms: Collection<String>): List<TermEntry>

    /**
     * [entries], in the same order, with their glossaries. The searches can leave those out
     * (empty), so that only the results that are shown read theirs: they're the big part of a
     * dictionary, and most entries a lookup finds don't make it into the results.
     */
    fun withGlossaries(entries: List<TermEntry>): List<TermEntry> = entries

    fun findTermMeta(expressions: Collection<String>): List<TermMeta>

    /**
     * The dictionaries turned on that have forms of other words among their terms
     * ([Yomitan.FormOf]): only their entries' glossaries are read to tell those apart.
     */
    fun dictionariesWithForms(): Set<String> = emptySet()

    fun findKanji(character: String): List<KanjiEntry>

    /** Kanji meta bank rows (frequencies) for [character]; [TermMeta.expression] is the character. */
    fun findKanjiMeta(character: String): List<TermMeta> = emptyList()

    /** Image [path] of [dictionary], if the dictionary came with it. */
    fun findMedia(dictionary: String, path: String): DictionaryMedia? = null

    /** What the tags [names] of [dictionary] mean, from its tag bank; unknown tags are left out. */
    fun findTags(dictionary: String, names: Collection<String>): List<TagInfo> = emptyList()

    /** [dictionary]'s entries with one of [sequences] (its related terms, for "Group related terms"). */
    fun findTermsBySequence(dictionary: String, sequences: Collection<Long>): List<TermEntry> = emptyList()

    /**
     * Up to [limit] entries of the dictionaries turned on whose expression or reading starts
     * with [affix], or ends with it if [suffix]; the shortest first.
     */
    fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int): List<TermEntry> = emptyList()

    /**
     * The keys (expressions and readings) of the dictionaries turned on that start with [affix],
     * or end with it if [suffix], and that [accepts], up to [limit] of each. A suffix search only searches
     * dictionaries that are ready for one ([findTermsByAffix] makes them so): it's for lookups,
     * which mustn't wait for that.
     */
    fun findKeys(affix: String, suffix: Boolean, limit: Int, accepts: (String) -> Boolean): List<String> = emptyList()

    /**
     * Up to [limit] entries of the dictionaries turned on whose definitions have the words of
     * [query] ([Meanings.words]), the last as the start of a word too; each with how well it fits,
     * lower fitting better. Only dictionaries with definitions in Latin letters have them.
     */
    fun findTermsByMeaning(query: String, limit: Int): List<Pair<TermEntry, Int>> = emptyList()

    /** How many terms' definitions have each of [words] in the dictionaries turned on, those that any have */
    fun meaningWordCounts(words: Collection<String>): Map<String, Int> = emptyMap()

    /** Those of [forms] that are a term's expression or reading in a dictionary turned on */
    fun existingForms(forms: Collection<String>): Set<String> = emptySet()
}

/**
 * A wildcard search, as on Yomitan's search page: `食べ*` finds the words starting with 食べ,
 * `*べる` ([suffix]) those ending in べる, without deinflecting. Either asterisk works (* or ＊);
 * with both, as in Yomitan, it's a suffix search. [start] is where [affix] begins in the query.
 */
data class Wildcard(val affix: String, val suffix: Boolean, val start: Int) {
    companion object {
        /** The wildcard search [query] asks for; null if it has no asterisk at either end. */
        fun parse(query: String): Wildcard? {
            val lead = query.takeWhile(::isStar).length
            val trail = query.drop(lead).takeLastWhile(::isStar).length
            if (lead == 0 && trail == 0) return null
            return Wildcard(query.substring(lead, query.length - trail), lead > 0, lead)
        }

        private fun isStar(c: Char) = c == '*' || c == '＊'
    }
}

/**
 * How results are grouped, as Yomitan's "Result grouping mode": [mode] "split" (every definition
 * on its own), "group" (term-reading pairs), "term" (by term, whatever the reading) or "merge"
 * (related terms: entries of [mainDictionary] with the same sequence number, with the
 * definitions of the [secondary] dictionaries for their headwords).
 */
data class Grouping(val mode: String = "group", val mainDictionary: String = "", val secondary: Set<String> = emptySet()) {
    companion object {
        val MODES = listOf("split", "group", "term", "merge")
    }
}

/** A result's headword; [primary]: the scanned text matched it (not only related to it, when grouping related terms). */
data class Headword(val expression: String, val reading: String, val primary: Boolean = true)

/** A definition to show: an entry with the headwords it's for, as indices into [TermGroup.headwords]. */
data class Definition(val entry: TermEntry, val headwordIndices: List<Int>)

/** Sorting results by one frequency dictionary, as Yomitan's "Frequency sorting dictionary"; [ascending] for ranks. */
data class FrequencySort(val dictionary: String, val ascending: Boolean)

/**
 * A result: entries from one or more dictionaries, by default sharing a headword and reading
 * ([expression], [reading]); grouped otherwise ([Grouping]), they can have several [headwords],
 * the first being [expression] and [reading]. [source] is the scanned text they matched;
 * [inflections] the transforms from the headword to [source] (empty if it matched as written);
 * [deinflected] the form that was looked up (the headword, or its reading), and [transformed]
 * [source] as it was read for that ([TextVariants]: as hiragana, iteration marks spelled out…).
 */
data class TermGroup(
    val expression: String,
    val reading: String,
    val source: String,
    val inflections: List<String>,
    val entries: List<TermEntry>,
    val meta: List<TermMeta>,
    val deinflected: String = expression,
    val transformed: String = source,
    val headwords: List<Headword> = listOf(Headword(expression, reading)),
    /** The kanji of [expression], each as the kanji dictionaries have it, in their order */
    val kanji: List<KanjiEntry> = emptyList(),
) {
    /** The headword of [entry] in [headwords] */
    fun headwordIndex(entry: TermEntry): Int =
        headwords.indexOfFirst { it.expression == entry.expression && it.reading == entry.reading }.coerceAtLeast(0)

    /** [entries] with their headwords; one that's the same as another (dictionary, tags, glossary) comes once, for both. */
    val definitions: List<Definition> by lazy {
        val out = LinkedHashMap<Triple<String, String, String>, Pair<TermEntry, MutableList<Int>>>()
        for (entry in entries) {
            val definition = out.getOrPut(Triple(entry.dictionary, entry.definitionTags, entry.glossary)) { entry to mutableListOf() }
            val index = headwordIndex(entry)
            if (index !in definition.second) definition.second += index
        }
        out.values.map { (entry, indices) -> Definition(entry, indices.sorted()) }
    }
}

/** [start]..[end] is the longest matched text. */
data class LookupResult(
    val start: Int,
    val end: Int,
    val terms: List<TermGroup>,
    val kanji: List<KanjiEntry>,
    /** Headings of groups of [terms], for a search that finds them in several ways ([ResultSection]) */
    val sections: List<ResultSection> = emptyList(),
)

/** A heading ([title]) over [terms][LookupResult.terms] from [first] on; [words]: what its definitions were searched for, if they were */
data class ResultSection(val first: Int, val title: String, val words: List<String> = emptyList())

/**
 * Yomitan-style lookup: the longest dictionary word, as written or deinflected, starting at a
 * position. [frequencySort] gives the frequency dictionary to sort by, if any.
 */
class Translator(
    private val store: DictionaryStore,
    private val frequencySort: () -> FrequencySort? = { null },
    private val grouping: () -> Grouping = { Grouping() },
    /** The switches per dictionary: "Part of speech filtering" and "Use deinflections" */
    private val options: () -> DictionaryOptions = { DictionaryOptions() },
) {

    /**
     * [transformed]: [source] as it was read to find [form] ([TextVariants]), with [steps] changes;
     * [fit]: how well a search by meaning fits it, lower fitting better (0 for others); [fits]:
     * whether an entry of [form] is one it finds (all, if null).
     */
    private class Candidate(
        val form: String,
        val source: String,
        val conditions: Int,
        val inflections: List<String>,
        val transformed: String = source,
        val steps: Int = 0,
        val fit: Int = 0,
        val fits: ((TermEntry) -> Boolean)? = null,
    )

    /** [definitions]: read the results' glossaries (not needed for furigana, say). */
    fun lookUp(text: String, start: Int, definitions: Boolean = true): LookupResult? {
        if (start !in text.indices || !TextVariants.scans(text[start]) && !Fuseji.isFuseji(text[start])) return null
        // From a Latin letter or digit (Ｔシャツ, ＣＤ, １日) or a censored character (○ね), as Yomitan's
        // popup: only with Japanese text near, and only a word
        val latin = TextVariants.isLatin(text[start]) || Fuseji.isFuseji(text[start])
        if (latin && text.substring(start, minOf(text.length, start + MAX_LENGTH)).none(Japanese::isJapanese)) return null
        var end = start
        while (end < text.length && end - start < MAX_LENGTH && (TextVariants.scans(text[end]) || Fuseji.isFuseji(text[end]))) end++

        // Longest source first; within one, as written before its variants, deinflected after both.
        val candidates = mutableListOf<Candidate>()
        for (length in end - start downTo 1) {
            val source = text.substring(start, start + length)
            for (variant in TextVariants.of(source)) {
                Deinflector.deinflect(variant.text).mapTo(candidates) { Candidate(it.text, source, it.conditions, it.inflections, variant.text, variant.steps) }
            }
        }

        candidates += censored(text.substring(start, end))

        val terms = match(candidates, definitions) { longest -> mixed(text.substring(start, end), longest) }
        if (latin && terms.isEmpty()) return null

        val kanji = if (!Japanese.isKanji(text[start])) emptyList() else {
            val character = text[start].toString()
            val frequencies = store.findKanjiMeta(character).mapNotNull { m -> Yomitan.frequency(m, null)?.let { m.dictionary to it } }
            store.findKanji(character).map { it.copy(frequencies = frequencies) }
        }
        val length = terms.firstOrNull()?.source?.length ?: 1
        return LookupResult(start, start + length, terms, kanji)
    }

    /**
     * The results for [candidates], the best first: the entries of their forms that their
     * conjugation fits. An entry that's a form of another word ([Yomitan.FormOf]: 痛く of 痛い,
     * "adverbial") finds that word, its rules added to the inflections, as Yomitan's
     * "Use deinflections"; after all the others, as Yomitan has them.
     */
    private fun match(candidates: List<Candidate>, definitions: Boolean = true, more: ((longest: Int) -> List<Candidate>)? = null): List<TermGroup> {
        val options = options()
        val matched = LinkedHashMap<TermEntry, Candidate>()
        val viaForms = ArrayList<Candidate>()
        match(candidates, options, matched, viaForms)
        // More candidates, if the longest text these matched calls for them
        val extra = more?.invoke(matched.values.maxOfOrNull { it.source.length } ?: 0).orEmpty()
        if (extra.isNotEmpty()) match(extra, options, matched, viaForms)
        if (viaForms.isNotEmpty()) match(viaForms, options, matched, null)
        return results(matched, (candidates + extra + viaForms).withIndex().associate { (i, c) -> c to i }, definitions)
    }

    /**
     * Candidates for the words [text] starts with that have censored characters in it ([Fuseji]):
     * the dictionaries' keys it (or a dictionary form of it) can be with them as any character,
     * searched for by the text before them, or after them if they come first (only if suffix
     * search is ready). After the others of the same length ([Candidate.steps]).
     */
    private fun censored(text: String): List<Candidate> {
        if (text.none(Fuseji::isFuseji)) return emptyList()
        class Pattern(val source: String, val form: Deinflection, val variant: String)
        val patterns = ArrayList<Pattern>()
        for (length in text.length downTo 2) {
            val source = text.substring(0, length)
            for (variant in listOf(source, Japanese.toHiragana(source)).distinct()) {
                for (form in Deinflector.deinflect(variant)) if (Fuseji.isCensored(form.text)) patterns += Pattern(source, form, variant)
            }
        }
        val out = ArrayList<Candidate>()
        for ((affix, group) in patterns.groupBy { p ->
            val prefix = p.form.text.takeWhile { !Fuseji.isFuseji(it) }
            if (prefix.isNotEmpty()) prefix to false else p.form.text.takeLastWhile { !Fuseji.isFuseji(it) } to true
        }) {
            val keys = store.findKeys(affix.first, affix.second, MAX_KEYS) { key -> group.any { Fuseji.matches(it.form.text, key) } }
            for (p in group) for (key in keys) {
                if (Fuseji.matches(p.form.text, key)) out += Candidate(key, p.source, p.form.conditions, p.form.inflections, p.variant, CHANGED_SPELLING)
            }
        }
        return out
    }

    /**
     * Candidates for the words [text] starts with that it writes with some kanji in kana
     * ([Mazegaki]: 改ざん, ぼう大), if the [longest] text other candidates matched doesn't go past its
     * first run of kanji or of kana (two or more): the spellings starting with its kanji, or the
     * readings starting with its kana, whose entries it fits. After the others of the same length.
     */
    private fun mixed(text: String, longest: Int): List<Candidate> {
        if (text.isEmpty()) return emptyList()
        val kanaFirst = Mazegaki.isKana(text[0])
        val lead = text.takeWhile { Mazegaki.isKana(it) == kanaFirst && (kanaFirst || Japanese.isKanji(it)) }
        if (longest > lead.length || lead.length == text.length || kanaFirst && lead.length < 2) return emptyList()
        // A particle after a kanji is no kana for another (本を, 日に): the lookup found the word
        if (!kanaFirst && text[lead.length] in PARTICLES) return emptyList()
        class Form(val source: String, val form: Deinflection)
        val forms = ArrayList<Form>()
        for (length in text.length downTo lead.length + 1) {
            val source = text.substring(0, length)
            for (form in Deinflector.deinflect(source)) {
                val f = form.text
                if (f.startsWith(lead) && f.any(Japanese::isKanji) && f.any(Mazegaki::isKana)) forms += Form(source, form)
            }
        }
        if (forms.isEmpty()) return emptyList()
        // A spelling has a kanji for each of the text's kana it doesn't have, so it's shorter; a reading longer
        fun fits(key: String, form: String) = if (kanaFirst) {
            key.length > form.length && key.length <= form.length + MAX_READING * form.count(Japanese::isKanji)
        } else {
            key.length < form.length && key.any(Japanese::isKanji)
        }
        val keys = store.findKeys(if (kanaFirst) Japanese.toHiragana(lead) else lead, false, MAX_KEYS) { key -> forms.any { fits(key, it.form.text) } }
        return forms.flatMap { f ->
            keys.filter { fits(it, f.form.text) }.map { key ->
                Candidate(key, f.source, f.form.conditions, f.form.inflections, f.form.text, CHANGED_SPELLING, fits = { Mazegaki.matches(f.form.text, it.expression, it.reading) })
            }
        }
    }

    /**
     * Puts each entry of [candidates]' forms that the candidate's conjugation fits into [matched],
     * with the first (best) candidate that found it (any, if its dictionary takes any part of
     * speech). Entries that are forms of other words go in with their other definitions only, if
     * they have any; the words they're forms of go to [viaForms] as candidates (unless null).
     */
    private fun match(candidates: List<Candidate>, options: DictionaryOptions, matched: MutableMap<TermEntry, Candidate>, viaForms: MutableList<Candidate>?) {
        val byForm = HashMap<String, MutableList<TermEntry>>()
        val found = store.findTerms(candidates.mapTo(LinkedHashSet()) { it.form })
        val forms = forms(found)
        for (entry in found) {
            byForm.getOrPut(entry.expression) { mutableListOf() } += entry
            if (entry.reading != entry.expression) byForm.getOrPut(entry.reading) { mutableListOf() } += entry
        }
        for (candidate in candidates) {
            for (entry in byForm[candidate.form].orEmpty()) {
                if (candidate.fits?.invoke(entry) == false) continue
                if (entry.dictionary !in options.partsOfSpeechFilterOff && !Deinflector.accepts(candidate.conditions, entry.rules)) continue
                val split = forms[entry]
                if (split == null) {
                    matched.putIfAbsent(entry, candidate)
                    continue
                }
                split.rest?.let { matched.putIfAbsent(entry.copy(glossary = it), candidate) }
                if (viaForms == null || entry.dictionary in options.deinflectionsOff) continue
                for (form in split.forms) viaForms += Candidate(form.term, candidate.source, 0, candidate.inflections + form.rules, candidate.transformed, candidate.steps)
            }
        }
    }

    /**
     * Of [entries], those of dictionaries with forms of other words ([DictionaryStore.dictionariesWithForms]),
     * their glossaries read and split ([Yomitan.forms]).
     */
    private fun forms(entries: Collection<TermEntry>): Map<TermEntry, Yomitan.Forms> {
        val dictionaries = store.dictionariesWithForms()
        if (dictionaries.isEmpty()) return emptyMap()
        val some = entries.filter { it.dictionary in dictionaries }.distinct()
        if (some.isEmpty()) return emptyMap()
        return some.zip(store.withGlossaries(some)).associate { (entry, full) -> entry to Yomitan.forms(full.glossary) }
    }

    /**
     * [matched] without the forms of other words: an entry that's only such forms is left out,
     * one with other definitions too keeps those. (Lookups ([match]) have done this already.)
     */
    private fun withoutForms(matched: Map<TermEntry, Candidate>): Map<TermEntry, Candidate> {
        val forms = forms(matched.keys.filter { it.glossary.isEmpty() })
        if (forms.isEmpty()) return matched
        val out = LinkedHashMap<TermEntry, Candidate>()
        for ((entry, candidate) in matched) {
            val split = forms[entry]
            if (split == null) out[entry] = candidate else split.rest?.let { out.putIfAbsent(entry.copy(glossary = it), candidate) }
        }
        return out
    }

    /** A section of [lookUpLatin]'s result, and the words its definitions were searched for if they were */
    private class Section(val title: String, val terms: List<TermGroup>, val words: List<String> = emptyList())

    /** What [lookUpLatin]'s sections are called: the romaji read as kana, by meaning, a spelling meant instead */
    class SectionTitles(val japanese: (kana: String) -> String, val meaning: String, val didYouMean: (spelling: String) -> String)

    /**
     * A search of text in Latin letters, in sections as Akebi's: the word [kana] is all of, if it's
     * the text typed as romaji; the words of [query]'s meaning; and if neither finds anything, the
     * words of spellings one edit away ([correctMeaning]) and, if [correctRomaji], of romaji one
     * edit away ([romajiCorrections]), the romaji first if the text reads as kana. Failing all that,
     * the words [kana] starts with. The result spans [query].
     */
    fun lookUpLatin(query: String, kana: String?, correctRomaji: Boolean, titles: SectionTitles): LookupResult {
        val sections = ArrayList<Section>()
        var partial = emptyList<TermGroup>()
        var japanese: Section? = null
        if (kana != null) {
            val found = lookUp(kana, 0)?.terms.orEmpty()
            val whole = found.filter { it.source.length == kana.length }
            if (whole.isNotEmpty()) japanese = Section(titles.japanese(kana), whole.take(MAX_SECTION)) else partial = found
        }
        val meaning = lookUpMeaning(query).terms.takeIf { it.isNotEmpty() }?.let { Section(titles.meaning, it, Meanings.words(query)) }
        // Japanese first if it's a common word (taberu, neko), else what the meaning finds (house, not 法施)
        sections += listOfNotNull(japanese, meaning).sortedBy { if (it === japanese && common(it.terms)) 0 else if (it === meaning) 1 else 2 }
        if (sections.isEmpty()) {
            val byMeaning = correctMeaning(query)?.let { spelling ->
                lookUpMeaning(spelling).terms.takeIf { it.isNotEmpty() }?.let { Section(titles.didYouMean(spelling), it.take(MAX_SECTION), Meanings.words(spelling)) }
            }
            val byRomaji = if (!correctRomaji) null else lookUpForms(romajiCorrections(query), query).takeIf { it.isNotEmpty() }?.let {
                Section(titles.didYouMean(it.first().reading), it.take(MAX_SECTION))
            }
            // The same for what it might have been meant as: tbaemono as 食べ物, but hosue as house, not ホセ
            sections += listOfNotNull(byRomaji, byMeaning).sortedBy { if (it === byRomaji && (kana != null && common(it.terms) || byMeaning == null)) 0 else if (it === byMeaning) 1 else 2 }
        }
        if (sections.isEmpty() && partial.isNotEmpty()) sections += Section(titles.japanese(kana!!), partial)
        val terms = ArrayList<TermGroup>()
        val headings = ArrayList<ResultSection>()
        for (section in sections) {
            headings += ResultSection(terms.size, section.title, section.words)
            terms += section.terms
        }
        return LookupResult(0, query.length, terms, emptyList(), headings)
    }

    /**
     * Whether the first of [terms] is a common word, as the frequency dictionary results are
     * sorted by ranks it ([COMMON_RANK]); without one (or with occurrence counts) it counts as one.
     */
    private fun common(terms: List<TermGroup>): Boolean {
        val sort = frequencySort() ?: return true
        if (!sort.ascending) return true
        return frequencyOrder(terms.first(), sort) <= COMMON_RANK
    }

    /**
     * [query] with each of its words ([Meanings.words]) that no definition has spelled as the
     * most common spelling one edit away that some have (wter → water); null if none is changed.
     */
    fun correctMeaning(query: String): String? {
        val words = Meanings.words(query)
        if (words.isEmpty()) return null
        val known = store.meaningWordCounts(words)
        var changed = false
        val corrected = words.map { word ->
            if (word in known || word.length < SHORTEST_CORRECTED) word
            else store.meaningWordCounts(Spelling.edits(word)).maxByOrNull { it.value }?.key?.also { changed = true } ?: word
        }
        return if (changed) corrected.joinToString(" ") else null
    }

    /**
     * The kana of the romaji one edit away from [romaji] (tbaeru → たべる) that are a word, or
     * a conjugation of one, in a dictionary: hiragana, and katakana for words written in it.
     */
    fun romajiCorrections(romaji: String): List<String> {
        val word = romaji.lowercase()
        if (word.length < SHORTEST_CORRECTED || word.any { it !in 'a'..'z' }) return emptyList()
        val kana = Spelling.edits(word).map(Romaji::toKana).filter { k -> k.isNotEmpty() && k.none { it in 'a'..'z' } }
            .flatMap { listOf(it, Japanese.toKatakana(it)) }.distinct()
        val forms = kana.associateWith { k -> Deinflector.deinflect(k).map { it.text } }
        val existing = store.existingForms(forms.values.flatten().toSet())
        return kana.filter { k -> forms.getValue(k).any { it in existing } }
    }

    /** The words [forms] are, or conjugations of, sorted as other results; [source] is what was searched */
    private fun lookUpForms(forms: List<String>, source: String): List<TermGroup> {
        if (forms.isEmpty()) return emptyList()
        return match(forms.flatMap { form -> Deinflector.deinflect(form).map { Candidate(it.text, source, it.conditions, it.inflections) } })
    }

    /**
     * A wildcard search ([Wildcard]): the words starting with [affix], or ending with it if
     * [suffix], as written (not deinflected), sorted as other results. The result spans [affix].
     */
    fun lookUpWildcard(affix: String, suffix: Boolean): LookupResult {
        fun matches(form: String) = if (suffix) form.endsWith(affix) else form.startsWith(affix)
        val matched = LinkedHashMap<TermEntry, Candidate>()
        val candidates = LinkedHashMap<String, Candidate>()
        for (entry in store.findTermsByAffix(affix, suffix, MAX_WILDCARD_ENTRIES)) {
            val form = if (matches(entry.expression)) entry.expression else entry.reading
            matched[entry] = candidates.getOrPut(form) { Candidate(form, affix, 0, emptyList()) }
        }
        val terms = results(matched, candidates.values.withIndex().associate { (i, c) -> c to i })
        return LookupResult(0, affix.length, terms, emptyList())
    }

    /**
     * A search by meaning: the words whose definitions have the words of [query] (English,
     * German…), those whose definition is more nearly just those words first, then sorted as
     * other results. The result spans [query].
     */
    fun lookUpMeaning(query: String): LookupResult {
        val matched = LinkedHashMap<TermEntry, Candidate>()
        for ((entry, fit) in store.findTermsByMeaning(query, MAX_MEANING_ENTRIES)) {
            matched[entry] = Candidate(entry.expression, query, 0, emptyList(), fit = fit)
        }
        val rank = matched.values.sortedBy { it.fit }.withIndex().associate { (i, c) -> c to i }
        return LookupResult(0, query.length, results(matched, rank), emptyList())
    }

    /**
     * The results for the [matched] entries, each with the candidate that found it; [rank]: the
     * candidates' order, best first. [definitions]: with their glossaries.
     */
    private fun results(found: Map<TermEntry, Candidate>, rank: Map<Candidate, Int>, definitions: Boolean = true): List<TermGroup> {
        val matched = withoutForms(found)
        val groups = group(matched.keys, grouping())
        val expressions = groups.flatMapTo(HashSet()) { entries -> entries.map { it.expression } }
        val meta = if (expressions.isEmpty()) emptyList() else store.findTermMeta(expressions)
        val sort = frequencySort()
        val sorted = groups.mapNotNull { entries ->
            // The best candidate of the entries the text matched (related ones it didn't)
            val candidate = entries.mapNotNull { matched[it] }.minByOrNull { rank.getValue(it) } ?: return@mapNotNull null
            val headwords = entries.map { Headword(it.expression, it.reading, it in matched) }
                .groupBy { it.expression to it.reading }
                .map { (_, same) -> same.firstOrNull { it.primary } ?: same.first() }
                .sortedBy { !it.primary }
            val first = headwords.first()
            val names = headwords.mapTo(HashSet()) { it.expression }
            val term = TermGroup(
                expression = first.expression,
                reading = first.reading,
                source = candidate.source,
                inflections = candidate.inflections,
                entries = entries.sortedWith(compareBy<TermEntry> { it.priority }.thenByDescending { it.score }),
                meta = meta.filter { it.expression in names }.sortedBy { it.priority },
                deinflected = candidate.form,
                transformed = candidate.transformed,
                headwords = headwords,
            )
            // Found by meaning, any spelling is as good (食べる and 喰べる)
            val exact = candidate.fit > 0 || candidate.form == first.expression
            Sortable(term, candidate.steps, exact, frequencyOrder(term, sort), agreed(entries, matched, candidate.fit))
        }.sortedWith(ORDER)
        val shown = sorted.map { it.term }.take(MAX_RESULTS)
        if (!definitions) return shown
        val entries = shown.flatMapTo(LinkedHashSet()) { it.entries }.toList()
        // Related entries (grouped by sequence) can be forms of other words too: only their other definitions show.
        val withForms = store.dictionariesWithForms()
        val full = entries.zip(store.withGlossaries(entries)).associate { (entry, read) ->
            entry to (if (read.dictionary in withForms) Yomitan.forms(read.glossary).rest?.let { read.copy(glossary = it) } else read)
        }
        val kanji = HashMap<Char, List<KanjiEntry>>()
        fun kanjiOf(word: String) = word.filter(Japanese::isKanji).toSet().flatMap { c ->
            kanji.getOrPut(c) {
                val frequencies = store.findKanjiMeta(c.toString()).mapNotNull { m -> Yomitan.frequency(m, null)?.let { m.dictionary to it } }
                store.findKanji(c.toString()).map { it.copy(frequencies = frequencies) }
            }
        }
        return shown.mapNotNull { term ->
            val read = term.entries.mapNotNull { full[it] }
            if (read.isEmpty()) null else term.copy(entries = read, kanji = kanjiOf(term.expression))
        }
    }

    /**
     * How well a result found by meaning fits, [best] being its best fitting entry's fit: better
     * for every other dictionary that defines it about as well (食べる, "to eat" in three of them),
     * as much as a word less in its gloss each, up to [MOST_AGREEING]. 0 for other results.
     */
    private fun agreed(entries: List<TermEntry>, matched: Map<TermEntry, Candidate>, best: Int): Int {
        if (best <= 0) return 0
        val fit = lenient(best)
        val agreeing = entries.mapNotNull { e -> matched[e]?.takeIf { it.fit > 0 && lenient(it.fit) <= fit + AGREEING_FIT }?.let { e.dictionary } }.distinct().size
        return fit - minOf(agreeing - 1, MOST_AGREEING) * AGREEING_WORTH
    }

    /** A fit with a gloss of one word as good as one of two: "eat" as "to eat" */
    private fun lenient(fit: Int): Int = (fit and 0xff.inv()) or (maxOf((fit shr 4) and 0xf, 2) shl 4) or (fit and 0xf)

    /** The matched [entries] in groups, one per result, as Yomitan's result grouping mode groups them. */
    private fun group(entries: Collection<TermEntry>, grouping: Grouping): List<List<TermEntry>> = when (grouping.mode) {
        "split" -> entries.map { listOf(it) }
        "term" -> entries.groupBy { it.expression }.values.toList()
        "merge" -> related(entries, grouping)
        else -> entries.groupBy { it.expression to it.reading }.values.toList()
    }

    /**
     * Yomitan's "Group related terms" (translator.js, _getRelatedDictionaryEntries): the main
     * dictionary's entries grouped by sequence number, with its other entries of that sequence
     * (other spellings and readings); entries of other dictionaries join a group with the same
     * headword (term and reading), and the secondary dictionaries are searched for its headwords
     * too. What's left is grouped by term and reading.
     */
    private fun related(entries: Collection<TermEntry>, grouping: Grouping): List<List<TermEntry>> {
        val bySequence = LinkedHashMap<Long, MutableList<TermEntry>>()
        val ungrouped = LinkedHashSet<TermEntry>()
        for (entry in entries) {
            if (entry.dictionary == grouping.mainDictionary && entry.sequence >= 0) bySequence.getOrPut(entry.sequence) { mutableListOf() } += entry
            else ungrouped += entry
        }
        if (bySequence.isNotEmpty()) {
            for (entry in store.findTermsBySequence(grouping.mainDictionary, bySequence.keys)) {
                val group = bySequence[entry.sequence] ?: continue
                if (entry !in group) group += entry
                ungrouped -= entry
            }
            // The groups by headword (Yomitan compares readings as hiragana)
            fun key(entry: TermEntry) = entry.expression to Japanese.toHiragana(entry.reading)
            val targets = HashMap<Pair<String, String>, MutableList<MutableList<TermEntry>>>()
            for (group in bySequence.values) {
                for (entry in group.toList()) {
                    val list = targets.getOrPut(key(entry)) { mutableListOf() }
                    if (list.none { it === group }) list += group
                }
            }
            fun join(entry: TermEntry): Boolean {
                val groups = targets[key(entry)] ?: return false
                for (group in groups) if (entry !in group) group += entry
                return true
            }
            ungrouped.removeAll { join(it) }
            if (grouping.secondary.isNotEmpty()) {
                val terms = targets.keys.mapTo(HashSet()) { it.first }
                for (entry in store.findTerms(terms)) {
                    if (entry.dictionary in grouping.secondary && join(entry)) ungrouped -= entry
                }
            }
        }
        return bySequence.values.toList() + ungrouped.groupBy { it.expression to it.reading }.values
    }

    /** A result with what it's sorted by besides itself; [exact]: the headword is spelled as it was matched. */
    private class Sortable(val term: TermGroup, val steps: Int, val exact: Boolean, val frequencyOrder: Long, val fit: Int = 0)

    /**
     * The term's place by the sorting frequency dictionary, as Yomitan computes it: its lowest
     * value for ranks, its highest (negated) for occurrence counts; without a value, after all
     * others. 0 for every term when no dictionary is chosen.
     */
    private fun frequencyOrder(term: TermGroup, sort: FrequencySort?): Long {
        if (sort == null) return 0
        val values = term.meta.filter { it.dictionary == sort.dictionary }.mapNotNull { Yomitan.frequency(it, term.reading)?.value }
        return when {
            values.isEmpty() -> if (sort.ascending) Long.MAX_VALUE else 0
            sort.ascending -> values.min().toLong()
            else -> -values.max().toLong()
        }
    }

    private companion object {
        const val MAX_LENGTH = 20
        const val MAX_RESULTS = 20

        /** The entries a wildcard search sorts its results from, the shortest that match */
        const val MAX_WILDCARD_ENTRIES = 500

        /** The keys searched for a censored or mixed spelling's words, in each dictionary */
        const val MAX_KEYS = 200

        /** Particles and the copula, which after a kanji aren't a mixed spelling's kana (本を, 車だ, 変な) */
        const val PARTICLES = "をはがにでともへやかのだなねよ"

        /** How many kana a kanji of a mixed spelling may read, at most */
        const val MAX_READING = 5

        /** A censored or mixed spelling's steps: after the text as written and the usual variants of the same length */
        const val CHANGED_SPELLING = 4

        /** The entries a search by meaning sorts its results from, those fitting best */
        const val MAX_MEANING_ENTRIES = 300

        /** The results a section other than the search by meaning shows ([lookUpLatin]) */
        const val MAX_SECTION = 8

        /** Shorter words aren't corrected: too much is one edit away from them */
        const val SHORTEST_CORRECTED = 3

        /** Up to which frequency rank a word counts as common, for which way of reading Latin text comes first */
        const val COMMON_RANK = 10_000L

        /**
         * A dictionary agrees with a result found by meaning if its gloss has at most a word more
         * than the best ([AGREEING_FIT]); each agreeing is worth a word less ([AGREEING_WORTH]).
         */
        const val AGREEING_FIT = 1 shl 4
        const val AGREEING_WORTH = 1 shl 4
        const val MOST_AGREEING = 3

        /** Yomitan's order (translator.js, _sortTermDictionaryEntries). */
        val ORDER: Comparator<Sortable> = compareByDescending<Sortable> { it.term.source.length }
            .thenBy { it.steps }
            .thenBy { it.term.inflections.size }
            .thenByDescending { it.exact }
            .thenBy { it.fit }
            .thenBy { it.frequencyOrder }
            .thenBy { s -> s.term.entries.minOf { it.priority } }
            .thenByDescending { s -> s.term.entries.maxOf { it.score } }
            .thenByDescending { it.term.expression.length }
            .thenBy { it.term.expression }
            .thenByDescending { it.term.entries.size }
    }
}
