package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    private fun term(expression: String, reading: String, rules: String, score: Int = 0) =
        TermEntry(expression, reading, "", rules, score, """["$expression"]""", 0, "", "Test", 1)

    private val store = object : DictionaryStore {
        val terms = listOf(
            term("食べる", "たべる", "v1"),
            term("食べ物", "たべもの", ""),
            term("書く", "かく", "v5"),
            term("書", "しょ", ""),
            term("勉強", "べんきょう", "vs"),
            term("猫", "ねこ", ""),
            term("ネコ", "ねこ", ""),
        )
        val meta = listOf(TermMeta("食べる", "freq", "[300]", "Freq", 2))
        val kanji = listOf(KanjiEntry("書", "ショ", "か.く", "", listOf("write"), "Kanji"))

        override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
        override fun findTermMeta(expressions: Collection<String>) = meta.filter { it.expression in expressions }
        override fun findKanji(character: String) = kanji.filter { it.character == character }
    }
    private val translator = Translator(store)

    @Test
    fun findsTheDictionaryFormOfAConjugatedVerb() {
        val result = translator.lookUp("ケーキを食べさせられた。", 4)!!
        val first = result.terms.first()
        assertEquals("食べる", first.expression)
        assertEquals("食べさせられた", first.source)
        assertEquals(listOf("causative", "potential or passive", "-た"), first.inflections)
        assertEquals(4 to 11, result.start to result.end)
        assertEquals("300", Yomitan.frequency(first.meta.single(), first.reading)?.display)
    }

    /** Two readings of 日 and a kanji frequency, with a rank list (Ranks) and a count list (Counts). */
    private val sortStore = object : DictionaryStore {
        val terms = listOf(term("日", "ひ", "", score = 5), term("日", "にち", ""), term("日", "か", ""))
        val meta = listOf(
            TermMeta("日", "freq", """[{"reading":"にち","frequency":100}]""", "Ranks", 2),
            TermMeta("日", "freq", """[{"reading":"ひ","frequency":900}]""", "Ranks", 2),
            TermMeta("日", "freq", """[{"reading":"か","frequency":50}]""", "Counts", 3),
            TermMeta("日", "freq", """[{"reading":"ひ","frequency":7000}]""", "Counts", 3),
        )

        override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
        override fun findTermMeta(expressions: Collection<String>) = meta.filter { it.expression in expressions }
        override fun findKanji(character: String) = listOf(KanjiEntry("日", "ニチ", "ひ", "", listOf("day"), "Kanji"))
        override fun findKanjiMeta(character: String) = listOf(TermMeta("日", "freq", "[12]", "Ranks", 2))
    }

    private fun readings(sort: FrequencySort?) = Translator(sortStore, { sort }).lookUp("日", 0)!!.terms.map { it.reading }

    @Test
    fun sortsByTheChosenFrequencyDictionaryLikeYomitan() {
        // Lowest rank first; without a value, last.
        assertEquals(listOf("にち", "ひ", "か"), readings(FrequencySort("Ranks", ascending = true)))
        // Highest count first; without a value, after those with one.
        assertEquals(listOf("ひ", "か", "にち"), readings(FrequencySort("Counts", ascending = false)))
        // Without a frequency dictionary: score, then as found.
        assertEquals("ひ", readings(null).first())
    }

    @Test
    fun givesKanjiTheirFrequencies() {
        val kanji = Translator(sortStore).lookUp("日", 0)!!.kanji.single()
        assertEquals(listOf("Ranks" to Yomitan.Frequency(12, "12")), kanji.frequencies)
    }

    @Test
    fun prefersTheLongestMatch() {
        val result = translator.lookUp("食べ物が好き", 0)!!
        assertEquals("食べ物", result.terms.first().expression)
    }

    @Test
    fun onlyDeinflectsToWordsOfTheRightType() {
        // 書かれた can be 書く (godan) but must not end up at the noun 書.
        val result = translator.lookUp("書かれた", 0)!!
        assertEquals("書く", result.terms.first().expression)
        assertTrue(result.terms.none { it.expression == "書" && it.inflections.isNotEmpty() })
        assertEquals("write", result.kanji.single().meanings.single())
    }

    @Test
    fun matchesReadingsAndKatakana() {
        assertEquals("食べる", translator.lookUp("たべた", 0)!!.terms.first().expression)
        val cat = translator.lookUp("ネコ", 0)!!
        assertEquals(setOf("ネコ", "猫"), cat.terms.map { it.expression }.toSet())
        assertEquals("ネコ", cat.terms.first().expression) // as written first
    }

    @Test
    fun suruNounsMatchTheirSuruForms() {
        val result = translator.lookUp("勉強しました。", 0)!!
        assertEquals("勉強", result.terms.first().expression)
        assertEquals("勉強しました", result.terms.first().source)
    }

    @Test
    fun ignoresNonJapaneseText() {
        assertNull(translator.lookUp("Hello 猫", 0))
        assertNull(translator.lookUp("「猫」", 0))
        assertEquals("猫", translator.lookUp("「猫」", 1)!!.terms.first().expression)
    }

    /**
     * A JMdict-like main dictionary (JM) whose entries share a sequence number per word, and
     * another one (Other) without sequences.
     */
    private val groupingStore = object : DictionaryStore {
        fun entry(expression: String, reading: String, gloss: String, dictionary: String, priority: Int, sequence: Long) =
            TermEntry(expression, reading, "", "v1", 0, """["$gloss"]""", sequence, "", dictionary, priority)
        val terms = listOf(
            entry("見る", "みる", "to see", "JM", 1, 100),
            entry("観る", "みる", "to see", "JM", 1, 100),
            entry("診る", "みる", "to examine", "JM", 1, 101),
            entry("見る", "みる", "to look", "Other", 2, -1),
            entry("観る", "みる", "to watch", "Other", 2, -1),
            entry("日", "ひ", "sun", "JM", 1, 200).copy(rules = ""),
            entry("日", "にち", "day", "JM", 1, 201).copy(rules = ""),
        )

        override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
        override fun findTermMeta(expressions: Collection<String>) = emptyList<TermMeta>()
        override fun findKanji(character: String) = emptyList<KanjiEntry>()
        override fun findTermsBySequence(dictionary: String, sequences: Collection<Long>) =
            terms.filter { it.dictionary == dictionary && it.sequence in sequences }
    }

    private fun lookUp(text: String, grouping: Grouping) = Translator(groupingStore, { null }, { grouping }).lookUp(text, 0)!!.terms

    private fun TermGroup.glosses() = definitions.map { it.entry.glossary.removeSurrounding("[\"", "\"]") to it.headwordIndices }

    @Test
    fun groupsAsYomitansResultGroupingModes() {
        // Term-reading pairs (the default): both dictionaries' 見る together
        val grouped = lookUp("見る", Grouping("group")).single()
        assertEquals(listOf("to see" to listOf(0), "to look" to listOf(0)), grouped.glosses())
        // No grouping: each definition its own entry
        assertEquals(2, lookUp("見る", Grouping("split")).size)
        // By term: 日's readings together, as two headwords
        val day = lookUp("日", Grouping("term")).single()
        assertEquals(listOf("ひ", "にち"), day.headwords.map { it.reading })
        assertEquals(listOf("sun" to listOf(0), "day" to listOf(1)), day.glosses())
        assertEquals(2, lookUp("日", Grouping("group")).size)
    }

    @Test
    fun groupsRelatedTermsByTheMainDictionarysSequences() {
        // 観る shares 見る's sequence: a related headword; its identical definition comes once, for both.
        // Other's 見る joins by headword; its 観る only comes from a secondary search.
        val related = lookUp("見る", Grouping("merge", "JM", setOf("Other"))).single()
        assertEquals(listOf("見る" to true, "観る" to false), related.headwords.map { it.expression to it.primary })
        assertEquals(listOf("to see" to listOf(0, 1), "to look" to listOf(0), "to watch" to listOf(1)), related.glosses())
        val noSecondary = lookUp("見る", Grouping("merge", "JM")).single()
        assertEquals(listOf("to see" to listOf(0, 1), "to look" to listOf(0)), noSecondary.glosses())
        // Without a main dictionary, it groups term-reading pairs
        assertEquals(listOf("見る"), lookUp("見る", Grouping("merge")).single().headwords.map { it.expression })
    }

    @Test
    fun partOfSpeechFilteringCanBeTurnedOffPerDictionary() {
        // A dictionary without parts of speech: 食べた deinflects to 食べる, which it doesn't say is a verb
        val store = object : DictionaryStore {
            val terms = listOf(TermEntry("食べる", "たべる", "", "", 0, """["to eat"]""", 0, "", "NoPos", 1))
            override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
            override fun findTermMeta(expressions: Collection<String>) = emptyList<TermMeta>()
            override fun findKanji(character: String) = emptyList<KanjiEntry>()
        }
        assertTrue(Translator(store).lookUp("食べた", 0)!!.terms.none { it.expression == "食べる" })
        val unfiltered = Translator(store, { null }, { Grouping() }) { setOf("NoPos") }.lookUp("食べた", 0)!!
        assertEquals("食べる", unfiltered.terms.first().expression)
        assertEquals("食べた", unfiltered.terms.first().source)
    }
}
