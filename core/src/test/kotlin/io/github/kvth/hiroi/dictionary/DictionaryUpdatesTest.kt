package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DictionaryUpdatesTest {
    @Test
    fun comparesRevisionsAsYomitan() {
        // Dot-separated numbers part by part (Jitendex's)
        assertTrue(DictionaryUpdates.newer("2026.10.03.0", "2026.11.05.0"))
        assertTrue(DictionaryUpdates.newer("2026.9.1", "2026.10.1"))
        assertFalse(DictionaryUpdates.newer("2026.11.05.0", "2026.11.05.0"))
        assertFalse(DictionaryUpdates.newer("2026.11.05.0", "2026.10.03.0"))
        // Anything else as text (JMdict's "JMdict.2026-11-22", Jiten's)
        assertTrue(DictionaryUpdates.newer("JMdict.2026-11-15", "JMdict.2026-11-22"))
        assertFalse(DictionaryUpdates.newer("Jiten 26-11-21", "Jiten 26-11-21"))
    }

    private val jitendex = """{"title":"Jitendex.org [2026-10-03]","revision":"2026.10.03.0","isUpdatable":true,
        "indexUrl":"https://jitendex.org/static/yomitan.json","downloadUrl":"https://example.com/jitendex-yomitan.zip"}"""

    @Test
    fun readsTheUpdateLinksOfTheStoredIndex() {
        assertEquals(
            DictionaryUpdates.Source("https://jitendex.org/static/yomitan.json", "https://example.com/jitendex-yomitan.zip", "2026.10.03.0"),
            DictionaryUpdates.source(jitendex),
        )
        assertNull(DictionaryUpdates.source("""{"title":"JPDB","revision":"1"}"""))
        assertNull(DictionaryUpdates.source("""{"title":"X","revision":"1","isUpdatable":true,"indexUrl":"https://x/i.json"}"""))
        assertNull(DictionaryUpdates.source(null))
    }

    @Test
    fun offersOnlyNewerRevisions() {
        val source = DictionaryUpdates.source(jitendex)!!
        val newer = """{"title":"Jitendex.org [2026-11-05]","revision":"2026.11.05.0","downloadUrl":"https://example.com/new.zip"}"""
        assertEquals(DictionaryUpdates.Update("2026.11.05.0", "https://example.com/new.zip"), DictionaryUpdates.check(source) { newer })
        // Without a download URL of its own, the installed one's
        assertEquals("https://example.com/jitendex-yomitan.zip", DictionaryUpdates.check(source) { """{"title":"J","revision":"2026.12.01.0"}""" }!!.downloadUrl)
        assertNull(DictionaryUpdates.check(source) { """{"title":"J","revision":"2026.10.03.0"}""" })
        assertThrows(IOException::class.java) { DictionaryUpdates.check(source) { "<html>Not found</html>" } }
    }
}
