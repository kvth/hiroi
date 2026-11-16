package io.github.kvth.hiroi.japanese

object Japanese {
    /** Kana, kanji (BMP only), iteration marks and half-width katakana. */
    fun isJapanese(c: Char): Boolean =
        c == '々' || c == '〆' ||
            c in '぀'..'ヿ' ||
            isKanji(c) ||
            c in 'ｦ'..'ﾟ'

    fun isKanji(c: Char): Boolean =
        c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿'

    // Hiragana and katakana sit 0x60 apart (ぁ/ァ ... ゖ/ヶ, ゝ/ヽ, ゞ/ヾ).
    fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ') c - 0x60 else c)
    }

    fun toKatakana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ') c + 0x60 else c)
    }

    /** Hiragana, katakana and half-width katakana only. */
    fun isKanaOnly(s: String): Boolean = s.isNotEmpty() && s.all { it in '぀'..'ヿ' || it in 'ｦ'..'ﾟ' }
}
