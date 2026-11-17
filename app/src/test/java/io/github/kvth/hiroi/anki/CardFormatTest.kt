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
        assertEquals(listOf("Default: Expression", "strict: Expression"), formats.map { it.name })

        val lapis = formats[0].format
        assertEquals("Lapis", lapis.deck)
        // Android keeps the export's field order; the JSON library in unit tests doesn't, hence a set
        assertEquals(setOf("Expression", "Sentence", "PAGraphs"), lapis.fields.keys)
        assertEquals("{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}", lapis.fields["Sentence"])
        assertEquals(listOf("yomitan"), lapis.tags)
        assertFalse(lapis.preventDuplicates)
        // Markers the templates don't define (here: JP Mining Note's, without its templates)
        val templates = listOf("expression", "cloze-prefix", "cloze-body", "cloze-suffix").joinToString("") { "{{#*inline \"$it\"}}{{/inline}}" }
        assertEquals(listOf("jpmn-pitch-accent-graphs"), AnkiTemplates.unknownMarkers(lapis, templates, emptyList()))

        val strict = formats[1].format
        assertTrue(strict.preventDuplicates)
        assertEquals("deck", strict.duplicateScope)
    }

    @Test
    fun importsYomitansDuplicateOptions() {
        val formats = YomitanSettings.cardFormats(export)
        val lapis = formats[0].format
        assertTrue(lapis.checkForDuplicates)
        assertEquals("new", lapis.duplicateBehavior)
        assertEquals("coalesce", lapis.overwriteMode("Sentence"))
        assertEquals("prevent", formats[1].format.duplicateBehavior)
        val overwrite = YomitanSettings.cardFormats(
            export.replace("\"duplicateBehavior\": \"new\"", "\"duplicateBehavior\": \"overwrite\", \"duplicateScopeCheckAllModels\": true")
                .replace("\"overwriteMode\": \"coalesce\"}", "\"overwriteMode\": \"append\"}"),
        ).first().format
        assertEquals("overwrite", overwrite.duplicateBehavior)
        assertTrue(overwrite.checkAllModels)
        assertTrue(overwrite.overwriteModes.values.all { it == "append" })
    }

    @Test
    fun importsTheProfilesTemplatesAndLayout() {
        val custom = YomitanSettings.cardFormats(
            export.replace("\"tags\": [\"yomitan\"],", "\"tags\": [\"yomitan\"], \"fieldTemplates\": \"{{#*inline \\\"mine\\\"}}x{{/inline}}\",")
                .replace("{\"name\": \"Default\", \"options\": {", "{\"name\": \"Default\", \"options\": {\"general\": {\"glossaryLayoutMode\": \"compact-popup-anki\", \"compactTags\": true}, "),
        ).first()
        assertEquals("{{#*inline \"mine\"}}x{{/inline}}", custom.templates)
        assertTrue(custom.compactGlossaries)
        assertTrue(custom.compactTags)
        val plain = YomitanSettings.cardFormats(export).first()
        assertEquals(null, plain.templates)
        assertFalse(plain.compactGlossaries)
    }

    @Test
    fun readsFormatsSavedBeforeTheDuplicateOptions() {
        val old = CardFormat.fromJson("""{"deck":"D","model":"M","fields":{"Front":"{expression}"},"tags":[],"preventDuplicates":true,"duplicateScope":"deck"}""")
        assertEquals("prevent", old.duplicateBehavior)
        assertTrue(old.checkForDuplicates)
        assertEquals("deck", old.duplicateScope)
        assertEquals("new", CardFormat.fromJson("""{"deck":"D","model":"M","fields":{},"preventDuplicates":false}""").duplicateBehavior)
    }

    @Test
    fun overwritesLikeYomitan() {
        assertEquals("new", CardFormat.overwrite("overwrite", "old", "new"))
        assertEquals("", CardFormat.overwrite("overwrite", "old", ""))
        // coalesce ("Fill if empty", Yomitan's default) keeps what's there; coalesce-new ("Overwrite if available") prefers the new value
        assertEquals("old", CardFormat.overwrite("coalesce", "old", "new"))
        assertEquals("new", CardFormat.overwrite("coalesce", "", "new"))
        assertEquals("new", CardFormat.overwrite("coalesce-new", "old", "new"))
        assertEquals("old", CardFormat.overwrite("coalesce-new", "old", ""))
        assertEquals("oldnew", CardFormat.overwrite("append", "old", "new"))
        assertEquals("newold", CardFormat.overwrite("prepend", "old", "new"))
        assertEquals("old", CardFormat.overwrite("skip", "old", "new"))
    }

    @Test
    fun survivesStorage() {
        val format = YomitanSettings.cardFormats(export).first().format
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
