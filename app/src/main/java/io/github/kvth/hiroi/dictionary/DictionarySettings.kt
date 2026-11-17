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
