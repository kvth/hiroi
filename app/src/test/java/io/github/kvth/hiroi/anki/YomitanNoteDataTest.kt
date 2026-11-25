package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionaryStore
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.TagInfo
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YomitanNoteDataTest {
    private val store = object : DictionaryStore {
        override fun findTerms(forms: Collection<String>) = emptyList<TermEntry>()
        override fun findTermMeta(expressions: Collection<String>) = emptyList<TermMeta>()
        override fun findKanji(character: String) = emptyList<KanjiEntry>()
        override fun findTags(dictionary: String, names: Collection<String>) = listOf(
            TagInfo("v1", "partOfSpeech", 0, "Ichidan verb", 0, "Jitendex"),
            TagInfo("★", "popular", -10, "high priority entry", 0, "Jitendex"),
        ).filter { it.name in names && dictionary == "Jitendex" }
    }

    private val dictionaries = listOf(
        DictionaryInfo(1, "Jitendex", "", 100, 0, 0),
        DictionaryInfo(2, "JPDB", "", 0, 50, 0, frequencies = 50, frequencyMode = "rank-based"),
        DictionaryInfo(3, "NHK", "", 0, 50, 0),
    )

    private val term = TermGroup(
        expression = "食べる", reading = "たべる", source = "食べなかった", inflections = listOf("negative", "-た"),
        entries = listOf(
            TermEntry("食べる", "たべる", "v1 ★", "v1", 5, """["to eat",{"type":"structured-content","content":"to live on"}]""", 1358280, "★", "Jitendex", 1),
        ),
        meta = listOf(
            TermMeta("食べる", "freq", """[{"reading":"たべる","frequency":{"value":480,"displayValue":"480㋕"}}]""", "JPDB", 2),
            TermMeta("食べる", "freq", """[{"reading":"くう","frequency":1}]""", "JPDB", 2),
            TermMeta("食べる", "freq", "[\"~1200\"]", "JPDB", 2),
            TermMeta("食べる", "pitch", """[{"reading":"たべる","pitches":[{"position":2,"devoice":[1]}]}]""", "NHK", 3),
        ),
        deinflected = "食べる",
    )

    private val entry = YomitanNoteData(store, dictionaries).dictionaryEntry(term)

    @Test
    fun givesDictionariesTheNamesTheyWereGiven() {
        val named = YomitanNoteData(store, dictionaries, mapOf("Jitendex" to "JTX", "JPDB" to "Frequency")).dictionaryEntry(term)
        assertEquals("JTX", named.getString("dictionaryAlias"))
        val definition = named.getJSONArray("definitions").getJSONObject(0)
        assertEquals(listOf("Jitendex", "JTX"), listOf(definition.getString("dictionary"), definition.getString("dictionaryAlias")))
        val frequency = named.getJSONArray("frequencies").getJSONObject(0)
        assertEquals(listOf("JPDB", "Frequency"), listOf(frequency.getString("dictionary"), frequency.getString("dictionaryAlias")))
        // Without one, a dictionary's alias is its title, as in Yomitan
        assertEquals("Jitendex", entry.getString("dictionaryAlias"))
    }

    @Test
    fun describesTheTermAsYomitansTranslatorDoes() {
        assertEquals("term", entry.getString("type"))
        assertEquals(6, entry.getInt("maxOriginalTextLength"))
        assertEquals(1, entry.getInt("sourceTermExactMatchCount"))
        val headword = entry.getJSONArray("headwords").getJSONObject(0)
        assertEquals("食べる", headword.getString("term"))
        assertEquals("たべる", headword.getString("reading"))
        val source = headword.getJSONArray("sources").getJSONObject(0)
        assertEquals("食べなかった", source.getString("originalText"))
        assertEquals("食べる", source.getString("deinflectedText"))
        assertEquals("term", source.getString("matchSource"))
        assertEquals(listOf("v1"), headword.getJSONArray("wordClasses").toList())
        val rules = entry.getJSONArray("inflectionRuleChainCandidates").getJSONObject(0).getJSONArray("inflectionRules")
        assertEquals(listOf("negative", "-た"), List(rules.length()) { rules.getJSONObject(it).getString("name") })
    }

    @Test
    fun expandsTagsFromTheTagBankSortedByOrder() {
        val tags = entry.getJSONArray("definitions").getJSONObject(0).getJSONArray("tags")
        assertEquals(listOf("★", "v1"), List(tags.length()) { tags.getJSONObject(it).getString("name") })
        assertEquals("popular", tags.getJSONObject(0).getString("category"))
        assertEquals(listOf("high priority entry"), tags.getJSONObject(0).getJSONArray("content").toList())
        val definition = entry.getJSONArray("definitions").getJSONObject(0)
        assertEquals(2, definition.getJSONArray("entries").length())
        assertEquals(0, definition.getInt("dictionaryIndex"))
    }

    @Test
    fun readsFrequenciesLikeYomitan() {
        val frequencies = entry.getJSONArray("frequencies")
        // The one for くう is left out
        assertEquals(2, frequencies.length())
        val byReading = frequencies.getJSONObject(0)
        assertEquals(480, byReading.getInt("frequency"))
        assertEquals("480㋕", byReading.getString("displayValue"))
        assertTrue(byReading.getBoolean("hasReading"))
        assertEquals("rank-based", byReading.getString("frequencyMode"))
        assertEquals(1, byReading.getInt("dictionaryIndex"))
        val text = frequencies.getJSONObject(1)
        assertEquals(1200, text.getInt("frequency"))
        assertTrue(text.getBoolean("displayValueParsed"))
        assertFalse(text.getBoolean("hasReading"))
    }

    @Test
    fun readsPitchAccents() {
        val pitch = entry.getJSONArray("pronunciations").getJSONObject(0).getJSONArray("pronunciations").getJSONObject(0)
        assertEquals("pitch-accent", pitch.getString("type"))
        assertEquals(2, pitch.getInt("positions"))
        assertEquals(listOf(1), pitch.getJSONArray("devoicePositions").toList())
    }

    @Test
    fun cutsOutTheSentenceForTheContext() {
        val context: JSONObject = YomitanNoteData.context(CardData(term, "昨日、パンを食べなかった。今日は食べる。", 6, 12, "Kindle"))
        val sentence = context.getJSONObject("sentence")
        assertEquals("昨日、パンを食べなかった。", sentence.getString("text"))
        assertEquals(6, sentence.getInt("offset"))
        assertEquals("Kindle", context.getString("documentTitle"))
        assertEquals("昨日、パンを食べなかった。今日は食べる。", context.getString("fullQuery"))
    }

    @Test
    fun namesDictionaryMarkersAsYomitanDoes() {
        assertEquals(listOf("single-glossary-jitendex", "single-frequency-number-jpdb"), AnkiTemplates.dynamicMarkers(dictionaries))
        assertEquals("jmdict-english-2026-11", AnkiTemplates.kebabCase("JMdict (English) 2026_11"))
        assertEquals(listOf("expression", "mine"), AnkiTemplates.inlineMarkers("{{#*inline \"expression\"}}a{{/inline}}\n{{~#*inline \"mine\"}}b{{/inline}}"))
    }
}
