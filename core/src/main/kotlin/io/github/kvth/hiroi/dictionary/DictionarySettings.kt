package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.settings.KeyValueStore
import org.json.JSONObject

/**
 * Lookups' switches per dictionary, as Yomitan's: [partsOfSpeechFilterOff] has "Part of speech
 * filtering" off, [deinflectionsOff] "Use deinflections".
 */
data class DictionaryOptions(val partsOfSpeechFilterOff: Set<String> = emptySet(), val deinflectionsOff: Set<String> = emptySet())

/** How lookups use the dictionaries: the frequency dictionary results are sorted by, as Yomitan's "Result sorting". */
class DictionarySettings(private val prefs: KeyValueStore) {

    /** Title of the frequency dictionary to sort by; "" for none, null until chosen. */
    var sortDictionary: String?
        get() = prefs.getString("sort_dictionary", null)
        set(value) = prefs.edit().putString("sort_dictionary", value).apply()

    /** Lower values first (ranks), or higher first (occurrence counts). */
    var sortAscending: Boolean
        get() = prefs.getBoolean("sort_ascending", true)
        set(value) = prefs.edit().putBoolean("sort_ascending", value).apply()

    /** One "Average" frequency tag (harmonic mean) instead of one per list, as Yomitan's "Average frequencies". */
    var averageFrequencies: Boolean
        get() = prefs.getBoolean("average_frequencies", false)
        set(value) = prefs.edit().putBoolean("average_frequencies", value).apply()

    /** Yomitan's "Result grouping mode" (see [Grouping]); Yomitan's default groups term-reading pairs. */
    var resultGrouping: String
        get() = prefs.getString("result_grouping", null)?.takeIf { it in Grouping.MODES } ?: "group"
        set(value) = prefs.edit().putString("result_grouping", value).apply()

    /** "Group related terms": the dictionary with related term information (sequences), as Yomitan's "Primary dictionary" */
    var mainDictionary: String
        get() = prefs.getString("main_dictionary", null).orEmpty()
        set(value) = prefs.edit().putString("main_dictionary", value).apply()

    /** "Group related terms": the dictionaries searched for the related terms' definitions, as Yomitan's "Secondary dictionaries" */
    var secondaryDictionaries: Set<String>
        get() = prefs.getStringSet("secondary_dictionaries", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("secondary_dictionaries", value).apply()

    fun grouping() = Grouping(resultGrouping, mainDictionary, secondaryDictionaries)

    /**
     * The dictionaries with Yomitan's "Part of speech filtering" off: a deinflected word finds
     * their entries whatever part of speech those have (or don't say). On for all by default.
     */
    var partsOfSpeechFilterOff: Set<String>
        get() = prefs.getStringSet("parts_of_speech_filter_off", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("parts_of_speech_filter_off", value).apply()

    /**
     * The dictionaries with Yomitan's "Use deinflections" off: the forms of other words they list
     * ([Yomitan.FormOf]) don't find those words. On for all by default.
     */
    var deinflectionsOff: Set<String>
        get() = prefs.getStringSet("deinflections_off", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("deinflections_off", value).apply()

    /** The switches lookups take per dictionary */
    fun options() = DictionaryOptions(partsOfSpeechFilterOff, deinflectionsOff)

    /** The names dictionaries were given (Yomitan's "Rename…", its alias), by title */
    var aliases: Map<String, String>
        get() = prefs.getString("aliases", null)?.let { json ->
            runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull()
        }.orEmpty()
        set(value) = prefs.edit().putString("aliases", JSONObject(value).toString()).apply()

    /** What dictionary [title] is called where it's shown: the name it was given, or its title */
    fun name(title: String): String = aliases[title] ?: title

    /** Names dictionary [title] [alias]; its own title, blank or null names it by its title again. */
    fun setAlias(title: String, alias: String?) {
        val name = alias?.trim().orEmpty()
        aliases = if (name.isEmpty() || name == title) aliases - title else aliases + (title to name)
    }

    /** The tabs dictionaries were put in instead of their language's ([ResultTabs]), by title */
    var tabOverrides: Map<String, String>
        get() = prefs.getString("tabs", null)?.let { json ->
            runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull()
        }.orEmpty()
        set(value) = prefs.edit().putString("tabs", JSONObject(value).toString()).apply()

    /** Puts dictionary [title] in tab [tab]; its language's tab ([default]), blank or null puts it back there. */
    fun setTab(title: String, tab: String?, default: String) {
        val name = ResultTabs.clean(tab)
        tabOverrides = if (name == null || name == default) tabOverrides - title else tabOverrides + (title to name)
    }

    /** The tab results were shown in last ("" for all of them), for the next results that have it */
    var lastTab: String
        get() = prefs.getString("last_tab", null).orEmpty()
        set(value) = prefs.edit().putString("last_tab", value).apply()

    /** Settings that name dictionary [old] name [new] instead (an update can change its title). */
    fun rename(old: String, new: String) {
        if (old == new) return
        if (sortDictionary == old) sortDictionary = new
        if (mainDictionary == old) mainDictionary = new
        if (old in secondaryDictionaries) secondaryDictionaries = secondaryDictionaries - old + new
        if (old in partsOfSpeechFilterOff) partsOfSpeechFilterOff = partsOfSpeechFilterOff - old + new
        if (old in deinflectionsOff) deinflectionsOff = deinflectionsOff - old + new
        aliases[old]?.let { aliases = aliases - old + (new to it) }
        tabOverrides[old]?.let { tabOverrides = tabOverrides - old + (new to it) }
    }
}
