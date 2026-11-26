package io.github.kvth.hiroi.settings

/** Settings kept in memory, for tests */
class MemoryStore : KeyValueStore {
    private val values = HashMap<String, Any?>()

    override fun getString(key: String, default: String?) = if (key in values) values[key] as String? else default

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = if (key in values) values[key] as Set<String>? else default

    override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default

    override fun getInt(key: String, default: Int) = values[key] as Int? ?: default

    override fun edit() = object : KeyValueStore.Editor {
        private val changes = ArrayList<() -> Unit>()

        private fun change(block: () -> Unit) = also { changes += block }

        override fun putString(key: String, value: String?) = change { values[key] = value }

        override fun putStringSet(key: String, value: Set<String>?) = change { values[key] = value?.toSet() }

        override fun putBoolean(key: String, value: Boolean) = change { values[key] = value }

        override fun putInt(key: String, value: Int) = change { values[key] = value }

        override fun remove(key: String) = change { values.remove(key) }

        override fun apply() = changes.forEach { it() }
    }
}
