package io.github.kvth.hiroi.settings

import android.content.Context
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.get
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Exports Hiroi's settings to a file and imports them from one (see [SettingsBackup]). */
object SettingsFile {
    fun export(context: Context, out: OutputStream, includeKeys: Boolean) {
        val preferences = SettingsBackup.FILES.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all }
        val dictionaries = DictionaryDb.get(context).dictionaries().map { SettingsBackup.DictionaryState(it.title, it.enabled) }
        val json = SettingsBackup.toJson(preferences, dictionaries, includeKeys, version(context), OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        out.write(json.toString(2).toByteArray())
    }

    fun read(input: InputStream): SettingsBackup.Backup = SettingsBackup.parse(input.bufferedReader().use { it.readText() })

    /**
     * Replaces the settings with [backup]'s: each settings file it has as a whole (keys it lacks
     * stay as they are), and the order and switches of the dictionaries installed here that it
     * names. Dictionaries it names that aren't here are returned.
     */
    fun apply(context: Context, backup: SettingsBackup.Backup): List<String> {
        for ((name, imported) in backup.preferences) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val values = SettingsBackup.merged(name, imported, prefs.all)
            val edit = prefs.edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.commit()
        }
        return DictionaryDb.get(context).applyOrder(backup.dictionaries)
    }

    private fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
