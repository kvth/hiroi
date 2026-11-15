package io.github.kvth.hiroi.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFormatTest {
    // Shape of a Yomitan settings export, trimmed down
    private val export = """{"version": 0, "options": {"profiles": [
        {"name": "Default", "options": {"anki": {"tags": ["yomitan"], "checkForDuplicates": true, "duplicateBehavior": "new",
            "duplicateScope": "collection", "cardFormats": [
            {"name": "Expression", "type": "term", "deck": "Lapis", "model": "Lapis", "fields": {
                "Expression": {"value": "{expression}", "overwriteMode": "coalesce"},
                "Sentence": {"value": "{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}", "overwriteMode": "coalesce"},
                "PAGraphs": {"value": "{jpmn-pitch-accent-graphs}", "overwriteMode": "coalesce"}}},
            {"name": "Reading", "type": "term", "deck": "", "model": "", "fields": {}},
            {"name": "Kanji", "type": "kanji", "deck": "K", "model": "Kanji", "fields": {"Kanji": {"value": "{character}"}}}]}}},
        {"name": "strict", "options": {"anki": {"tags": [], "checkForDuplicates": true, "duplicateBehavior": "prevent",
            "duplicateScope": "deck", "cardFormats": [
            {"name": "Expression", "type": "term", "deck": "Mining", "model": "Basic", "fields": {"Front": {"value": "{expression}"}}}]}}}
    ]}}"""

    @Test
    fun importsTermCardFormatsFromYomitanSettings() {
        val formats = YomitanSettings.cardFormats(export)
        assertEquals(listOf("Default: Expression", "strict: Expression"), formats.map { it.first })

        val lapis = formats[0].second
        assertEquals("Lapis", lapis.deck)
        // Android keeps the export's field order; the JSON library in unit tests doesn't, hence a set
        assertEquals(setOf("Expression", "Sentence", "PAGraphs"), lapis.fields.keys)
        assertEquals("{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}", lapis.fields["Sentence"])
        assertEquals(listOf("yomitan"), lapis.tags)
        assertFalse(lapis.preventDuplicates)
        assertEquals(listOf("jpmn-pitch-accent-graphs"), lapis.unsupported)

        val strict = formats[1].second
        assertTrue(strict.preventDuplicates)
        assertEquals("deck", strict.duplicateScope)
    }

    @Test
    fun survivesStorage() {
        val format = YomitanSettings.cardFormats(export).first().second
        assertEquals(format, CardFormat.fromJson(format.toJson()))
    }

    @Test
    fun guessesTemplatesFromFieldNames() {
        val lapis = listOf("Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "Sentence",
            "SentenceFurigana", "SentenceAudio", "Picture", "Glossary", "PitchPosition", "PitchCategories", "FreqSort", "MiscInfo")
        assertEquals(
            listOf("{expression}", "{furigana-plain}", "{reading}", "{audio}", "{sentence}", "{sentence-furigana}", "", "{screenshot}",
                "{glossary}", "{pitch-accent-positions}", "{pitch-accent-categories}", "{frequency-harmonic-rank}", ""),
            CardFormat.guessTemplates(lapis).values.toList(),
        )
    }
}
