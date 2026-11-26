package io.github.kvth.hiroi.settings

import io.github.kvth.hiroi.settings.SettingsBackup.DictionaryState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val services = """[{"type":"deepl","url":"","key":"secret:fx"},{"type":"libretranslate","url":"https://lt.example.com","key":"lt-key"},{"type":"mlkit","url":"","key":""}]"""

    private val preferences: Map<String, Map<String, Any?>> = mapOf(
        "hiroi" to mapOf("freeze_screen" to false, "search_romaji" to true),
        "anki" to mapOf("deck" to "Mining", "key" to "anki-secret", "card_format" to """{"deck":"Mining"}"""),
        "audio" to mapOf("volume" to 80, "sources" to """[{"type":"jpod101","url":""}]"""),
        "marks" to mapOf("excluded_apps" to setOf("com.b", "com.a"), "opacity" to 35),
        "translation" to mapOf("services" to services, "target" to "de", "auto" to true),
        "other" to mapOf("x" to "not a settings file"),
    )

    private val dictionaries = listOf(DictionaryState("JPDB", true), DictionaryState("Jitendex", false))

    private fun roundTrip(includeKeys: Boolean) =
        SettingsBackup.parse(SettingsBackup.toJson(preferences, dictionaries, includeKeys, "1.0", "2026-11-18T10:00:00+01:00").toString(2))

    @Test
    fun valuesComeBackAsTheyWere() {
        val backup = roundTrip(includeKeys = true)
        assertEquals(preferences - "other", backup.preferences)
        assertEquals(dictionaries, backup.dictionaries)
        assertTrue(backup.includesKeys)
        assertEquals("1.0", backup.app)
        assertEquals("2026-11-18T10:00:00+01:00", backup.exported)
        // Numbers stay ints, string sets sets
        assertEquals(80, backup.preferences.getValue("audio")["volume"])
        assertEquals(setOf("com.a", "com.b"), backup.preferences.getValue("marks")["excluded_apps"])
    }

    @Test
    fun keysOnlyWhenAskedFor() {
        val backup = roundTrip(includeKeys = false)
        assertFalse(backup.includesKeys)
        assertFalse("key" in backup.preferences.getValue("anki"))
        assertEquals("Mining", backup.preferences.getValue("anki")["deck"])
        val exported = JSONArray(backup.preferences.getValue("translation")["services"] as String)
        assertEquals(listOf("", "", ""), List(exported.length()) { exported.getJSONObject(it).getString("key") })
        assertEquals("https://lt.example.com", exported.getJSONObject(1).getString("url"))
    }

    @Test
    fun importingWithoutKeysKeepsTheOnesHere() {
        val imported = roundTrip(includeKeys = false).preferences
        // AnkiConnect's key stays
        val anki = SettingsBackup.merged("anki", imported.getValue("anki"), mapOf("deck" to "Old", "key" to "here"))
        assertEquals("here", anki["key"])
        assertEquals("Mining", anki["deck"])
        // A service keeps its key if the same one (type and address) is here with one
        val here = mapOf("services" to """[{"type":"libretranslate","url":"https://lt.example.com","key":"here-lt"},{"type":"deepl","url":"","key":"here-deepl"}]""")
        val translation = SettingsBackup.merged("translation", imported.getValue("translation"), here)
        val merged = JSONArray(translation["services"] as String)
        assertEquals(listOf("here-deepl", "here-lt", ""), List(merged.length()) { merged.getJSONObject(it).getString("key") })
        assertEquals("de", translation["target"])
        // A file with keys replaces them
        val withKeys = roundTrip(includeKeys = true).preferences
        assertEquals("anki-secret", SettingsBackup.merged("anki", withKeys.getValue("anki"), mapOf("key" to "here"))["key"])
    }

    @Test
    fun onlySettingsFiles() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.parse("""{"hiroi":"1.0","rules":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.parse("not json") }
        val newer = assertThrows(IllegalArgumentException::class.java) { SettingsBackup.parse("""{"format":"hiroi-settings","version":99}""") }
        assertTrue(newer.message!!.contains("newer"))
        // Unknown settings files in it are left out
        val backup = SettingsBackup.parse("""{"format":"hiroi-settings","version":1,"preferences":{"evil":{"a":1},"audio":{"volume":50}}}""")
        assertEquals(mapOf("audio" to mapOf("volume" to 50)), backup.preferences)
    }
}
