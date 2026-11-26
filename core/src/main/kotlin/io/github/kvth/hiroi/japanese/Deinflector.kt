package io.github.kvth.hiroi.japanese

/**
 * A form the scanned text may be an inflection of. [conditions] are [LanguageTransformer] flags
 * (0: the text as written, fits any entry); [inflections] are the names of the applied
 * transforms, starting at the dictionary form: 食べさせられた -> 食べる with
 * [causative, potential or passive, -た].
 */
data class Deinflection(val text: String, val conditions: Int, val inflections: List<String>)

/** Yomitan's Japanese deinflection rules, loaded from japanese-transforms.json. */
object Deinflector {
    val transformer: LanguageTransformer by lazy {
        val json = Deinflector::class.java.getResourceAsStream("japanese-transforms.json")!!
            .bufferedReader().use { it.readText() }
        LanguageTransformer(json)
    }

    private val names by lazy { transformer.transforms.associate { it.id to it.name } }
    private val descriptions by lazy { transformer.transforms.associate { it.name to it.description } }

    /** What an inflection (by its name, as in [Deinflection.inflections]) does, as Yomitan describes it; Hiroi's own for する. */
    fun description(name: String): String? = if (name == "する") SURU_DESCRIPTION else descriptions[name]?.takeIf { it.isNotEmpty() }

    private const val SURU_DESCRIPTION = "A noun used as a verb with する (勉強 → 勉強する).\nDictionaries list these verbs under the noun."
    private val suru by lazy { transformer.conditionFlagsFromConditionType("vs") }

    /** [text] itself (first) and every form it could be an inflection of, fewest steps first. */
    fun deinflect(text: String): List<Deinflection> {
        val out = mutableListOf<Deinflection>()
        for (result in transformer.transform(text)) {
            val inflections = result.trace.map { names[it.transform] ?: it.transform }
            out += Deinflection(result.text, result.conditions, inflections)
            // Not in Yomitan: dictionaries list する verbs under the noun (勉強, rules "vs"), so 勉強した
            // also gives 勉強. Single-kanji ones (愛する, 察する) have their own entries.
            val couldBeSuru = result.conditions == 0 || (result.conditions and suru) != 0
            if (couldBeSuru && result.text.endsWith("する") && result.text.length >= 4) {
                out += Deinflection(result.text.dropLast(2), suru, listOf("する") + inflections)
            }
        }
        return out
    }

    /** Can a dictionary entry with these space-separated part-of-speech [rules] be the word [conditions] describe? */
    fun accepts(conditions: Int, rules: String): Boolean =
        LanguageTransformer.conditionsMatch(conditions, transformer.conditionFlagsFromPartsOfSpeech(rules.split(' ')))
}
