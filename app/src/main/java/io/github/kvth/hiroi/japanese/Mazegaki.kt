package io.github.kvth.hiroi.japanese

/**
 * Mixed spellings ("mazegaki"): a word written with some of its kanji in kana, as newspapers
 * write kanji outside the jōyō list (改ざん for 改竄, ぼう大 for 膨大, 覚せい剤 for 覚醒剤). JL
 * makes such spellings of its frequency and pitch accent lists' words; here a text is checked
 * against a dictionary's spelling and reading.
 */
object Mazegaki {
    /** Hiragana, katakana and their marks (ー, ゝ) */
    fun isKana(c: Char): Boolean = c in '぀'..'ヿ'

    /**
     * Whether [text] is [expression] with one or more of its kanji in kana, but not all, as
     * [reading] reads them: its other kanji and its kana as in [expression], each kanji standing
     * for some of [reading] (a kana run for a kanji run, at least a kana each), kana as in [reading].
     */
    fun matches(text: String, expression: String, reading: String): Boolean {
        if (text.length < 2 || expression == text || expression.all(::isKana) || text.all(::isKana) || text.none(::isKana)) return false
        val t = Japanese.toHiragana(text)
        val e = Japanese.toHiragana(expression)
        val r = Japanese.toHiragana(reading)
        // Whether text from i and expression from j can be read as reading from k (0 unknown, 1 yes, 2 no)
        val known = ByteArray((t.length + 1) * (e.length + 1) * (r.length + 1))
        fun go(i: Int, j: Int, k: Int): Boolean {
            if (j == e.length) return i == t.length && k == r.length
            if (i == t.length || k == r.length) return false
            val at = (i * (e.length + 1) + j) * (r.length + 1) + k
            if (known[at] != 0.toByte()) return known[at] == 1.toByte()
            val c = e[j]
            val yes = if (isKana(c)) {
                t[i] == c && r[k] == c && go(i + 1, j + 1, k + 1)
            } else {
                // The kanji as written, reading a few kana
                (t[i] == c && (1..MAX_KANJI_READING).any { m -> k + m <= r.length && go(i + 1, j + 1, k + m) }) ||
                    // Or it and the kanji after it in kana: as much of the reading as the text has
                    replaced(t, e, r, i, j, k, ::go)
            }
            known[at] = if (yes) 1 else 2
            return yes
        }
        return go(0, 0, 0)
    }

    /** Whether some of the kanji run of [e] at [j] is the kana of [t] at [i], read as [r] at [k], and the rest [go]es */
    private fun replaced(t: String, e: String, r: String, i: Int, j: Int, k: Int, go: (Int, Int, Int) -> Boolean): Boolean {
        val kanji = (j until e.length).takeWhile { !isKana(e[it]) }.size
        val kana = (i until t.length).takeWhile { isKana(t[it]) }.size
        for (a in 1..kanji) {
            for (b in a..minOf(kana, a * MAX_KANJI_READING)) {
                if (k + b > r.length || !r.regionMatches(k, t, i, b)) break
                if (go(i + b, j + a, k + b)) return true
            }
        }
        return false
    }

    /** The most kana a kanji is read as (承: うけたまわ) */
    private const val MAX_KANJI_READING = 5
}
