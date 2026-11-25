package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultTabsTest {
    @Test
    fun namesTabsShortByLanguage() {
        assertEquals("国語", ResultTabs.forLanguage("ja", latin = false))
        assertEquals("EN", ResultTabs.forLanguage("en", latin = true))
        assertEquals("DE", ResultTabs.forLanguage("de-DE", latin = true))
        // Without a language: Latin letters count as English, others as Japanese
        assertEquals("EN", ResultTabs.forLanguage(null, latin = true))
        assertEquals("国語", ResultTabs.forLanguage("", latin = false))
    }

    @Test
    fun cleansNamesGiven() {
        assertEquals("JJ", ResultTabs.clean(" J J "))
        assertNull(ResultTabs.clean("  "))
        // 漢字 is the kanji's
        assertNull(ResultTabs.clean("漢字"))
    }
}
