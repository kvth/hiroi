package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeinflectorTest {
    private fun forms(text: String) = Deinflector.deinflect(text).map { it.text }

    // ankimark's cases (test/japanese.test.mjs), which Yomitan's rules cover as well.
    @Test
    fun deinflectsVerbsAdjectivesAndChainsBackToTheDictionaryForm() {
        val cases = mapOf(
            "食べた" to "食べる", "食べない" to "食べる", "食べなかった" to "食べる", "食べます" to "食べる",
            "食べませんでした" to "食べる", "食べたい" to "食べる", "食べたかった" to "食べる", "食べられる" to "食べる",
            "食べさせられなかった" to "食べる", "食べている" to "食べる", "食べてる" to "食べる", "食べていた" to "食べる",
            "食べちゃった" to "食べる", "食べれば" to "食べる", "食べよう" to "食べる",
            "書いた" to "書く", "書いて" to "書く", "書かない" to "書く", "書きます" to "書く", "書ける" to "書く",
            "書かれた" to "書く", "書こう" to "書く", "書けば" to "書く", "書いておく" to "書く", "書いとく" to "書く",
            "泳いだ" to "泳ぐ", "話した" to "話す", "待った" to "待つ", "死んだ" to "死ぬ", "遊んだ" to "遊ぶ",
            "読んだ" to "読む", "帰った" to "帰る", "買った" to "買う", "買わない" to "買う", "行った" to "行く", "行って" to "行く",
            "わかった" to "わかる", "勉強した" to "勉強する", "勉強しません" to "勉強する", "勉強できる" to "勉強する",
            "来た" to "来る", "来ない" to "来る", "来ます" to "来る", "こなかった" to "くる", "きた" to "くる",
            "高かった" to "高い", "高くて" to "高い", "高くない" to "高い", "高くなかった" to "高い", "高ければ" to "高い",
            // Yomitan gives よい, the form dictionaries list いい under.
            "よかった" to "よい", "行かなきゃ" to "行く", "食べたら" to "食べる", "読んだり" to "読む",
            // Beyond ankimark's rules
            "行っちまった" to "行く", "食べなさい" to "食べる", "知らん" to "知る", "問うた" to "問う",
            "いらっしゃいます" to "いらっしゃる",
        )
        for ((form, base) in cases) {
            assertTrue("$form -> $base, got ${forms(form).take(8)}", base in forms(form))
        }
    }

    @Test
    fun suruVerbsAlsoGiveTheNoun() {
        assertTrue("勉強" in forms("勉強した"))
        assertTrue("勉強" in forms("勉強する"))
        assertFalse("愛" in forms("愛した"))
    }

    @Test
    fun namesTheInflectionsFromTheDictionaryFormOut() {
        val deinflection = Deinflector.deinflect("食べさせられた").first { it.text == "食べる" }
        assertEquals(listOf("causative", "potential or passive", "-た"), deinflection.inflections)
        assertEquals(listOf("する", "-ます", "-た"), Deinflector.deinflect("勉強しました").first { it.text == "勉強" }.inflections)
    }

    @Test
    fun matchesEntriesByTheirYomitanRules() {
        val ichidan = Deinflector.deinflect("食べた").first { it.text == "食べる" }
        assertTrue(Deinflector.accepts(ichidan.conditions, "v1"))
        assertTrue(Deinflector.accepts(ichidan.conditions, "v1 vt"))
        assertFalse(Deinflector.accepts(ichidan.conditions, "v5"))
        assertFalse(Deinflector.accepts(ichidan.conditions, ""))

        val noun = Deinflector.deinflect("勉強した").first { it.text == "勉強" }
        assertTrue(Deinflector.accepts(noun.conditions, "vs"))
        assertFalse(Deinflector.accepts(noun.conditions, "n"))

        val adjective = Deinflector.deinflect("高かった").first { it.text == "高い" }
        assertTrue(Deinflector.accepts(adjective.conditions, "adj-i"))

        val asWritten = Deinflector.deinflect("猫").first()
        assertTrue(Deinflector.accepts(asWritten.conditions, ""))
    }
}
