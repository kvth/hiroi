package io.github.kvth.hiroi.dictionary

/**
 * [frequencies]: how many frequency values it has (terms and kanji); [frequencyMode] as its
 * index.json says, if it does. [forms]: how many of its terms are forms of other words
 * ([Yomitan.FormOf]); null if it was imported before Hiroi counted them.
 */
data class DictionaryInfo(
    val id: Long,
    val title: String,
    val revision: String,
    val terms: Int,
    val meta: Int,
    val kanji: Int,
    val frequencies: Int = 0,
    val frequencyMode: String? = null,
    val enabled: Boolean = true,
    val images: Int = 0,
    val forms: Int? = null,
)

/**
 * What a dictionary's tag in the results says about it when tapped, as in Yomitan: its title,
 * revision, and its index.json's [author], [description] and [url] (empty if it has none); how
 * many [terms], [kanji] and [frequencies] it has.
 */
data class DictionarySummary(
    val title: String,
    val revision: String,
    val author: String = "",
    val description: String = "",
    val url: String = "",
    val terms: Int = 0,
    val kanji: Int = 0,
    val frequencies: Int = 0,
)
