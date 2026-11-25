package io.github.kvth.hiroi.dictionary

/**
 * The tabs results can be shown in: each term dictionary is in one, by the language of its
 * definitions unless it was put in another ([DictionarySettings.tabs]), and 漢字 shows the
 * words' kanji. A tab shows the same results in the same order, with only its definitions.
 */
object ResultTabs {
    const val KANJI = "漢字"

    /** Japanese definitions: a Japanese–Japanese dictionary (国語辞典) */
    const val JAPANESE = "国語"

    /**
     * The tab of a dictionary with definitions in [language] (its index.json's targetLanguage):
     * 国語 for Japanese, else the language's code (EN, DE). Without one, [latin] tells: definitions
     * in Latin letters count as English, others as Japanese.
     */
    fun forLanguage(language: String?, latin: Boolean): String {
        val code = language?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: if (latin) "en" else "ja"
        return if (code == "ja") JAPANESE else code.uppercase()
    }

    /** [name] as a tab's name: without spaces, since a result lists its tabs separated by them; null if nothing's left */
    fun clean(name: String?): String? = name?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() && it != KANJI }
}
