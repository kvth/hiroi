package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextVariantsTest {
    private fun variants(text: String) = TextVariants.of(text).associate { it.text to it.steps }

    /** The variants in hiragana without long vowel marks: what the long vowels and collapsing make of [text] */
    private fun spelledOut(text: String) = variants(text).keys.filter { s -> s.none { it == 'ー' || it in 'ァ'..'ヶ' } }.toSet()

    @Test
    fun asWrittenFirstWithNoSteps() {
        assertEquals(TextVariants.Variant("食べる", 0), TextVariants.of("食べる").first())
        assertEquals(mapOf("食べる" to 0), variants("食べる"))
    }

    @Test
    fun halfWidthKanaAndSquaredWords() {
        assertEquals(1, variants("ｶﾞｯｺｳ")["ガッコウ"])
        assertEquals(2, variants("ｶﾞｯｺｳ")["がっこう"])
        assertEquals(1, variants("㍿")["株式会社"])
        assertEquals(1, variants("㋿")["令和"])
    }

    @Test
    fun latinLettersAndDigitsInEitherWidth() {
        assertEquals(1, variants("Tシャツ")["Ｔシャツ"])
        assertEquals(1, variants("Ｔシャツ")["Tシャツ"])
        assertEquals(1, variants("CD")["ＣＤ"])
        assertEquals(1, variants("1日")["１日"])
    }

    @Test
    fun iterationMarks() {
        assertEquals(1, variants("人〻")["人人"])
        assertEquals(1, variants("人〻")["人々"])
        assertEquals(1, variants("いすゞ")["いすず"])
        assertEquals(1, variants("こゝろ")["こころ"])
        assertEquals(1, variants("ミスヾ")["ミスズ"])
        assertEquals(1, variants("時時")["時々"])
        // Nothing to repeat
        assertEquals(mapOf("々" to 0), variants("々"))
    }

    @Test
    fun kanaInTheOtherScript() {
        assertEquals(1, variants("ごみ")["ゴミ"])
        assertEquals(1, variants("ゴミ")["ごみ"])
    }

    @Test
    fun emphaticSequencesCollapsed() {
        val v = variants("すっっごーーい")
        assertEquals(1, v["すっごーい"])
        assertEquals(1, v["すごい"])
        // Not at the start or end
        assertEquals(setOf("っす", "ッス"), variants("っす").keys)
        assertTrue("すごいっ" in variants("すっごいっ"))
    }

    @Test
    fun longVowelMarks() {
        val v = variants("セーソー")
        // Hiragana, then each ー both ways after e and o
        for (s in listOf("せえそお", "せえそう", "せいそお", "せいそう")) assertEquals(s, 2, v[s])
        assertEquals(2, variants("セ〜ソ〜")["せいそう"])
        // ～ is normalized to ~ first
        assertEquals(3, variants("セ～ソ～")["せいそう"])
        // A run is one vowel; after a, i, u only that vowel; or collapsed away
        assertEquals(setOf("すごおい", "すごうい", "すごい"), spelledOut("すごーーい"))
        assertEquals(setOf("かあさん", "かさん"), spelledOut("かーさん"))
        // Small vowels drawing out a kana
        assertTrue("すごおい" in variants("すごぉい"))
        // Not a long vowel: ふぁ, or a mark first
        assertEquals(setOf("ふぁん", "ファン"), variants("ふぁん").keys)
        assertEquals(setOf("ーあ", "ーア"), variants("ーあ").keys)
    }

    @Test
    fun noLongVowelsForMoreThanThree() {
        assertTrue(variants("せーせーせーせー").keys.none { it.startsWith("せい") || it.startsWith("せえ") })
        assertEquals(8, spelledOut("せーせーせー").count { it.length == 6 })
    }
}
