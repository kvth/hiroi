package io.github.kvth.hiroi

import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.ResultSection
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
    fun headsTheSectionsOfASearch() {
        val result = LookupResult(0, 5, listOf(term, term), emptyList(), listOf(ResultSection(0, "Japanese: ほうせ"), ResultSection(1, "Meaning")))
        val sectioned = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).document(result, null, emptyMap(), cards = false)
        val japanese = sectioned.indexOf("<div class=\"section\">Japanese: ほうせ</div>")
        val meaning = sectioned.indexOf("<div class=\"section\">Meaning</div>")
        val entries = Regex("<div class=\"entry\">").findAll(sectioned).map { it.range.first }.toList()
        assertTrue(sectioned, japanese in 0 until entries[0] && meaning in entries[0] until entries[1])
        // As tiles: the word, its reading and first glosses, common (JPDB 2412); the whole result inside, shown once tapped
        assertTrue(sectioned, "<div class=\"tile common\" id=\"tile0\"><div class=\"tile-head\" onclick=\"openTile(0)\">" in sectioned)
        assertTrue(sectioned, "<div class=\"tile-reading\" lang=\"ja\">とどく</div><div class=\"tile-word\" lang=\"ja\">届く</div>" in sectioned)
        assertTrue(sectioned, "<div class=\"tile-gloss\">5-dan; to reach</div></div><div class=\"tile-full\"><div class=\"entry\">" in sectioned)
        assertFalse("a lookup without sections isn't in tiles", "class=\"tile" in html)
    }

    @Test
    fun aTileShowsTheGlossThatWasSearchedForFirst() {
        val result = LookupResult(0, 6, listOf(term), emptyList(), listOf(ResultSection(0, "Meaning", listOf("arrive"))))
        val sectioned = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).document(result, null, emptyMap(), cards = false)
        assertTrue(sectioned, "<div class=\"tile-gloss\">to arrive; 5-dan</div>" in sectioned)
    }

    @Test
    fun foldsTheKanjiOfTheWord() {
        val withKanji = term.copy(kanji = listOf(KanjiEntry("届", "カイ", "とど.く", "", listOf("deliver", "reach"), "KANJIDIC", stats = """{"strokes":"8","grade":"6","skip":"3-3-5"}""")))
        val page = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).document(LookupResult(0, 6, listOf(withKanji), emptyList()), null, emptyMap(), cards = false)
        assertTrue(page, "<details class=\"kanji-breakdown\"><summary>Kanji <span lang=\"ja\">届</span></summary>" in page)
        assertTrue(page, "<span class=\"label\">KANJIDIC</span></span> deliver, reach" in page)
        assertTrue(page, "<span class=\"kanji-label\">On</span> カイ · <span class=\"kanji-label\">Kun</span> とど.く" in page)
        assertTrue(page, "<div class=\"kanji-numbers\">8 strokes · grade 6</div>" in page)
        assertFalse("a word without kanji data has no kanji row", "kanji-breakdown\">" in html)
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
    fun makesThePageInParts() {
        val other = term.copy(expression = "届ける", reading = "とどける")
        val page = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA")).Page(
            LookupResult(0, 6, listOf(term, other), emptyList()), AnkiStatus.ASKING,
            styles = mapOf("Jitendex" to "span { color: red; }", "JMdict <b>" to "li { color: blue; }"), cards = false,
        )
        // The first definition only, with the styles it needs; the Anki tags wait hidden for Anki
        val first = page.first(budget = 1)
        assertTrue(first, "5-dan" in first && "color: red" in first)
        assertFalse(first, "to reach" in first || "color: blue" in first || "definitions1" in first)
        assertTrue(first, "<span class=\"tag not-anki asking\" id=\"anki0\">" in first)
        // Then the other styles, the first result's other definitions, and the other result
        val rest = page.rest()!!
        assertTrue(rest, rest.startsWith("addStyles(") && "color: blue" in rest)
        assertTrue(rest, rest.indexOf("addDefinitions(0,") in 0 until rest.indexOf("to reach"))
        assertTrue(rest, rest.indexOf("addResults(") in 0 until rest.indexOf("id=\\\"definitions1\\\""))
        assertEquals(null, page.rest())
    }

    @Test
    fun showsResultsInThePageThatStaysLoaded() {
        val results = ResultsHtml(ResultsHtml.Colors("#000000", "#666666", "#0055AA"))
        val shell = results.shell()
        assertTrue(shell, "function showResults(" in shell && "<body></body>" in shell)
        // The body to show, and each dictionary's styles for the page to add unless it has them
        val show = results.Page(LookupResult(0, 6, listOf(term), emptyList()), null, mapOf("Jitendex" to "span { color: red; }"), cards = false).show()
        assertTrue(show, show.startsWith("showResults({\"Jitendex\":\".glossary[data-dictionary=\\\"Jitendex\\\"] {\\nspan { color: red; }"))
        assertTrue(show, "to reach" in show && "<body>" !in show)
        // Not those the page has already
        val again = results.Page(LookupResult(0, 6, listOf(term), emptyList()), null, mapOf("Jitendex" to "span { color: red; }"), cards = false, hasStyles = { true }).show()
        assertTrue(again, again.startsWith("showResults({},"))
    }

    @Test
    fun scopesEachDictionarysStyles() {
        assertTrue(html, ".glossary[data-dictionary=\"Jitendex\"] {\nspan[data-sc-class=\"tag\"] { color: red; }\n}" in html)
    }
}
