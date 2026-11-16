package io.github.kvth.hiroi.japanese

/**
 * Romaji to kana as Japanese keyboards and Yomitan's search box (wanakana) convert it: Hepburn
 * and kunrei spellings (shi/si, tsu/tu, chi/ti, ja/zya), double consonants as っ (kitte, matcha),
 * n as ん before a consonant, at the end, or written nn / n', small kana with x or l (xtsu, la),
 * and - as ー. Lower case gives hiragana, upper case katakana. Everything else stays as it is,
 * including letters that don't form a syllable (yet).
 */
object Romaji {
    private val TABLE: Map<String, String> = buildMap {
        val vowels = "aiueo"
        fun row(consonant: String, kana: String) {
            for ((i, v) in vowels.withIndex()) {
                val k = kana.split(' ')[i]
                if (k != "-") put(consonant + v, k)
            }
        }
        row("", "あ い う え お")
        row("k", "か き く け こ")
        row("g", "が ぎ ぐ げ ご")
        row("s", "さ し す せ そ")
        row("z", "ざ じ ず ぜ ぞ")
        row("t", "た ち つ て と")
        row("d", "だ ぢ づ で ど")
        row("n", "な に ぬ ね の")
        row("h", "は ひ ふ へ ほ")
        row("b", "ば び ぶ べ ぼ")
        row("p", "ぱ ぴ ぷ ぺ ぽ")
        row("m", "ま み む め も")
        row("y", "や い ゆ いぇ よ")
        row("r", "ら り る れ ろ")
        row("l", "ぁ ぃ ぅ ぇ ぉ")
        row("x", "ぁ ぃ ぅ ぇ ぉ")
        row("w", "わ うぃ う うぇ を")
        row("f", "ふぁ ふぃ ふ ふぇ ふぉ")
        row("v", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ")
        row("j", "じゃ じ じゅ じぇ じょ")
        row("q", "くぁ くぃ く くぇ くぉ")
        row("sh", "しゃ し しゅ しぇ しょ")
        row("ch", "ちゃ ち ちゅ ちぇ ちょ")
        row("ts", "つぁ つぃ つ つぇ つぉ")
        row("th", "てゃ てぃ てゅ てぇ てょ")
        row("dh", "でゃ でぃ でゅ でぇ でょ")
        row("wh", "うぁ うぃ う うぇ うぉ")
        // Contracted sounds: kya, sya, zya, jya, cya, tya, ...
        val small = mapOf('a' to "ゃ", 'u' to "ゅ", 'o' to "ょ", 'e' to "ぇ", 'i' to "ぃ")
        val stems = mapOf(
            "k" to "き", "g" to "ぎ", "s" to "し", "z" to "じ", "j" to "じ", "c" to "ち", "t" to "ち", "d" to "ぢ",
            "n" to "に", "h" to "ひ", "b" to "び", "p" to "ぴ", "m" to "み", "r" to "り", "v" to "ゔ", "f" to "ふ",
        )
        for ((c, kana) in stems) for ((v, s) in small) putIfAbsent(c + "y" + v, kana + s)
        put("si", "し"); put("ti", "ち"); put("tu", "つ"); put("hu", "ふ"); put("zi", "じ"); put("di", "ぢ"); put("du", "づ")
        put("ca", "か"); put("cu", "く"); put("co", "こ")
        put("xtu", "っ"); put("ltu", "っ"); put("xtsu", "っ"); put("ltsu", "っ")
        put("xya", "ゃ"); put("lya", "ゃ"); put("xyu", "ゅ"); put("lyu", "ゅ"); put("xyo", "ょ"); put("lyo", "ょ")
        put("xwa", "ゎ"); put("lwa", "ゎ"); put("xka", "ゕ"); put("xke", "ゖ")
    }

    private const val MAX_KEY = 4
    private const val VOWELS = "aiueo"

    /** True if [text] has letters that [toKana] would convert. */
    fun hasRomaji(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun toKana(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!c.isAsciiLetter()) {
                // Long vowel mark after kana: ra-men
                out.append(if (c == '-' && out.isNotEmpty() && out.last().isKana()) 'ー' else c)
                i++
                continue
            }
            val katakana = c.isUpperCase()
            fun emit(kana: String, length: Int) {
                out.append(if (katakana) Japanese.toKatakana(kana) else kana)
                i += length
            }
            val lower = c.lowercaseChar()
            val next = text.getOrNull(i + 1)?.takeIf { it.isAsciiLetter() }?.lowercaseChar()
            val key = (MAX_KEY downTo 1).firstNotNullOfOrNull { n ->
                text.substring(i, minOf(text.length, i + n)).lowercase().takeIf { it.length == n && it.all { ch -> ch in 'a'..'z' } && it in TABLE }
            }
            when {
                // nn / n' / n before a consonant or at the end: ん. "nn" before a vowel is ん + n (konnichiha).
                lower == 'n' && next == 'n' -> {
                    val third = text.getOrNull(i + 2)?.lowercaseChar()
                    emit("ん", if (third != null && (third in VOWELS || third == 'y')) 1 else 2)
                }
                lower == 'n' && text.getOrNull(i + 1) == '\'' -> emit("ん", 2)
                key != null -> emit(TABLE.getValue(key), key.length)
                lower == 'n' && (next == null || (next !in VOWELS && next != 'y')) -> emit("ん", 1)
                // Double consonants: kitte, matcha (tch), maccha
                next != null && lower !in VOWELS && (next == lower || (lower == 't' && next == 'c')) -> emit("っ", 1)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isKana() = this in '぀'..'ヿ'
}
