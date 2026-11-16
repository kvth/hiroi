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

    private fun readings(sort: FrequencySort?) = Translator(sortStore) { sort }.lookUp("日", 0)!!.terms.map { it.reading }

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
}
