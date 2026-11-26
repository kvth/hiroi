package io.github.kvth.hiroi.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ankimark's matcher cases (test/japanese.test.mjs). */
class WordMatcherTest {
    private fun found(text: String, words: List<String>, options: WordMatcher.Options = WordMatcher.Options()) =
        WordMatcher(words.associateWith { it }, options).find(text).map { "${text.substring(it.start, it.end)}=${it.word}" }

    @Test
    fun matchesTheWholeInflectedWordAndNamesItsDictionaryForm() {
        assertEquals(listOf("パン=パン", "食べなかった=食べる"), found("昨日パンを食べなかった。", listOf("食べる", "パン")))
        assertEquals(listOf("手紙=手紙", "書いておいた=書く"), found("手紙を書いておいた", listOf("書く", "手紙")))
        assertEquals(listOf("勉強しています=勉強"), found("毎日勉強しています", listOf("勉強")))
        assertEquals(listOf("わかった=わかる"), found("もうわかった", listOf("わかる")))
        assertEquals(listOf("食べさせられた=食べる"), found("子供の頃は野菜を食べさせられた。", listOf("食べる")))
    }

    @Test
    fun prefersTheLongestWordAndNeverOverlaps() {
        assertEquals(listOf("日本語=日本語", "話した=話す"), found("日本語を話した", listOf("日本", "日本語", "話す")))
    }

    @Test
    fun doesNotStretchAWordOverUnrelatedText() {
        // 書類 is a different word; 書く must not grab it
        assertEquals(listOf("書類=書類", "書く=書く"), found("書類を書くと", listOf("書く", "書類")))
        assertEquals(listOf("学校=学校"), found("学校で", listOf("学校")))
        // particles after a verb are not part of it
        assertEquals(listOf("食べた=食べる"), found("食べたのは", listOf("食べる")))
    }

    @Test
    fun withConjugationsOffOnlyWordsAsWrittenMatch() {
        assertEquals(listOf("食べる=食べる"), found("食べた。食べる", listOf("食べる"), WordMatcher.Options(conjugations = false)))
    }

    @Test
    fun skipsShortKanaOnlyWords() {
        assertEquals(listOf("ペン=ペン"), found("これはペン", listOf("は", "ペン")))
        assertEquals(listOf("は=は", "ペン=ペン"), found("これはペン", listOf("は", "ペン"), WordMatcher.Options(kanaMinLen = 1)))
    }

    @Test
    fun extractWordsCleansAnkiFields() {
        assertEquals(listOf("日本語"), NoteWords.extractWords(" 日本[にほん] 語[ご]"))
        assertEquals(listOf("漢字"), NoteWords.extractWords("<ruby>漢字<rt>かんじ</rt></ruby>"))
        assertEquals(listOf("食べる", "喰べる"), NoteWords.extractWords("食べる、喰べる"))
        assertEquals(listOf("勉強"), NoteWords.extractWords("勉強（する）"))
        assertEquals(listOf("刺", "棘", "トゲ"), NoteWords.extractWords("(forms) 刺（★） | 棘（★） | トゲ"))
        assertEquals(listOf("猫"), NoteWords.extractWords("{{c1::猫::cat}}[sound:neko.mp3]&nbsp;"))
    }

    @Test
    fun kanaWordsMatchInTheOtherScriptToo() {
        assertEquals(listOf("ごみ=ゴミ"), found("ごみを捨てた", listOf("ゴミ")))
        assertEquals(listOf("トゲ=とげ"), found("トゲが刺さった", listOf("とげ")))
        assertEquals(listOf("はまった=ハマる"), found("ゲームにはまった", listOf("ハマる")))
        assertEquals(1, found("ゴミ", listOf("ゴミ"), WordMatcher.Options(kanaVariants = false)).size)
        assertEquals(emptyList<String>(), found("ごみ", listOf("ゴミ"), WordMatcher.Options(kanaVariants = false)))
        // kanji words get no variants
        assertEquals(emptyList<String>(), found("たべる", listOf("食べる")))
    }

    @Test
    fun aNoteForTheOtherSpellingWinsOverTheVariant() {
        val m = WordMatcher(mapOf("ゴミ" to "katakana", "ごみ" to "hiragana"))
        assertEquals(listOf(Triple("ごみ", "hiragana", false)), m.find("ごみ").map { Triple(it.word, it.entry, it.variant) })
        assertEquals(listOf(Triple("ゴミ", "katakana", false)), m.find("ゴミ").map { Triple(it.word, it.entry, it.variant) })
        assertTrue(WordMatcher(mapOf("ゴミ" to 1)).find("ごみ")[0].variant)
        assertEquals(2, WordMatcher(mapOf("ゴミ" to 1, "日本" to 2)).size)
    }

    @Test
    fun variantsRespectTheKanaMinimumLength() {
        assertEquals(emptyList<String>(), found("へ", listOf("ヘ"), WordMatcher.Options(kanaMinLen = 2)))
    }

    @Test
    fun inflectedMatchesAreFlagged() {
        val m = WordMatcher(mapOf("食べる" to 1)).find("食べた")
        assertTrue(m.single().inflected)
        assertFalse(WordMatcher(mapOf("食べる" to 1)).find("食べる").single().inflected)
    }
}
