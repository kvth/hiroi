package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionaryStore
import io.github.kvth.hiroi.dictionary.TagInfo
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Japanese
import org.json.JSONArray
import org.json.JSONObject

/**
 * A lookup as Yomitan's Anki templates see it: a term dictionary entry (types/ext/dictionary.d.ts)
 * and the note context (types/ext/anki-templates-internal.d.ts), built the way Yomitan's
 * translator builds them, so its AnkiNoteBuilder fills fields exactly as in Yomitan.
 */
class YomitanNoteData(private val store: DictionaryStore, private val dictionaries: List<DictionaryInfo>) {
    private val index = dictionaries.withIndex().associate { (i, info) -> info.title to i }
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
        val headwordTags = term.entries.groupBy { it.dictionary }.flatMap { (dictionary, entries) ->
            tags(dictionary, entries.flatMap { it.termTags.split(' ') })
        }
        val source = JSONObject()
            .put("originalText", term.source)
            .put("transformedText", if (term.processed) Japanese.toHiragana(term.source) else term.source)
            .put("deinflectedText", term.deinflected)
            .put("matchType", "exact")
            .put("matchSource", if (term.deinflected == term.expression) "term" else "reading")
            .put("isPrimary", true)
        val headword = JSONObject()
            .put("index", 0)
            .put("term", term.expression)
            .put("reading", term.reading)
            .put("sources", JSONArray().put(source))
            .put("tags", JSONArray(headwordTags))
            .put("wordClasses", JSONArray(term.entries.flatMap { it.rules.split(' ') }.filter { it.isNotEmpty() }.distinct()))
        val definitions = term.entries.mapIndexed { i, entry ->
            JSONObject()
                .put("index", i)
                .put("headwordIndices", JSONArray().put(0))
                .put("dictionary", entry.dictionary)
                .put("dictionaryIndex", index[entry.dictionary] ?: 0)
                .put("dictionaryAlias", entry.dictionary)
                .put("id", i)
                .put("score", entry.score)
                .put("frequencyOrder", 0)
                .put("sequences", JSONArray().put(entry.sequence))
                .put("isPrimary", true)
                .put("tags", JSONArray(tags(entry.dictionary, entry.definitionTags.split(' '))))
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
            .put("dictionaryAlias", term.entries.firstOrNull()?.dictionary.orEmpty())
            .put("sourceTermExactMatchCount", if (term.deinflected == term.expression) 1 else 0)
            .put("matchPrimaryReading", false)
            .put("maxOriginalTextLength", term.source.length)
            .put("headwords", JSONArray().put(headword))
            .put("definitions", JSONArray(definitions))
            .put("pronunciations", JSONArray(pronunciations(term)))
            .put("frequencies", JSONArray(frequencies(term)))
    }

    /** Tags as Yomitan expands them: what the tag bank says, sorted by order then name; unknown ones in "default". */
    private fun tags(dictionary: String, names: List<String>): List<JSONObject> {
        val wanted = names.filter { it.isNotBlank() }.distinct()
        val known = store.findTags(dictionary, wanted).associateBy { it.name }
        return wanted.map { known[it] ?: TagInfo(it, "default", 0, "", 0, dictionary) }
            .sortedWith(compareBy<TagInfo> { it.order }.thenBy { it.name })
            .map { tag ->
                JSONObject()
                    .put("name", tag.name)
                    .put("category", tag.category.ifEmpty { "default" })
                    .put("order", tag.order)
                    .put("score", tag.score)
                    .put("content", JSONArray(listOfNotNull(tag.notes.takeIf { it.isNotEmpty() })))
                    .put("dictionaries", JSONArray().put(dictionary))
                    .put("redundant", false)
            }
    }

    private fun base(meta: TermMeta, i: Int) = JSONObject()
        .put("index", i)
        .put("headwordIndex", 0)
        .put("dictionary", meta.dictionary)
        .put("dictionaryIndex", index[meta.dictionary] ?: 0)
        .put("dictionaryAlias", meta.dictionary)

    /** Pitch accents and IPA for the headword's reading, one group per meta row. */
    private fun pronunciations(term: TermGroup): List<JSONObject> = term.meta.filter { it.mode == "pitch" || it.mode == "ipa" }
        .mapNotNull { meta ->
            val data = JSONArray(meta.data).optJSONObject(0) ?: return@mapNotNull null
            if (data.optString("reading", term.reading) != term.reading) return@mapNotNull null
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
            meta to list
        }
        .mapIndexed { i, (meta, list) -> base(meta, i).put("pronunciations", JSONArray(list)) }

    /**
     * Frequencies as Yomitan reads them (translator.js, _getFrequencyInfo): a number as is; a text
     * as its first number, with the text shown; {value, displayValue}; any of these under
     * {reading, frequency}, which only counts for that reading.
     */
    private fun frequencies(term: TermGroup): List<JSONObject> {
        class Frequency(val meta: TermMeta, val value: Double, val display: String?, val parsed: Boolean, val hasReading: Boolean)
        val list = term.meta.filter { it.mode == "freq" }.mapNotNull { meta ->
            var data = JSONArray(meta.data).get(0)
            var hasReading = false
            if (data is JSONObject && data.has("frequency")) {
                if (data.optString("reading", term.reading) != term.reading) return@mapNotNull null
                hasReading = data.has("reading")
                data = data.get("frequency")
            }
            when (data) {
                is Number -> Frequency(meta, data.toDouble(), null, false, hasReading)
                is String -> Frequency(meta, FIRST_NUMBER.find(data)?.value?.toDoubleOrNull() ?: 0.0, data, true, hasReading)
                is JSONObject -> Frequency(meta, data.optDouble("value", 0.0), data.optString("displayValue").takeIf { data.has("displayValue") }, false, hasReading)
                else -> null
            }
        }
        return list.mapIndexed { i, f ->
            base(f.meta, i)
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
                .put("url", "")
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
