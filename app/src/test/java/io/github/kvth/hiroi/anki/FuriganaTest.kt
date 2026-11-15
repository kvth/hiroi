package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.anki.Furigana.Segment
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
}
