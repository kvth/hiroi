package io.github.kvth.hiroi

import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.junit.Assert.assertEquals
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
        AnkiStatus(mapOf("届く" to listOf(42L))),
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
        assertTrue("an existing note can be opened", "<button class=\"card\" id=\"open0\" onclick=\"Hiroi.openNote(0)\">Open in Anki</button>" in html)
    }

    @Test
    fun numbersDefinitionsWithTheirDictionary() {
        assertTrue(html, "<span class=\"tag label\"><span class=\"label\">★</span></span><span class=\"tag dictionary\"><span class=\"label\">Jitendex</span></span>" in html)
        assertTrue(html, "<ul><li>to reach</li><li>to arrive</li></ul>" in html)
        assertTrue("dictionary names are escaped", "JMdict &lt;b&gt;" in html)
        assertFalse(html, "JMdict <b>" in html)
    }

    private fun frequencyTags(average: Boolean, occurrenceBased: Set<String> = emptySet()): List<String> {
        val meta = listOf(
            TermMeta("届く", "freq", """[{"reading":"とどく","frequency":120}]""", "JPDB", 3),
            TermMeta("届く", "freq", """[{"reading":"とどく","frequency":{"value":200,"displayValue":"200㋕"}}]""", "JPDB", 3),
            TermMeta("届く", "freq", "[480]", "BCCWJ", 5),
            TermMeta("届く", "freq", "[30000]", "Counts", 6),
        )
        val html = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).document(
            LookupResult(0, 2, listOf(term.copy(meta = meta)), emptyList()), null, emptyMap(), cards = false,
            frequencies = ResultsHtml.Frequencies(average, occurrenceBased),
        )
        return Regex("<span class=\"tag frequency\"><span class=\"label\">([^<]*)</span><span class=\"value\">([^<]*)</span>")
            .findAll(html).map { "${it.groupValues[1]}: ${it.groupValues[2]}" }.toList()
    }

    @Test
    fun groupsFrequenciesByDictionaryOrAveragesThem() {
        assertEquals(listOf("JPDB: 120, 200㋕", "BCCWJ: 480", "Counts: 30000"), frequencyTags(average = false))
        // Harmonic mean of each list's lowest value: 3 / (1/120 + 1/480 + 1/30000)
        assertEquals(listOf("Average: 287"), frequencyTags(average = true))
        assertEquals(listOf("Average: 192"), frequencyTags(average = true, occurrenceBased = setOf("Counts")))
    }

    @Test
    fun scopesEachDictionarysStyles() {
        assertTrue(html, ".glossary[data-dictionary=\"Jitendex\"] {\nspan[data-sc-class=\"tag\"] { color: red; }\n}" in html)
    }
}
