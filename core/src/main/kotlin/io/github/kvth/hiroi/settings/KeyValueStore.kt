package io.github.kvth.hiroi.settings

/**
 * Where a group of settings is kept (anki, audio, marks…): SharedPreferences on Android. It's the
 * part of SharedPreferences' API the settings classes use, so they're the same on any platform.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?

    fun getStringSet(key: String, default: Set<String>?): Set<String>?

    fun getBoolean(key: String, default: Boolean): Boolean

    fun getInt(key: String, default: Int): Int

    fun edit(): Editor

    /** Changes that take effect together with [apply] */
    interface Editor {
        fun putString(key: String, value: String?): Editor

        fun putStringSet(key: String, value: Set<String>?): Editor

        fun putBoolean(key: String, value: Boolean): Editor

        fun putInt(key: String, value: Int): Editor

        fun remove(key: String): Editor

        fun apply()
    }
}
