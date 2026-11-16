package io.github.kvth.hiroi

import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsHtmlTest {
    private val term = TermGroup(
        expression = "届く", reading = "とどく", source = "届かなかった", inflections = listOf("negative", "-た"),
        entries = listOf(
            TermEntry("届く", "とどく", "★", "v5", 0, """[{"type":"structured-content","content":{"tag":"span","data":{"class":"tag"},"content":"5-dan"}}]""", 0, "", "Jitendex", 1),
            TermEntry("届く", "とどく", "", "v5", 0, """["to reach", "to arrive"]""", 0, "", "JMdict <b>", 2),
        ),
        meta = listOf(TermMeta("届く", "freq", "[2412]", "JPDB", 3), TermMeta("届く", "pitch", """[{"reading":"とどく","pitches":[{"position":2}]}]""", "NHK", 4)),
    )
    private val html = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).document(
        LookupResult(0, 6, listOf(term), emptyList()),
        AnkiStatus(setOf("届く")),
        styles = mapOf("Jitendex" to "span[data-sc-class=\"tag\"] { color: red; }"),
        cards = true,
    )

    @Test
    fun showsTheWordLikeYomitan() {
        assertTrue(html, "<ruby>届<rt>とど</rt></ruby>く" in html)
        assertTrue(html, "« negative « -た" in html)
        assertTrue(html, "<span class=\"tag anki\" id=\"anki0\"><span class=\"label\">✓ in Anki</span></span>" in html)
        assertTrue(html, "<span class=\"tag frequency\"><span class=\"label\">JPDB</span><span class=\"value\">2412</span></span>" in html)
        assertTrue(html, "class=\"tag pitch\"><span class=\"label\">NHK</span>" in html)
        assertTrue(html, "onclick=\"Hiroi.addCard(0)\"" in html)
    }

    @Test
    fun numbersDefinitionsWithTheirDictionary() {
        assertTrue(html, "<span class=\"tag label\"><span class=\"label\">★</span></span><span class=\"tag dictionary\"><span class=\"label\">Jitendex</span></span>" in html)
        assertTrue(html, "<ul><li>to reach</li><li>to arrive</li></ul>" in html)
        assertTrue("dictionary names are escaped", "JMdict &lt;b&gt;" in html)
        assertFalse(html, "JMdict <b>" in html)
    }

    @Test
    fun scopesEachDictionarysStyles() {
        assertTrue(html, ".glossary[data-dictionary=\"Jitendex\"] {\nspan[data-sc-class=\"tag\"] { color: red; }\n}" in html)
    }
}
