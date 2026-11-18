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
}

/** Sorting results by one frequency dictionary, as Yomitan's "Frequency sorting dictionary"; [ascending] for ranks. */
data class FrequencySort(val dictionary: String, val ascending: Boolean)

/**
 * Entries sharing a headword and reading, from one or more dictionaries. [source] is the scanned
 * text they matched; [inflections] the transforms from the headword to [source] (empty if it
 * matched as written); [deinflected] the form that was looked up (the headword, or its reading),
 * and [processed] whether [source] was read as hiragana for that.
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
)

/** [start]..[end] is the longest matched text. */
data class LookupResult(val start: Int, val end: Int, val terms: List<TermGroup>, val kanji: List<KanjiEntry>)

/**
 * Yomitan-style lookup: the longest dictionary word, as written or deinflected, starting at a
 * position. [frequencySort] gives the frequency dictionary to sort by, if any.
 */
class Translator(private val store: DictionaryStore, private val frequencySort: () -> FrequencySort? = { null }) {

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

        class Group(val candidate: Candidate, val entries: MutableList<TermEntry> = mutableListOf())
        val groups = LinkedHashMap<Pair<String, String>, Group>()
        for (candidate in candidates) {
            for (entry in byForm[candidate.form].orEmpty()) {
                if (!Deinflector.accepts(candidate.conditions, entry.rules)) continue
                val group = groups.getOrPut(entry.expression to entry.reading) { Group(candidate) }
                if (entry !in group.entries) group.entries += entry
            }
        }

        val meta = if (groups.isEmpty()) emptyList() else store.findTermMeta(groups.keys.mapTo(HashSet()) { it.first })
        val sort = frequencySort()
        val sorted = groups.map { (key, group) ->
            val (expression, reading) = key
            val term = TermGroup(
                expression = expression,
                reading = reading,
                source = group.candidate.source,
                inflections = group.candidate.inflections,
                entries = group.entries.sortedWith(compareBy<TermEntry> { it.priority }.thenByDescending { it.score }),
                meta = meta.filter { it.expression == expression }.sortedBy { it.priority },
                deinflected = group.candidate.form,
                processed = group.candidate.processed,
            )
            Sortable(term, group.candidate.processed, group.candidate.form == expression, frequencyOrder(term, sort))
        }.sortedWith(ORDER)
        val terms = sorted.map { it.term }.take(MAX_RESULTS)

        val kanji = if (!Japanese.isKanji(text[start])) emptyList() else {
            val character = text[start].toString()
            val frequencies = store.findKanjiMeta(character).mapNotNull { m -> Yomitan.frequency(m, null)?.let { m.dictionary to it } }
            store.findKanji(character).map { it.copy(frequencies = frequencies) }
        }
        val matched = terms.firstOrNull()?.source?.length ?: 1
        return LookupResult(start, start + matched, terms, kanji)
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
