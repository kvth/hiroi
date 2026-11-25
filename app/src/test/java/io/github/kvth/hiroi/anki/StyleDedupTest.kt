package io.github.kvth.hiroi.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of anki-yomitan-css-dedup's tests that are about one field. */
class StyleDedupTest {
    private val sheetA = """.yomitan-glossary [data-dictionary="A"] b { color: red; }"""
    private val sheetB = """.yomitan-glossary [data-dictionary="B"] i { margin: 0; }"""
    private val compact = """.yomitan-glossary ul[data-sc-content="glossary"] { display: inline; }"""
    private val entryA = """<div class="yomitan-glossary"><ol><li data-dictionary="A"><ul data-sc-content="glossary"><li>x</li></ul></li></ol></div>"""
    private val entryB = entryA.replace("\"A\"", "\"B\"")

    @Test
    fun dictionaryStylesLeaveTheField() {
        val field = StyleDedup.field("<style>$sheetA</style><div>text</div>")
        assertEquals("<div>text</div>", field.text)
        assertEquals(listOf(sheetA), field.moved)
        assertEquals("A", StyleDedup.sheetKey(field.moved.single()))
    }

    @Test
    fun rulesNotLimitedToADictionaryStayOncePerField() {
        val field = StyleDedup.field("<style>$sheetA\n$compact</style><style>$sheetB\n$compact</style>$entryA$entryB")
        assertEquals("<style>$compact</style>$entryA$entryB", field.text)
        assertEquals(listOf(sheetA, sheetB), field.moved)
    }

    @Test
    fun blocksWithoutDictionaryRulesStayAsTheyAre() {
        val plain = "<style>b { color: red; }</style><b>x</b>"
        assertEquals(plain, StyleDedup.field(plain).text)
        assertTrue(StyleDedup.field(plain).moved.isEmpty())
        // Inline styles and attributes aren't touched
        val html = """<div data-sc-content="x" class="a" style="color:red" lang="ja">t</div>"""
        assertEquals(html, StyleDedup.field("<style>$sheetA</style>$html").text)
    }

    @Test
    fun atRulesMoveOnlyIfEverythingInsideIsScoped() {
        val scoped = """@media (max-width: 600px) { .yomitan-glossary [data-dictionary="A"] b { color: red; } }"""
        val mixed = """@media print { .yomitan-glossary [data-dictionary="A"] b { color: red; } ul { margin: 0; } }"""
        assertEquals(scoped to listOf(mixed), StyleDedup.splitSheet("$scoped\n$mixed"))
    }

    @Test
    fun selectorListsAndBrokenSelectors() {
        // Every part of a selector list must be limited to the dictionary
        val both = """[data-dictionary="A"] b, [data-dictionary="A"] i { color: red; }"""
        val oneOnly = """[data-dictionary="A"] b, i { color: red; }"""
        val negated = """li:not([data-dictionary="A"]) { color: red; }"""
        // Yomitan's stray <br> breaks a selector, which browsers then drop: it stays where it was
        val broken = """<br>[data-dictionary="A"] b { color: red; }"""
        assertEquals(both to listOf(oneOnly, negated, broken), StyleDedup.splitSheet("$both\n$oneOnly\n$negated\n$broken"))
    }

    @Test
    fun theSectionIsTheAddOnsFormat() {
        val section = StyleDedup.section(linkedMapOf("A" to sheetA, "B" to sheetB))
        assertEquals(
            "/* BEGIN yomitan-dictionary-styles (generated, do not edit) */\n" +
                "/* yomitan-sheet: A */\n$sheetA\n/* yomitan-sheet: B */\n$sheetB\n" +
                "/* END yomitan-dictionary-styles */",
            section,
        )
    }

    @Test
    fun sheetsGoIntoTheNoteTypesStyling() {
        // No section yet: one at the end, the Styling before it as it was
        val card = ".card { font-size: 20px; }"
        val first = StyleDedup.withSheets(card, linkedMapOf("A" to sheetA))
        assertEquals(card + "\n\n" + StyleDedup.section(linkedMapOf("A" to sheetA)), first)
        assertEquals(StyleDedup.section(linkedMapOf("A" to sheetA)), StyleDedup.withSheets("", linkedMapOf("A" to sheetA)))
        // With a section: sheets added to it, a dictionary's replaced, what follows it kept
        val newA = sheetA.replace("red", "blue")
        val updated = StyleDedup.withSheets("$first\n.after { }", linkedMapOf("B" to sheetB, "A" to newA))
        assertEquals(card + "\n\n" + StyleDedup.section(linkedMapOf("A" to newA, "B" to sheetB)) + "\n.after { }", updated)
        // Nothing changes when it has them all, or without sheets
        assertEquals(updated, StyleDedup.withSheets(updated, linkedMapOf("B" to sheetB)))
        assertEquals(card, StyleDedup.withSheets(card, emptyMap()))
    }

    @Test
    fun whatTheSectionHasBeforeItsSheetsStays() {
        val css = "${StyleDedup.SHEETS_BEGIN}\n.extra { }\n/* yomitan-sheet: A */\n$sheetA\n${StyleDedup.SHEETS_END}"
        assertEquals(
            "${StyleDedup.SHEETS_BEGIN}\n.extra { }\n/* yomitan-sheet: A */\n$sheetA\n/* yomitan-sheet: B */\n$sheetB\n${StyleDedup.SHEETS_END}",
            StyleDedup.withSheets(css, linkedMapOf("B" to sheetB)),
        )
    }
}
