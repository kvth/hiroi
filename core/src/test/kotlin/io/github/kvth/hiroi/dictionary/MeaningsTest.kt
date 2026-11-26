package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningsTest {
    @Test
    fun wordsAreLowercaseWithoutAccents() {
        assertEquals(listOf("madchen", "fur", "strasse", "cafe"), Meanings.words("Mädchen für Straße, café"))
        assertEquals(listOf("to", "eat", "e", "g", "a", "meal"), Meanings.words("to eat (e.g. a meal)"))
        assertEquals(listOf("don", "t", "2nd"), Meanings.words("don't 2nd"))
        // Japanese separates words, and isn't one
        assertEquals(listOf("bachirusu", "a", "bacillus"), Meanings.words("バチルス [ローマ字](bachirusu) 〔桿菌〕 a bacillus"))
        assertEquals(emptyList<String>(), Meanings.words("食べる"))
    }

    @Test
    fun glossesOfTextAreItsLines() {
        assertEquals(listOf("schmeicheln", "lobhudeln"), Meanings.glosses("""["schmeicheln\nlobhudeln\n"]"""))
        assertEquals(listOf("to eat", "to have a meal"), Meanings.glosses("""["to eat", {"type": "text", "text": "to have a meal"}]"""))
        // An inflection of another term
        assertEquals(emptyList<String>(), Meanings.glosses("""[["食べる", ["past"]]]"""))
    }

    @Test
    fun glossesOfStructuredContentAreItsGlossListsWithoutExamples() {
        // As Jitendex and JMdict: a list marked "glossary" beside examples and notes
        val jitendex = """[{"type": "structured-content", "content": [{"tag": "span", "data": {"content": "part-of-speech-info"}, "content": "noun"},
            {"tag": "ul", "data": {"content": "glossary"}, "content": [{"tag": "li", "content": "meal"}, {"tag": "li", "content": ["food ", {"tag": "ruby", "content": ["食", {"tag": "rt", "content": "しょく"}]}]}]},
            {"tag": "div", "data": {"content": "example-sentence"}, "content": "A meal at home is the best."}]}]"""
        assertEquals(listOf("meal", "food 食"), Meanings.glosses(jitendex))
        // As Wiktionary's: "glosses", examples inside an item
        val wiktionary = """[{"type": "structured-content", "content": [{"tag": "ol", "data": {"content": "glosses"}, "content": [
            {"tag": "li", "content": [{"tag": "div", "content": "technische Oberschule"}, {"tag": "details", "data": {"content": "details-entry-examples"}, "content": "Beispiel"}]}]},
            {"tag": "div", "data": {"content": "backlink"}, "content": "Wiktionary"}]}]"""
        assertEquals(listOf("technische Oberschule"), Meanings.glosses(wiktionary))
        // Without gloss lists: each line of its text
        assertEquals(listOf("to be", "to exist"), Meanings.glosses("""[{"type": "structured-content", "content": [{"tag": "div", "content": "to be"}, {"tag": "div", "content": "to exist"}]}]"""))
    }

    @Test
    fun latinLettersAsTheIndexSaysOrAsTheGlossesAre() {
        assertTrue(Meanings.inLatinLetters("en", emptyList()))
        assertTrue(Meanings.inLatinLetters("de", emptyList()))
        assertFalse(Meanings.inLatinLetters("ja", listOf("""["to eat"]""")))
        assertTrue(Meanings.inLatinLetters(null, listOf("""["essen, speisen"]""", """["食べる"]""")))
        assertFalse(Meanings.inLatinLetters(null, listOf("""["食べ物を口に入れて、かんで飲み込む。eat"]""")))
        assertFalse(Meanings.inLatinLetters(null, emptyList()))
    }
}
