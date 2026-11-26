package io.github.kvth.hiroi.marks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkSettingsTest {
    @Test
    fun importsAnAnkiMarkExport() {
        val file = """
            {
              "ankimark": "0.5.0",
              "baseQuery": "\"deck:kaishi\"",
              "rules": [
                { "id": "mature", "name": "Mature", "query": "prop:ivl>=21", "color": "#28C85A", "enabled": true },
                { "id": "mature", "name": "", "query": "is:new", "color": "blue", "enabled": false }
              ],
              "fields": ["Word"],
              "fieldScript": "return [];",
              "style": "underline",
              "opacity": 95,
              "conjugations": false,
              "kanaVariants": true,
              "kanaMinLen": 1,
              "enabled": true,
              "excludedHosts": ["example.com"],
              "syncMinutes": 15
            }
        """.trimIndent()
        val parsed = MarkSettings.parseImport(file)
        assertEquals("\"deck:kaishi\"", parsed.baseQuery)
        val rules = parsed.rules!!
        assertEquals(MarkRule("mature", "Mature", "prop:ivl>=21", "#28c85a", true), rules[0])
        // A repeated id gets a new one; a bad colour turns grey, a blank name gets a number.
        assertTrue(rules[1].id != "mature")
        assertEquals("Rule 2", rules[1].name)
        assertEquals("#888888", rules[1].color)
        assertEquals(false, rules[1].enabled)
        assertEquals(listOf("Word"), parsed.fields)
        assertEquals("underline", parsed.style)
        assertEquals(80, parsed.opacity)
        assertEquals(false, parsed.conjugations)
        assertEquals(1, parsed.kanaMinLen)
        assertEquals(15, parsed.syncMinutes)
        // Sites don't apply to apps.
        assertNull(parsed.excludedApps)
    }

    @Test
    fun rejectsFilesWithoutSettings() {
        assertThrows(IllegalArgumentException::class.java) { MarkSettings.parseImport("[]") }
        assertThrows(IllegalArgumentException::class.java) { MarkSettings.parseImport("{\"other\": 1}") }
        assertEquals("Rule 1 needs a \"query\" text.", assertThrows(IllegalArgumentException::class.java) {
            MarkSettings.parseImport("{\"rules\": [{\"name\": \"x\"}]}")
        }.message)
    }

    @Test
    fun syncSignatureChangesWithWhatASyncReads() {
        val rules = MarkSettings.DEFAULT_RULES
        val base = MarkSettings.syncSignature("deck:A", rules, listOf("Word"), "")
        assertEquals(base, MarkSettings.syncSignature("deck:A ", rules.map { it.copy(color = "#000000", enabled = false) }, listOf("Word"), " "))
        assertTrue(base != MarkSettings.syncSignature("deck:B", rules, listOf("Word"), ""))
        assertTrue(base != MarkSettings.syncSignature("deck:A", rules, listOf("Word"), "return [];"))
    }
}
