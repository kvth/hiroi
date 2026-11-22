package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Japanese

interface DictionaryStore {
    /** Entries whose expression or reading is one of [forms]. */
    fun findTerms(forms: Collection<String>): List<TermEntry>

    fun findTermMeta(expressions: Collection<String>): List<TermMeta>

    fun findKanji(character: String): List<KanjiEntry>

    /** Kanji meta bank rows (frequencies) for [character]; [TermMeta.expression] is the character. */
    fun findKanjiMeta(character: String): List<TermMeta> = emptyList()

    /** Image [path] of [dictionary], if the dictionary came with it. */
    fun findMedia(dictionary: String, path: String): DictionaryMedia? = null

    /** What the tags [names] of [dictionary] mean, from its tag bank; unknown tags are left out. */
    fun findTags(dictionary: String, names: Collection<String>): List<TagInfo> = emptyList()

    /** [dictionary]'s entries with one of [sequences] (its related terms, for "Group related terms"). */
    fun findTermsBySequence(dictionary: String, sequences: Collection<Long>): List<TermEntry> = emptyList()
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
 * [deinflected] the form that was looked up (the headword, or its reading), and [processed]
 * whether [source] was read as hiragana for that.
 */
data class TermGroup(
    val expression: String,
    val reading: String,
    val source: String,
    val inflections: List<String>,
    val entries: List<TermEntry>,
    val meta: List<TermMeta>,
    val deinflected: String = expression,
    val processed: Boolean = false,
    val headwords: List<Headword> = listOf(Headword(expression, reading)),
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
data class LookupResult(val start: Int, val end: Int, val terms: List<TermGroup>, val kanji: List<KanjiEntry>)

/**
 * Yomitan-style lookup: the longest dictionary word, as written or deinflected, starting at a
 * position. [frequencySort] gives the frequency dictionary to sort by, if any.
 */
class Translator(
    private val store: DictionaryStore,
    private val frequencySort: () -> FrequencySort? = { null },
    private val grouping: () -> Grouping = { Grouping() },
    /** Dictionaries with Yomitan's "Part of speech filtering" off */
    private val partsOfSpeechFilterOff: () -> Set<String> = { emptySet() },
) {

    /** [processed]: read as hiragana rather than as written (Yomitan's text processing). */
    private class Candidate(val form: String, val source: String, val conditions: Int, val inflections: List<String>, val processed: Boolean)

    fun lookUp(text: String, start: Int): LookupResult? {
        if (start !in text.indices || !Japanese.isJapanese(text[start])) return null
        var end = start
        while (end < text.length && end - start < MAX_LENGTH && Japanese.isJapanese(text[end])) end++

        // Longest source first; within one, as written before deinflected, fewer steps first.
        val candidates = mutableListOf<Candidate>()
        for (length in end - start downTo 1) {
            val source = text.substring(start, start + length)
            for (variant in listOf(source, Japanese.toHiragana(source)).distinct()) {
                Deinflector.deinflect(variant).mapTo(candidates) { Candidate(it.text, source, it.conditions, it.inflections, variant != source) }
            }
        }

        val byForm = HashMap<String, MutableList<TermEntry>>()
        for (entry in store.findTerms(candidates.mapTo(LinkedHashSet()) { it.form })) {
            byForm.getOrPut(entry.expression) { mutableListOf() } += entry
            if (entry.reading != entry.expression) byForm.getOrPut(entry.reading) { mutableListOf() } += entry
        }

        // Each entry with the first (best) candidate that found it
        // (unless its dictionary takes any part of speech)
        val anyPartOfSpeech = partsOfSpeechFilterOff()
        val matched = LinkedHashMap<TermEntry, Candidate>()
        for (candidate in candidates) {
            for (entry in byForm[candidate.form].orEmpty()) {
                if (entry.dictionary in anyPartOfSpeech || Deinflector.accepts(candidate.conditions, entry.rules)) matched.putIfAbsent(entry, candidate)
            }
        }
        val rank = candidates.withIndex().associate { (i, c) -> c to i }

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
                processed = candidate.processed,
                headwords = headwords,
            )
            Sortable(term, candidate.processed, candidate.form == first.expression, frequencyOrder(term, sort))
        }.sortedWith(ORDER)
        val terms = sorted.map { it.term }.take(MAX_RESULTS)

        val kanji = if (!Japanese.isKanji(text[start])) emptyList() else {
            val character = text[start].toString()
            val frequencies = store.findKanjiMeta(character).mapNotNull { m -> Yomitan.frequency(m, null)?.let { m.dictionary to it } }
            store.findKanji(character).map { it.copy(frequencies = frequencies) }
        }
        val length = terms.firstOrNull()?.source?.length ?: 1
        return LookupResult(start, start + length, terms, kanji)
    }

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
    private class Sortable(val term: TermGroup, val processed: Boolean, val exact: Boolean, val frequencyOrder: Long)

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

        /** Yomitan's order (translator.js, _sortTermDictionaryEntries). */
        val ORDER: Comparator<Sortable> = compareByDescending<Sortable> { it.term.source.length }
            .thenBy { it.processed }
            .thenBy { it.term.inflections.size }
            .thenByDescending { it.exact }
            .thenBy { it.frequencyOrder }
            .thenBy { s -> s.term.entries.minOf { it.priority } }
            .thenByDescending { s -> s.term.entries.maxOf { it.score } }
            .thenByDescending { it.term.expression.length }
            .thenBy { it.term.expression }
            .thenByDescending { it.term.entries.size }
    }
}
