package io.github.kvth.hiroi.dictionary

import android.content.Context

/** How lookups use the dictionaries: the frequency dictionary results are sorted by, as Yomitan's "Result sorting". */
class DictionarySettings(context: Context) {
    private val prefs = context.getSharedPreferences("dictionaries", Context.MODE_PRIVATE)

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

    /** Settings that name dictionary [old] name [new] instead (an update can change its title). */
    fun rename(old: String, new: String) {
        if (old == new) return
        if (sortDictionary == old) sortDictionary = new
        if (mainDictionary == old) mainDictionary = new
        if (old in secondaryDictionaries) secondaryDictionaries = secondaryDictionaries - old + new
        if (old in partsOfSpeechFilterOff) partsOfSpeechFilterOff = partsOfSpeechFilterOff - old + new
    }

    /**
     * The sorting to use. Until one is chosen, it's the first imported dictionary with
     * frequencies, in the order it says or Yomitan's check finds; that choice is then kept.
     */
    fun frequencySort(db: DictionaryDb): FrequencySort? {
        val all = db.dictionaries()
        val dictionaries = all.filter { it.enabled }
        val chosen = sortDictionary
        if (chosen == "") return null
        // One that's turned off sorts nothing, but stays chosen for when it's on again.
        if (chosen != null && all.any { it.title == chosen }) return FrequencySort(chosen, sortAscending).takeIf { dictionaries.any { it.title == chosen } }
        // Not chosen yet, or the chosen one was removed.
        val first = dictionaries.firstOrNull { it.frequencies > 0 } ?: return null
        choose(db, first.title)
        return FrequencySort(first.title, sortAscending)
    }

    /** Sorts by [title] (null or "" for none), in the order it says or Yomitan's check finds. */
    fun choose(db: DictionaryDb, title: String?) {
        sortDictionary = title.orEmpty()
        if (!title.isNullOrEmpty()) sortAscending = db.frequencyAscending(title) ?: true
    }
}
