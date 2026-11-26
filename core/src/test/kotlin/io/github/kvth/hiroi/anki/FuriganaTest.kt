package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.anki.Furigana.Segment
import io.github.kvth.hiroi.dictionary.DictionaryStore
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermMeta
import io.github.kvth.hiroi.dictionary.Translator
import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaTest {
    @Test
    fun distributesReadingsOverKanjiRuns() {
        assertEquals(listOf(Segment("食", "た"), Segment("べる", null)), Furigana.distribute("食べる", "たべる"))
        assertEquals(listOf(Segment("お", null), Segment("茶", "ちゃ")), Furigana.distribute("お茶", "おちゃ"))
        assertEquals(listOf(Segment("日本語", "にほんご")), Furigana.distribute("日本語", "にほんご"))
        assertEquals(listOf(Segment("引", "ひ"), Segment("き", null), Segment("出", "だ"), Segment("す", null)), Furigana.distribute("引き出す", "ひきだす"))
        assertEquals(listOf(Segment("アプリ", null)), Furigana.distribute("アプリ", "あぷり"))
    }

    @Test
    fun formatsLikeYomitan() {
        val segments = Furigana.distribute("勉強する", "べんきょうする")
        assertEquals("<ruby>勉強<rt>べんきょう</rt></ruby>する", Furigana.html(segments))
        assertEquals("勉強[べんきょう]する", Furigana.plain(segments))
        assertEquals("引[ひ]き 出[だ]す", Furigana.plain(Furigana.distribute("引き出す", "ひきだす")))
    }

    @Test
    fun keepsReadingsForInflectedForms() {
        val segments = Furigana.forSource("食べさせられた", "食べる", "たべる")
        assertEquals("食[た]べさせられた", Furigana.plain(segments))
        assertEquals("たべさせられた", Furigana.kana(segments))
    }

    private fun term(expression: String, reading: String, rules: String = "") =
        TermEntry(expression, reading, "", rules, 0, """["$expression"]""", 0, "", "Test", 1)

    private val translator = Translator(object : DictionaryStore {
        val terms = listOf(
            term("ご飯", "ごはん"),
            term("飯", "めし"),
            term("を", "を", "prt"),
            term("食べる", "たべる", "v1"),
            term("この", "この"),
            term("先生", "せんせい"),
            term("先", "さき"),
            term("きのこ", "きのこ"),
        )

        override fun findTerms(forms: Collection<String>) = terms.filter { it.expression in forms || it.reading in forms }
        override fun findTermMeta(expressions: Collection<String>) = emptyList<TermMeta>()
        override fun findKanji(character: String) = emptyList<KanjiEntry>()
        override fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int) = emptyList<TermEntry>()
    })

    private fun parse(text: String) = Furigana.forText(text) { t, i ->
        translator.lookUp(t, i, definitions = false)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) }
    }

    private fun words(text: String) = parse(text).map { word -> word.joinToString("") { it.text } }

    @Test
    fun parsesTextIntoWordsAsYomitansScanningParser() {
        // Looked up at kana too: ご飯 is one word, not ご and 飯 read めし
        assertEquals(listOf("ご飯", "を", "食べた"), words("ご飯を食べた"))
        assertEquals("ご 飯[はん]を 食[た]べた", Furigana.plain(parse("ご飯を食べた").flatten()))
        // Longest match first, as Yomitan's parser: the greedy split MeCab would avoid
        assertEquals(listOf("この", "先生", "きのこ", "る"), words("この先生きのこる"))
    }

    @Test
    fun groupsTextNoWordStartsIn() {
        assertEquals(listOf("「", "ご飯", "」！ A ", "を"), words("「ご飯」！ A を"))
        assertEquals(listOf(listOf(Segment("xyz", null))), parse("xyz"))
    }
}
