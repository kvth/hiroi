package io.github.kvth.hiroi.dictionary

import android.content.Context
import io.github.kvth.hiroi.settings.preferences

// DictionarySettings on the phone, and its parts that need the dictionaries themselves ([DictionaryDb])

fun DictionarySettings(context: Context) = DictionarySettings(context.preferences("dictionaries"))

/** Each term dictionary's tab, by title: the one it was put in, or its language's ([DictionaryDb.languageTabs]) */
fun DictionarySettings.tabs(db: DictionaryDb): Map<String, String> {
    val languages = db.languageTabs()
    return languages + tabOverrides.filterKeys { it in languages }
}

/**
 * The sorting to use. Until one is chosen, it's the first imported dictionary with
 * frequencies, in the order it says or Yomitan's check finds; that choice is then kept.
 */
fun DictionarySettings.frequencySort(db: DictionaryDb): FrequencySort? {
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
fun DictionarySettings.choose(db: DictionaryDb, title: String?) {
    sortDictionary = title.orEmpty()
    if (!title.isNullOrEmpty()) sortAscending = db.frequencyAscending(title) ?: true
}
