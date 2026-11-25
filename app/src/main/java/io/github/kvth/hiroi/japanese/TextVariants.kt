package io.github.kvth.hiroi.japanese

import java.text.Normalizer

/**
 * The other ways a text may be written in a dictionary, as JL normalizes text for its lookups
 * and Yomitan's text processors vary it. Each step works on what the ones before made:
 * - half-width kana and squared words as they're written in full: ｶﾞｯｺｳ → ガッコウ, ㍿ → 株式会社
 * - Latin letters and digits in either width: Tシャツ ↔ Ｔシャツ
 * - iteration marks spelled out: 人〻 → 人人, いすゞ → いすず, こゝろ → こころ; 〻 as 々, and a
 *   kanji written twice as with 々 (時時 → 時々)
 * - kana in the other script: katakana as hiragana, and kana-only text as katakana (ごみ → ゴミ)
 * - emphatic っ and ー collapsed, as Yomitan does: すっっごーーい → すっごーい, すごい
 * - long vowel marks (ー, 〜) and drawn-out small vowels as the vowel they stand for: セーソー → せいそう
 */
object TextVariants {
    /** [text] as it may be written in a dictionary; [steps]: how many changes made it so (Yomitan sorts by the fewest) */
    data class Variant(val text: String, val steps: Int)

    /** Whether a lookup reads on at [c]: Japanese, or one of the characters only these variants make Japanese of */
    fun scans(c: Char): Boolean =
        Japanese.isJapanese(c) || isLatin(c) || c in '㌀'..'㏿' || c == '㋿' || c == '〻' || c == '〜' || c == '～'

    /** Latin letters and digits, in either width */
    fun isLatin(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in 'ａ'..'ｚ' || c in 'Ａ'..'Ｚ' || c in '０'..'９'

    /** [text] as written (0 steps) and its variants, each with the fewest steps that make it */
    fun of(text: String): List<Variant> {
        val out = LinkedHashMap<String, Int>()
        out[text] = 0
        for (step in STEPS) {
            for ((s, steps) in out.entries.toList()) {
                for (variant in step(s)) if (variant != s && (out[variant] ?: Int.MAX_VALUE) > steps + 1) out[variant] = steps + 1
            }
        }
        return out.map { (s, steps) -> Variant(s, steps) }
    }

    private val STEPS: List<(String) -> List<String>> = listOf(
        { s -> listOf(Normalizer.normalize(s, Normalizer.Form.NFKC)) },
        { s -> listOf(toFullWidth(s)) },
        ::iterationMarks,
        // Katakana only for kana (ごみ → ゴミ): words mixing kanji and katakana are few, and it would double every lookup
        { s -> listOfNotNull(Japanese.toHiragana(s), Japanese.toKatakana(s).takeIf { Japanese.isKanaOnly(s) }) },
        { s -> listOf(collapseEmphatic(s, full = false), collapseEmphatic(s, full = true)) },
        ::longVowels,
    )

    /** [s] with its Latin letters and digits full-width (Ｔシャツ); the step before makes them half-width */
    private fun toFullWidth(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c + ('Ａ' - 'A') else c)
    }

    /**
     * Yomitan's collapseEmphaticSequences: each run of っ, ッ or ー inside [s] (not at its start or
     * end) as one of them, or none if [full]: すっっごーーい → すっごーい, すごい.
     */
    private fun collapseEmphatic(s: String, full: Boolean): String {
        val left = s.indexOfFirst { it !in EMPHATIC }
        if (left < 0) return s
        val right = s.indexOfLast { it !in EMPHATIC }
        return buildString(s.length) {
            append(s, 0, left)
            var collapsing: Char? = null
            for (i in left..right) {
                val c = s[i]
                if (c !in EMPHATIC) {
                    collapsing = null
                    append(c)
                } else if (c != collapsing) {
                    collapsing = c
                    if (!full) append(c)
                }
            }
            append(s, right + 1, s.length)
        }
    }

    private const val EMPHATIC = "っッー"

    /** [s] with its iteration marks spelled out, with 〻 as 々, and with kanji written twice as with 々 */
    private fun iterationMarks(s: String): List<String> {
        val spelled = StringBuilder(s.length)
        for (c in s) {
            val previous = spelled.lastOrNull()
            spelled.append(
                when {
                    previous == null -> c
                    c == '々' || c == '〻' || c == 'ゝ' || c == 'ヽ' -> previous
                    c == 'ゞ' || c == 'ヾ' -> voiced(previous) ?: c
                    else -> c
                }
            )
        }
        val doubled = StringBuilder(s.length)
        for (c in s) doubled.append(if (Japanese.isKanji(c) && doubled.lastOrNull() == c) '々' else c)
        return listOf(spelled.toString(), s.replace('〻', '々'), doubled.toString())
    }

    /** [c] with a dakuten (か → が, う → ゔ), if it takes one */
    private fun voiced(c: Char): Char? = Normalizer.normalize("$c゙", Normalizer.Form.NFC).singleOrNull()

    /**
     * [s] with each long vowel mark, or small vowel drawing out the kana before it (すごぉい), as
     * the vowel it stands for, as JL reads them: a run of them is one vowel, and after an e or o
     * both ways (せー: せえ, せい; そー: そお, そう). Only for hiragana; none for text that starts with
     * one or has more than [MAX_LONG_VOWELS] (8 variants).
     */
    private fun longVowels(s: String): List<String> {
        if (s.isEmpty() || s[0] in LONG_VOWEL_MARKS) return emptyList()
        var out = listOf(StringBuilder().append(s[0]))
        var count = 0
        var i = 1
        while (i < s.length) {
            val c = s[i]
            val vowel = VOWELS[s[i - 1]]
            val other = vowel?.let { ALTERNATIVE_VOWELS[it] }
            if (vowel == null || !drawsOut(c, vowel, other)) {
                for (b in out) b.append(c)
                i++
                continue
            }
            if (++count > MAX_LONG_VOWELS) return emptyList()
            while (i + 1 < s.length && drawsOut(s[i + 1], vowel, other)) i++
            out = if (other == null) out.onEach { it.append(vowel) } else out.flatMap { listOf(StringBuilder(it).append(vowel), it.append(other)) }
            i++
        }
        return if (count == 0) emptyList() else out.map { it.toString() }
    }

    /** Whether [c] draws out a kana ending in [vowel] (or [other], its other way) */
    private fun drawsOut(c: Char, vowel: Char, other: Char?): Boolean =
        c in LONG_VOWEL_MARKS || SMALL_VOWELS[c].let { it != null && (it == vowel || it == other) }

    private const val MAX_LONG_VOWELS = 3

    /** ～ is ~ once normalized */
    private const val LONG_VOWEL_MARKS = "ー〜~"

    private val SMALL_VOWELS = mapOf('ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お')

    /** An え drawn out is also read い (せい), an お also う (そう) */
    private val ALTERNATIVE_VOWELS = mapOf('え' to 'い', 'お' to 'う')

    /** The vowel each hiragana ends in */
    private val VOWELS: Map<Char, Char> = buildMap {
        for ((vowel, kana) in listOf(
            'あ' to "あかさたなはまらがざだばぱわやぁゃゕゎ",
            'い' to "いきしちにひみりぎじぢびぴぃ",
            'う' to "うくすつぬふむるぐずづぶぷゆぅゅゔ",
            'え' to "えけせてねへめれげぜでべぺぇゖ",
            'お' to "おこそとのほもろごぞどぼぽをよぉょ",
        )) for (k in kana) put(k, vowel)
    }
}
