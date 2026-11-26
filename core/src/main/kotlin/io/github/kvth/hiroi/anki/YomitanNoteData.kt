package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionaryStore
import io.github.kvth.hiroi.dictionary.Tags
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import io.github.kvth.hiroi.japanese.Deinflector
import org.json.JSONArray
import org.json.JSONObject

/**
 * A lookup as Yomitan's Anki templates see it: a term dictionary entry (types/ext/dictionary.d.ts)
 * and the note context (types/ext/anki-templates-internal.d.ts), built the way Yomitan's
 * translator builds them, so its AnkiNoteBuilder fills fields exactly as in Yomitan.
 */
class YomitanNoteData(
    private val store: DictionaryStore,
    private val dictionaries: List<DictionaryInfo>,
    /** The names dictionaries were given (Rename…) by title: Yomitan's dictionaryAlias, which {dictionary-alias} and the glossaries' labels show */
    private val aliases: Map<String, String> = emptyMap(),
) {
    private val index = dictionaries.withIndex().associate { (i, info) -> info.title to i }

    private fun alias(title: String) = aliases[title] ?: title
    private val modes = dictionaries.associate { it.title to it.frequencyMode }

    /** The dictionaries as hiroi-anki.js wants them: for {single-glossary-*} markers and glossary styles. */
    fun dictionariesJson(styles: Map<String, String>): JSONArray = JSONArray(dictionaries.map { info ->
        JSONObject()
            .put("name", info.title)
            .put("terms", info.terms)
            .put("frequencies", info.frequencies)
            .apply { styles[info.title]?.let { put("styles", it) } }
    })

    fun dictionaryEntry(term: TermGroup): JSONObject {
        // One per headword, as Yomitan's translator makes them: the text matched the primary ones,
        // the others are related terms (their source is themselves).
        val headwords = term.headwords.mapIndexed { i, headword ->
            val entries = term.entries.filter { term.headwordIndex(it) == i }
            val headwordTags = tags(entries.groupBy({ it.dictionary }, { it.termTags }).mapValues { (_, tags) -> tags.flatMap { it.split(' ') } })
            val source = if (headword.primary) {
                JSONObject()
                    .put("originalText", term.source)
                    .put("transformedText", term.transformed)
                    .put("deinflectedText", term.deinflected)
                    .put("matchType", "exact")
                    .put("matchSource", if (term.deinflected == headword.expression) "term" else "reading")
                    .put("isPrimary", true)
            } else {
                JSONObject()
                    .put("originalText", headword.expression)
                    .put("transformedText", headword.expression)
                    .put("deinflectedText", headword.expression)
                    .put("matchType", "exact")
                    .put("matchSource", "term")
                    .put("isPrimary", false)
            }
            JSONObject()
                .put("index", i)
                .put("term", headword.expression)
                .put("reading", headword.reading)
                .put("sources", JSONArray().put(source))
                .put("tags", JSONArray(headwordTags))
                .put("wordClasses", JSONArray(entries.flatMap { it.rules.split(' ') }.filter { it.isNotEmpty() }.distinct()))
        }
        // Their part-of-speech tags marked where they repeat the previous definition's, for "Compact tags"
        val definitionTags = Tags.flagRedundant(
            term.definitions.map { (entry, _) -> entry.dictionary to Tags.expand(store::findTags, entry.dictionary, entry.definitionTags.split(' ')) },
        )
        val definitions = term.definitions.mapIndexed { i, (entry, headwordIndices) ->
            JSONObject()
                .put("index", i)
                .put("headwordIndices", JSONArray(headwordIndices))
                .put("dictionary", entry.dictionary)
                .put("dictionaryIndex", index[entry.dictionary] ?: 0)
                .put("dictionaryAlias", alias(entry.dictionary))
                .put("id", i)
                .put("score", entry.score)
                .put("frequencyOrder", 0)
                .put("sequences", JSONArray().put(entry.sequence))
                .put("isPrimary", headwordIndices.any { term.headwords[it].primary })
                .put("tags", JSONArray(definitionTags[i].map(::json)))
                .put("entries", JSONArray(entry.glossary))
        }
        val inflections = JSONArray(
            term.inflections.map { name -> JSONObject().put("name", name).apply { Deinflector.description(name)?.let { put("description", it) } } },
        )
        return JSONObject()
            .put("type", "term")
            .put("isPrimary", true)
            .put("textProcessorRuleChainCandidates", JSONArray().put(JSONArray()))
            .put("inflectionRuleChainCandidates", JSONArray().put(JSONObject().put("source", "algorithm").put("inflectionRules", inflections)))
            .put("score", term.entries.maxOfOrNull { it.score } ?: 0)
            .put("frequencyOrder", 0)
            .put("dictionaryIndex", term.entries.minOfOrNull { index[it.dictionary] ?: 0 } ?: 0)
            .put("dictionaryAlias", term.entries.firstOrNull()?.dictionary?.let(::alias).orEmpty())
            .put("sourceTermExactMatchCount", term.headwords.count { it.primary && term.deinflected == it.expression })
            .put("matchPrimaryReading", false)
            .put("maxOriginalTextLength", term.source.length)
            .put("headwords", JSONArray(headwords))
            .put("definitions", JSONArray(definitions))
            .put("pronunciations", JSONArray(pronunciations(term)))
            .put("frequencies", JSONArray(frequencies(term)))
    }

    /** Tags as Yomitan expands them ([Tags.expand]) */
    private fun tags(dictionary: String, names: List<String>): List<JSONObject> = tags(mapOf(dictionary to names))

    private fun tags(names: Map<String, List<String>>): List<JSONObject> = Tags.expand(store::findTags, names).map(::json)

    private fun json(tag: Tags.Tag) = JSONObject()
        .put("name", tag.name)
        .put("category", tag.category)
        .put("order", tag.order)
        .put("score", tag.score)
        .put("content", JSONArray(tag.notes))
        .put("dictionaries", JSONArray(tag.dictionaries))
        .put("redundant", tag.redundant)

    private fun base(meta: TermMeta, i: Int, headwordIndex: Int) = JSONObject()
        .put("index", i)
        .put("headwordIndex", headwordIndex)
        .put("dictionary", meta.dictionary)
        .put("dictionaryIndex", index[meta.dictionary] ?: 0)
        .put("dictionaryAlias", alias(meta.dictionary))

    /** The meta rows for each headword (its term), with the headword's index and reading. */
    private fun perHeadword(term: TermGroup, modes: Set<String>): List<Triple<TermMeta, Int, String>> = term.headwords.withIndex().flatMap { (i, headword) ->
        term.meta.filter { it.mode in modes && it.expression == headword.expression }.map { Triple(it, i, headword.reading) }
    }

    /** Pitch accents and IPA for each headword's reading, one group per meta row. */
    private fun pronunciations(term: TermGroup): List<JSONObject> = perHeadword(term, setOf("pitch", "ipa"))
        .mapNotNull { (meta, headwordIndex, reading) ->
            val data = JSONArray(meta.data).optJSONObject(0) ?: return@mapNotNull null
            if (data.optString("reading", reading) != reading) return@mapNotNull null
            val list = if (meta.mode == "pitch") {
                val pitches = data.optJSONArray("pitches") ?: return@mapNotNull null
                List(pitches.length()) { i ->
                    val pitch = pitches.getJSONObject(i)
                    JSONObject()
                        .put("type", "pitch-accent")
                        .put("positions", pitch.opt("position"))
                        .put("nasalPositions", positions(pitch.opt("nasal")))
                        .put("devoicePositions", positions(pitch.opt("devoice")))
                        .put("tags", JSONArray(tags(meta.dictionary, strings(pitch.opt("tags")))))
                }
            } else {
                val transcriptions = data.optJSONArray("transcriptions") ?: return@mapNotNull null
                List(transcriptions.length()) { i ->
                    val ipa = transcriptions.getJSONObject(i)
                    JSONObject()
                        .put("type", "phonetic-transcription")
                        .put("ipa", ipa.optString("ipa"))
                        .put("tags", JSONArray(tags(meta.dictionary, strings(ipa.opt("tags")))))
                }
            }
            Triple(meta, headwordIndex, list)
        }
        .mapIndexed { i, (meta, headwordIndex, list) -> base(meta, i, headwordIndex).put("pronunciations", JSONArray(list)) }

    /**
     * Frequencies as Yomitan reads them (translator.js, _getFrequencyInfo): a number as is; a text
     * as its first number, with the text shown; {value, displayValue}; any of these under
     * {reading, frequency}, which only counts for that reading.
     */
    private fun frequencies(term: TermGroup): List<JSONObject> {
        class Frequency(val meta: TermMeta, val headwordIndex: Int, val value: Double, val display: String?, val parsed: Boolean, val hasReading: Boolean)
        val list = perHeadword(term, setOf("freq")).mapNotNull { (meta, headwordIndex, reading) ->
            var data = JSONArray(meta.data).get(0)
            var hasReading = false
            if (data is JSONObject && data.has("frequency")) {
                if (data.optString("reading", reading) != reading) return@mapNotNull null
                hasReading = data.has("reading")
                data = data.get("frequency")
            }
            when (data) {
                is Number -> Frequency(meta, headwordIndex, data.toDouble(), null, false, hasReading)
                is String -> Frequency(meta, headwordIndex, FIRST_NUMBER.find(data)?.value?.toDoubleOrNull() ?: 0.0, data, true, hasReading)
                is JSONObject -> Frequency(meta, headwordIndex, data.optDouble("value", 0.0), data.optString("displayValue").takeIf { data.has("displayValue") }, false, hasReading)
                else -> null
            }
        }
        return list.mapIndexed { i, f ->
            base(f.meta, i, f.headwordIndex)
                .put("hasReading", f.hasReading)
                .put("frequencyMode", modes[f.meta.dictionary] ?: JSONObject.NULL)
                .put("frequency", if (f.value % 1.0 == 0.0) f.value.toLong() else f.value)
                .put("displayValue", f.display ?: JSONObject.NULL)
                .put("displayValueParsed", f.parsed)
        }
    }

    private fun positions(value: Any?): JSONArray = when (value) {
        is Number -> JSONArray().put(value)
        is JSONArray -> value
        else -> JSONArray()
    }

    private fun strings(value: Any?): List<String> = (value as? JSONArray)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()

    companion object {
        private val FIRST_NUMBER = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

        /**
         * Yomitan's note context: where the word was found. [CardData.text] is all the text it
         * was found in; the sentence is the part around the word, as Yomitan cuts it out.
         */
        fun context(data: CardData): JSONObject {
            val (start, end) = sentence(data.text, data.start, data.end)
            return JSONObject()
                .put("url", data.url)
                .put("documentTitle", data.documentTitle)
                .put("query", data.text.substring(data.start))
                .put("fullQuery", data.text)
                .put("sentence", JSONObject().put("text", data.text.substring(start, end)).put("offset", data.start - start))
        }

        /** The sentence around [wordStart]..[wordEnd]: from after the previous 。！？ or line break up to and including the next. */
        fun sentence(text: String, wordStart: Int, wordEnd: Int): Pair<Int, Int> {
            val enders = "。！？!?\n"
            var start = wordStart
            while (start > 0 && text[start - 1] !in enders) start--
            var end = wordEnd
            while (end < text.length && text[end] !in enders) end++
            if (end < text.length && text[end] != '\n') end++
            while (start < wordStart && text[start].isWhitespace()) start++
            return start to end
        }

        /** Yomitan's parsed text (api.parseText's scanning-parser content): terms of {text, reading} segments. */
        fun parsedText(terms: List<List<Furigana.Segment>>): JSONArray = JSONArray(terms.map { segments ->
            JSONArray(segments.map { JSONObject().put("text", it.text).put("reading", it.reading.orEmpty()) })
        })
    }
}
