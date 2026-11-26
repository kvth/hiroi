package io.github.kvth.hiroi.settings

import android.content.Context
import android.content.SharedPreferences

/** The settings group [name], kept in the SharedPreferences file of that name */
fun Context.preferences(name: String): KeyValueStore = PreferencesStore(getSharedPreferences(name, Context.MODE_PRIVATE))

private class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = prefs.getStringSet(key, default)

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)

    override fun edit(): KeyValueStore.Editor = Editor(prefs.edit())

    private class Editor(private val editor: SharedPreferences.Editor) : KeyValueStore.Editor {
        override fun putString(key: String, value: String?): KeyValueStore.Editor = also { editor.putString(key, value) }

        override fun putStringSet(key: String, value: Set<String>?): KeyValueStore.Editor = also { editor.putStringSet(key, value) }

        override fun putBoolean(key: String, value: Boolean): KeyValueStore.Editor = also { editor.putBoolean(key, value) }

        override fun putInt(key: String, value: Int): KeyValueStore.Editor = also { editor.putInt(key, value) }

        override fun remove(key: String): KeyValueStore.Editor = also { editor.remove(key) }

        override fun apply() = editor.apply()
    }
}
