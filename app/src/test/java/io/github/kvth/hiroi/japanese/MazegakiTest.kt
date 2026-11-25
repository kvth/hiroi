package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MazegakiTest {
    @Test
    fun kanjiWrittenInKana() {
        assertTrue(Mazegaki.matches("改ざん", "改竄", "かいざん"))
        assertTrue(Mazegaki.matches("ぼう大", "膨大", "ぼうだい"))
        assertTrue(Mazegaki.matches("覚せい剤", "覚醒剤", "かくせいざい"))
        assertTrue(Mazegaki.matches("取りあつかい", "取り扱い", "とりあつかい"))
        // Several kanji of a run in kana
        assertTrue(Mazegaki.matches("処ほうせん", "処方箋", "しょほうせん"))
        // In katakana too
        assertTrue(Mazegaki.matches("改ザン", "改竄", "かいざん"))
    }

    @Test
    fun notAMixedSpelling() {
        // The same spelling, or the reading alone
        assertFalse(Mazegaki.matches("改竄", "改竄", "かいざん"))
        assertFalse(Mazegaki.matches("かいざん", "改竄", "かいざん"))
        // Kana that aren't the reading
        assertFalse(Mazegaki.matches("改ざん", "改正", "かいせい"))
        // A kanji of the text the spelling doesn't have
        assertFalse(Mazegaki.matches("改ざん", "変竄", "へんざん"))
        // Kana the spelling has differently
        assertFalse(Mazegaki.matches("食べる", "食う", "くう"))
    }
}
