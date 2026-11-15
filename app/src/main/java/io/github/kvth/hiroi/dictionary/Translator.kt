package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Japanese

interface DictionaryStore {
    /** Entries whose expression or reading is one of [forms]. */
    fun findTerms(forms: Collection<String>): List<TermEntry>

    fun findTermMeta(expressions: Collection<String>): List<TermMeta>

    fun findKanji(character: String): List<KanjiEntry>
}

/**
 * Entries sharing a headword and reading, from one or more dictionaries. [source] is the scanned
 * text they matched; [inflections] the transforms from the headword to [source] (empty if it matched as written).
 */
data class TermGroup(
    val expression: String,
    val reading: String,
    val source: String,
    val inflections: List<String>,
    val entries: List<TermEntry>,
    val meta: List<TermMeta>,
)

/** [start]..[end] is the longest matched text. */
data class LookupResult(val start: Int, val end: Int, val terms: List<TermGroup>, val kanji: List<KanjiEntry>)

/** Yomitan-style lookup: the longest dictionary word, as written or deinflected, starting at a position. */
class Translator(private val store: DictionaryStore) {

    private class Candidate(val form: String, val source: String, val conditions: Int, val inflections: List<String>)

    fun lookUp(text: String, start: Int): LookupResult? {
        if (start !in text.indices || !Japanese.isJapanese(text[start])) return null
        var end = start
        while (end < text.length && end - start < MAX_LENGTH && Japanese.isJapanese(text[end])) end++

        // Longest source first; within one, as written before deinflected, fewer steps first.
        val candidates = mutableListOf<Candidate>()
        for (length in end - start downTo 1) {
            val source = text.substring(start, start + length)
            for (variant in listOf(source, Japanese.toHiragana(source)).distinct()) {
                Deinflector.deinflect(variant).mapTo(candidates) { Candidate(it.text, source, it.conditions, it.inflections) }
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
        val terms = groups.map { (key, group) ->
            val (expression, reading) = key
            TermGroup(
                expression = expression,
                reading = reading,
                source = group.candidate.source,
                inflections = group.candidate.inflections,
                entries = group.entries.sortedWith(compareBy<TermEntry> { it.priority }.thenByDescending { it.score }),
                meta = meta.filter { it.expression == expression }.sortedBy { it.priority },
            )
        }.sortedWith(
            compareByDescending<TermGroup> { it.source.length }
                .thenBy { it.inflections.size }
                .thenBy { group -> group.meta.mapNotNull { Yomitan.frequency(it, group.reading)?.value }.minOrNull() ?: Int.MAX_VALUE }
                .thenByDescending { group -> group.entries.maxOf { it.score } },
        ).take(MAX_RESULTS)

        val kanji = if (Japanese.isKanji(text[start])) store.findKanji(text[start].toString()) else emptyList()
        val matched = terms.firstOrNull()?.source?.length ?: 1
        return LookupResult(start, start + matched, terms, kanji)
    }

    private companion object {
        const val MAX_LENGTH = 20
        const val MAX_RESULTS = 20
    }
}
