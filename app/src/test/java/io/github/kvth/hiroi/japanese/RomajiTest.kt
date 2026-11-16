package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiTest {
    private fun check(vararg cases: Pair<String, String>) {
        for ((romaji, kana) in cases) assertEquals(romaji, kana, Romaji.toKana(romaji))
    }

    @Test
    fun convertsHepburnAndKunreiSpellings() {
        check(
            "taberu" to "たべる", "tabesaserareta" to "たべさせられた", "benkyou" to "べんきょう",
            "shinbun" to "しんぶん", "sinbun" to "しんぶん", "tsukau" to "つかう", "tukau" to "つかう",
            "chiisai" to "ちいさい", "tiisai" to "ちいさい", "jisho" to "じしょ", "zyouzu" to "じょうず",
            "fuji" to "ふじ", "wo" to "を", "kyaku" to "きゃく", "ryokou" to "りょこう",
        )
    }

    @Test
    fun handlesNAndDoubleConsonants() {
        check(
            "konnichiha" to "こんにちは", "hon" to "ほん", "kin'en" to "きんえん", "kinen" to "きねん",
            "onna" to "おんな", "nnn" to "んん", "minna" to "みんな", "sanpo" to "さんぽ", "hon'ya" to "ほんや",
            "kitte" to "きって", "matcha" to "まっちゃ", "maccha" to "まっちゃ", "zasshi" to "ざっし",
        )
    }

    @Test
    fun smallKanaLongVowelsAndKatakana() {
        check(
            "ra-men" to "らーめん", "RA-MEN" to "ラーメン", "KOOHII" to "コオヒイ", "xtsu" to "っ", "la" to "ぁ",
            "fairu" to "ふぁいる", "FAIRU" to "ファイル", "thi" to "てぃ", "vaiorin" to "ゔぁいおりん",
        )
    }

    @Test
    fun leavesTheRestAlone() {
        check(
            "食べru" to "食べる", "tab" to "たb", "日本go no hon" to "日本ご の ほん", "123" to "123",
            "k" to "k", "-a" to "-あ",
        )
    }
}
