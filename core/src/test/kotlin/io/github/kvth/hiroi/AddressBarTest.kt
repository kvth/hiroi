package io.github.kvth.hiroi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressBarTest {
    @Test
    fun putsBackTheSchemeBrowsersLeaveOut() {
        assertEquals("https://kvth.github.io/mokuro-reader/#/reader/x", AddressBar.url("kvth.github.io/mokuro-reader/#/reader/x"))
        assertEquals("https://example.com", AddressBar.url(" example.com "))
        assertEquals("https://localhost:5173/?page=3", AddressBar.url("localhost:5173/?page=3"))
        assertEquals("https://192.168.0.20:8080/a", AddressBar.url("192.168.0.20:8080/a"))
        assertEquals("https://ja.wikipedia.org/wiki/日本語", AddressBar.url("ja.wikipedia.org/wiki/日本語"))
    }

    @Test
    fun keepsAddressesThatHaveTheirScheme() {
        assertEquals("http://example.com/a", AddressBar.url("http://example.com/a"))
        assertEquals("https://example.com/a", AddressBar.url("https://example.com/a"))
        assertEquals("file:///sdcard/a.html", AddressBar.url("file:///sdcard/a.html"))
    }

    @Test
    fun isNullForWhatIsNoWebPage() {
        assertNull(AddressBar.url(""))
        assertNull(AddressBar.url("Search or type web address"))
        assertNull(AddressBar.url("日本語"))
        assertNull(AddressBar.url("about:blank"))
        assertNull(AddressBar.url("chrome://settings"))
        assertNull(AddressBar.url("view-source:example.com"))
        assertNull(AddressBar.url("word"))
    }
}
