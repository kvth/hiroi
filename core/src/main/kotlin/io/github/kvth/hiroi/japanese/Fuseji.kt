package io.github.kvth.hiroi.japanese

/**
 * Censored characters ("fuseji"): ク○ガキ for クソガキ, ○ね for 死ね, as JL finds them. Each
 * stands for any one character.
 */
object Fuseji {
    /** JL's, and their full-width forms */
    private const val CHARS = "○〇◯●⬤◎◉□■×✕◇◆△▲▽▼※*＊#＃"

    fun isFuseji(c: Char): Boolean = c in CHARS

    /** Whether [text] is a word with some of it censored: at least as much of it not, so ○○ isn't any two characters */
    fun isCensored(text: String): Boolean {
        val censored = text.count(::isFuseji)
        return censored > 0 && text.length - censored >= censored
    }

    /** Whether [key] is [pattern] with its censored characters as any character */
    fun matches(pattern: String, key: String): Boolean =
        key.length == pattern.length && key != pattern && pattern.indices.all { isFuseji(pattern[it]) || pattern[it] == key[it] }
}
