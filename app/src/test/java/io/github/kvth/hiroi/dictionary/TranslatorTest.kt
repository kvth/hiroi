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
        override fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int) =
            terms.filter { t -> listOf(t.expression, t.reading).any { if (suffix) it.endsWith(affix) else it.startsWith(affix) } }
                .sortedBy { it.expression.length }.take(limit)
    }
    private val translator = Translator(store)

    /** A store that can be searched by meaning: "eat", "water" */
    private val latin = object : DictionaryStore by store {
        val meanings = mapOf("eat" to listOf("食べる"), "water" to listOf("勉強"), "taberu" to listOf("書く"), "neko" to listOf("書く"))

        override fun findTermsByMeaning(query: String, limit: Int) =
            meanings[Meanings.words(query).joinToString(" ")].orEmpty().flatMap { e -> store.terms.filter { it.expression == e } }.map { it to 32 }

        override fun meaningWordCounts(words: Collection<String>) = words.filter { it in meanings }.associateWith { 1 }

        override fun existingForms(forms: Collection<String>) =
            forms.filterTo(HashSet()) { f -> store.terms.any { it.expression == f || it.reading == f } }
    }
    private val titles = Translator.SectionTitles({ "Japanese: $it" }, "Meaning", { "Did you mean $it?" })

    private fun sections(result: LookupResult) = result.sections.map { s ->
        val end = result.sections.firstOrNull { it.first > s.first }?.first ?: result.terms.size
        s.title to result.terms.subList(s.first, end).map { it.expression }
    }

    @Test
    fun latinTextIsSearchedInSections() {
        val translator = Translator(latin)
        // By meaning
        assertEquals(listOf("Meaning" to listOf("食べる")), sections(translator.lookUpLatin("eat", null, true, titles)))
        // Romaji read as kana, all of it a word; then what has it in its definitions
        assertEquals(listOf("Japanese: たべる" to listOf("食べる"), "Meaning" to listOf("書く")), sections(translator.lookUpLatin("taberu", "たべる", true, titles)))
        // Nothing as typed: a spelling one letter away, by meaning and as romaji
        assertEquals(listOf("Did you mean water?" to listOf("勉強")), sections(translator.lookUpLatin("wter", null, true, titles)))
        // tbaeru doesn't read as kana: a spelling by meaning first ("taberu" is in definitions here, as in Wadoku's)
        assertEquals(
            listOf("Did you mean taberu?" to listOf("書く"), "Did you mean たべる?" to listOf("食べる")),
            sections(translator.lookUpLatin("tbaeru", null, true, titles)),
        )
        assertEquals(listOf("Did you mean taberu?" to listOf("書く")), sections(translator.lookUpLatin("tbaeru", null, false, titles)))
        // Romaji that reads as kana but isn't a word: romaji one edit away first
        assertEquals(listOf("Did you mean たべもの?" to listOf("食べ物")), sections(translator.lookUpLatin("tabemonoo", "たべものお", true, titles)))
    }

    @Test
    fun aCommonJapaneseWordComesBeforeTheMeaningARareOneAfter() {
        val translator = Translator(latin, frequencySort = { FrequencySort("Freq", true) })
        // 食べる is ranked 300
        assertEquals(listOf("Japanese: たべる", "Meaning"), translator.lookUpLatin("taberu", "たべる", true, titles).sections.map { it.title })
        // 猫 has no rank
        assertEquals(listOf("Meaning", "Japanese: ねこ"), translator.lookUpLatin("neko", "ねこ", true, titles).sections.map { it.title })
    }

    @Test
    fun aWordNoDefinitionHasIsSpelledAsTheMostCommonOneEditAway() {
        val counts = object : DictionaryStore by store {
            override fun meaningWordCounts(words: Collection<String>) = mapOf("water" to 50, "wader" to 2, "eat" to 9).filterKeys { it in words }
        }
        val translator = Translator(counts)
        assertEquals("water", translator.correctMeaning("wter"))
        assertEquals("eat water", translator.correctMeaning("eat watr"))
        assertEquals(null, translator.correctMeaning("eat"))
        // Too short to tell
        assertEquals(null, translator.correctMeaning("wa"))
    }

    @Test
    fun aWordSeveralDictionariesAgreeOnComesBeforeOneThatFitsBetterInOne() {
        val agreeing = object : DictionaryStore by store {
            override fun findTermsByMeaning(query: String, limit: Int) = listOf(
                term("食う", "くう", "v5") to 32,
                term("食べる", "たべる", "v1").copy(dictionary = "A") to 48,
                term("食べる", "たべる", "v1").copy(dictionary = "B") to 48,
                term("食べる", "たべる", "v1").copy(dictionary = "C") to 64,
                // Too far off to agree
                term("食べる", "たべる", "v1").copy(dictionary = "D") to 200,
            )
        }
        // 食べる: 48, and two more dictionaries about as good: as good as 16
        assertEquals(listOf("食べる", "食う"), Translator(agreeing).lookUpMeaning("eat").terms.map { it.expression })
    }

    @Test
    fun aResultHasTheKanjiOfItsWord() {
        val result = translator.lookUp("書いた", 0)!!
        assertEquals(listOf("書"), result.terms.first { it.expression == "書く" }.kanji.map { it.character })
        assertEquals(emptyList<KanjiEntry>(), translator.lookUp("ねこ", 0)!!.terms.first { it.expression == "ネコ" }.kanji)
    }

    @Test
    fun searchByMeaningPutsTheBestFittingFirstThenTheMoreCommon() {
        val meanings = object : DictionaryStore by store {
            override fun findTermsByMeaning(query: String, limit: Int) = listOf(
                term("食事", "しょくじ", "") to 65,
                term("食う", "くう", "v5") to 32,
                term("食べる", "たべる", "v1") to 32,
            )
        }
        val result = Translator(meanings, frequencySort = { FrequencySort("Freq", true) }).lookUpMeaning("eat")
        // 食べる and 食う fit as well; 食べる is the more common
        assertEquals(listOf("食べる", "食う", "食事"), result.terms.map { it.expression })
        assertEquals(0 to 3, result.start to result.end)
    }

    @Test
    fun wildcardSearchFindsWordsStartingWithTheText() {
        val result = translator.lookUpWildcard("食べ", suffix = false)
        assertEquals(setOf("食べる", "食べ物"), result.terms.mapTo(HashSet()) { it.expression })
        assertTrue(result.terms.all { it.source == "食べ" && it.inflections.isEmpty() })
        assertEquals(0 to 2, result.start to result.end)
        // By reading too
        assertEquals(setOf("猫", "ネコ"), translator.lookUpWildcard("ね", suffix = false).terms.mapTo(HashSet()) { it.expression })
    }

    @Test
    fun wildcardSearchFindsWordsEndingWithTheTextAsWritten() {
        assertEquals(listOf("書く"), translator.lookUpWildcard("く", suffix = true).terms.map { it.expression })
        // Not deinflected: 食べた doesn't end in る
        assertTrue(translator.lookUpWildcard("べた", suffix = true).terms.isEmpty())
    }

    @Test
    fun wildcardIsAnAsteriskAtEitherEnd() {
        assertEquals(Wildcard("食べ", suffix = false, start = 0), Wildcard.parse("食べ*"))
        assertEquals(Wildcard("べる", suffix = true, start = 1), Wildcard.parse("＊べる"))
        assertEquals(Wildcard("べ", suffix = true, start = 2), Wildcard.parse("**べ*"))
        assertEquals(Wildcard("", suffix = true, start = 1), Wildcard.parse("*"))
        assertNull(Wildcard.parse("食べる"))
        assertNull(Wildcard.parse("食*べる"))
    }

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

    @Test
    fun readsTheGlossariesOfTheShownResultsOnly() {
        // Two entries alike but for their glossaries (and rows): both stay, each with its own glossary.
        val store = object : DictionaryStore {
            val glossaries = mapOf(1L to """["cat"]""", 2L to """["kitty"]""", 3L to """["to write"]""")
            val terms = listOf(
                TermEntry("猫", "ねこ", "", "", 0, "", 0, "", "Lazy", 1, id = 1),
                TermEntry("猫", "ねこ", "", "", 0, "", 0, "", "Lazy", 1, id = 2),
                TermEntry("書く", "かく", "", "v5", 0, "", 0, "", "Lazy", 1, id = 3),
            )
            val asked = mutableListOf<Long>()
            override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
            override fun findTermMeta(expressions: Collection<String>) = emptyList<TermMeta>()
            override fun findKanji(character: String) = emptyList<KanjiEntry>()
            override fun withGlossaries(entries: List<TermEntry>) = entries.map { e ->
                asked += e.id
                e.copy(glossary = glossaries.getValue(e.id))
            }
        }
        val cat = Translator(store).lookUp("猫", 0)!!.terms.single()
        assertEquals(listOf("cat", "kitty"), cat.definitions.flatMap { Yomitan.glossaryItems(it.entry.glossary) })
        // 書く wasn't matched, so its glossary wasn't read
        assertEquals(listOf(1L, 2L), store.asked)
        store.asked.clear()
        assertEquals("猫", Translator(store).lookUp("猫", 0, definitions = false)!!.terms.single().expression)
        assertTrue(store.asked.isEmpty())
    }
}
