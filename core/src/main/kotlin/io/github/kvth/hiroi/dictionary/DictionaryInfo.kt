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
