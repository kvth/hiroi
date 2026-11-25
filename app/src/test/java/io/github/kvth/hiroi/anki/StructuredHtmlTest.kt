package io.github.kvth.hiroi.anki

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredHtmlTest {
    @Test
    fun linksToOtherWordsKeepTheWord() {
        val link = """{"type":"structured-content","content":{"tag":"a","href":"?query=%E9%A3%9F%E3%81%86&wildcards=off","content":"食う"}}"""
        assertEquals("<span class=\"link\" data-query=\"食う\">食う</span>", StructuredHtml.item(JSONObject(link)))
        // Plain text in the query too, and links elsewhere stay plain
        assertEquals("食べる", StructuredHtml.linkQuery("?query=食べる&wildcards=off"))
        assertNull(StructuredHtml.linkQuery("https://example.com/"))
        assertNull(StructuredHtml.linkQuery("?wildcards=off"))
    }
}
