package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.FrequencySort
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import io.github.kvth.hiroi.japanese.Deinflector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardCreatorTest {
    private val requests = mutableListOf<JSONObject>()

    /** The note type's Styling, and the error AnkiConnect gives for it (AnkiConnect Android's) */
    private var styling = ".card { }"
    private var stylingError: String? = null

    /** Anki with one note, 食べる (id 7), whose fields are Word, Meaning and Notes. */
    private val anki = AnkiConnect(null) { body ->
        val request = JSONObject(body).also { requests += it }
        val params = request.getJSONObject("params")
        val result: Any = when (request.getString("action")) {
            "canAddNotes" -> {
                val notes = params.getJSONArray("notes")
                JSONArray((0 until notes.length()).map { notes.getJSONObject(it).getJSONObject("fields").optString("Word") != "食べる" })
            }
            "findNotes" -> JSONArray(if ("Word:食べる" in params.getString("query")) listOf(7L) else emptyList())
            "notesInfo" -> JSONArray().put(
                JSONObject().put("noteId", 7).put(
                    "fields",
                    JSONObject()
                        .put("Word", JSONObject().put("value", "食べる").put("order", 0))
                        .put("Meaning", JSONObject().put("value", "old meaning").put("order", 1))
                        .put("Notes", JSONObject().put("value", "").put("order", 2)),
                ),
            )
            "updateNoteFields" -> JSONObject.NULL
            "addNote" -> 42
            "modelStyling" -> stylingError?.let { return@AnkiConnect JSONObject().put("result", JSONObject.NULL).put("error", it).toString() }
                ?: JSONObject().put("css", styling)
            "updateModelStyling" -> JSONObject.NULL.also { styling = params.getJSONObject("model").getString("css") }
            else -> error("unexpected action")
        }
        JSONObject().put("result", result).put("error", JSONObject.NULL).toString()
    }

    private val format = CardFormat(
        deck = "Mining::Core", model = "Vocab",
        fields = linkedMapOf("Word" to "{expression}", "Meaning" to "new meaning", "Notes" to "{selection}"),
    )

    /** What each pass of the renderer got: requirements and media (both null in the first pass). */
    private val passes = mutableListOf<Pair<JSONArray?, JSONObject?>>()

    /**
     * Stands in for Yomitan's templates: {expression} is the word; {selection} needs the
     * selection and sentence furigana, as Yomitan's markers report such needs, and shows what
     * was injected for them in the second pass.
     */
    private fun renderer(format: CardFormat) = NoteRenderer { data, requirements, media ->
        passes += requirements to media
        val needs = JSONArray()
        val fields = format.fields.mapValues { (_, template) ->
            if ("{selection}" in template) {
                needs.put(JSONObject().put("type", "popupSelectionText")).put(JSONObject().put("type", "textFurigana").put("text", data.text))
            }
            template.replace("{expression}", data.term.expression).replace("{selection}", media?.optString("selectionText").orEmpty())
        }
        RenderedNote(fields, listOf("Template render error for {x}").filter { "{x}" in format.fields.values.joinToString() }, needs)
    }

    private fun creator(format: CardFormat = this.format) = CardCreator(format, anki, renderer(format))

    private fun term(word: String) = TermGroup(word, "", word, emptyList(), emptyList(), emptyList())

    private fun card(word: String) =
        CardData(term(word), word, 0, word.length, parse = { listOf(listOf(Furigana.Segment(it, "よみ"))) }, selection = "選択")

    private fun query() = requests.last { it.getString("action") == "findNotes" }.getJSONObject("params").getString("query")

    @Test
    fun findsTheNotesEachTermWouldDuplicate() {
        assertEquals(listOf(listOf(7L), emptyList()), creator().duplicates(listOf(term("食べる"), term("届く"))))
        // One canAddNotes for all, with the headword in the first field as Yomitan checks it,
        // then a search only for the duplicate: in the note type, anywhere in the collection
        assertEquals(listOf("canAddNotes", "findNotes"), requests.map { it.getString("action") })
        assertEquals("\"note:Vocab\" \"Word:食べる\"", query())
        assertTrue("no rendering for the check", passes.isEmpty())
    }

    @Test
    fun searchesInTheDuplicateScope() {
        creator(format.copy(duplicateScope = "deck")).duplicates(listOf(term("食べる")))
        assertEquals("\"deck:Mining::Core\" \"note:Vocab\" \"Word:食べる\"", query())
        creator(format.copy(duplicateScope = "deck-root", checkAllModels = true)).duplicates(listOf(term("食べる")))
        assertEquals("\"deck:Mining\" \"Word:食べる\"", query())
        val options = requests.last { it.getString("action") == "canAddNotes" }
            .getJSONObject("params").getJSONArray("notes").getJSONObject(0).getJSONObject("options")
        assertEquals("deck", options.getString("duplicateScope"))
        assertEquals("Mining", options.getJSONObject("duplicateScopeOptions").getString("deckName"))
        assertTrue(options.getJSONObject("duplicateScopeOptions").getBoolean("checkChildren"))
    }

    @Test
    fun checksNothingWhenTurnedOff() {
        assertEquals(listOf(emptyList<Long>()), creator(format.copy(checkForDuplicates = false)).duplicates(listOf(term("食べる"))))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun rendersTwiceWithTheMediaTheFieldsNeed() {
        val added = creator().add(card("届く"), null)
        assertEquals(42, added.noteId)
        // First pass without media; the second gets what the first asked for
        assertEquals(2, passes.size)
        assertNull(passes[0].second)
        val media = passes[1].second!!
        assertEquals("選択", media.getString("selectionText"))
        val segment = media.getJSONObject("parsedText").getJSONArray("届く").getJSONArray(0).getJSONObject(0)
        assertEquals(mapOf("text" to "届く", "reading" to "よみ"), segment.toMap())
        val fields = requests.last().getJSONObject("params").getJSONObject("note").getJSONObject("fields")
        assertEquals("選択", fields.getString("Notes"))
    }

    @Test
    fun rendersOnceWithoutMedia() {
        creator(format.copy(fields = linkedMapOf("Word" to "{expression}", "Meaning" to "{x}"))).add(card("届く"), null).let {
            assertEquals(listOf("Template render error for {x}"), it.errors)
        }
        assertEquals(1, passes.size)
    }

    @Test
    fun overwritesFieldByFieldWithTheirModes() {
        // coalesce, Yomitan's default ("Fill if empty"): only the empty Notes gets the new value
        creator().overwrite(7, card("食べる"), null)
        val update = requests.last().getJSONObject("params").getJSONObject("note")
        assertEquals("updateNoteFields", requests.last().getString("action"))
        assertEquals(7, update.getLong("id"))
        assertEquals(mapOf("Notes" to "選択"), update.getJSONObject("fields").toMap())

        creator(format.copy(overwriteModes = mapOf("Meaning" to "append", "Notes" to "skip"))).overwrite(7, card("食べる"), null)
        assertEquals(
            mapOf("Meaning" to "old meaningnew meaning"),
            requests.last().getJSONObject("params").getJSONObject("note").getJSONObject("fields").toMap(),
        )
    }

    @Test
    fun sentenceTranslationOnlyWhenAFieldHasIt() {
        val asked = mutableListOf<String>()
        val text = "昨日は晴れ。明日は雨が降る。"
        val data = CardData(term("雨"), text, 8, 9, translate = { asked += it; "It will <rain> {tomorrow}." })
        val (fields, error) = hiroiMarkers(mapOf("Word" to "{expression}", "Back" to "{sentence}<br>{sentence-translation}"), data)
        // The sentence of the word, translated once for both passes, as HTML with no marker left in it
        assertEquals(listOf("明日は雨が降る。"), asked)
        assertEquals("{sentence}<br>It will &lt;rain&gt; &#123;tomorrow}.", fields["Back"])
        assertEquals("{expression}", fields["Word"])
        assertNull(error)
        hiroiMarkers(mapOf("Back" to "{sentence-translation}"), data)
        assertEquals(1, asked.size)

        // Not asked for: no translation
        val untouched = CardData(term("雨"), text, 8, 9, translate = { error("not asked for") })
        assertEquals(mapOf("Word" to "{expression}") to null, hiroiMarkers(mapOf("Word" to "{expression}"), untouched))

        // Failed: empty, and why
        val failing = CardData(term("雨"), text, 8, 9, translate = { throw IllegalStateException("offline") })
        val (empty, reason) = hiroiMarkers(mapOf("Back" to "[{sentence-translation}]"), failing)
        assertEquals("[]", empty["Back"])
        assertEquals("sentence-translation: offline", reason)
    }

    @Test
    fun fillsHiroisOwnMarkers() {
        val term = TermGroup(
            "食べる", "たべる", "食べた", listOf("-た", "no such step"), emptyList(),
            listOf(TermMeta("食べる", "freq", "[300]", "JPDB"), TermMeta("食べる", "freq", "[900]", "JPDB"), TermMeta("食べる", "freq", "[5]", "Other")),
            kanji = listOf(
                KanjiEntry("食", "", "", "", emptyList(), "Empty"),
                KanjiEntry("食", "ショク ジキ", "く.う た.べる", "", listOf("eat", "food"), "KANJIDIC"),
                KanjiEntry("食", "ショク", "", "", listOf("食べる"), "Kanken"),
            ),
        )
        val data = CardData(term, "昨日、{ラーメン}を\n食べた。", 5, 8, frequencySort = FrequencySort("JPDB", ascending = true))
        data.screenshotRegion = "hiroi_screenshot_region_1.png"
        val fields = mapOf(
            "Kanji" to "{kanji-meanings}|{kanji-readings}",
            "Conjugation" to "{conjugation-description}",
            "Context" to "{text-block}",
            "Rank" to "{frequency-sort-rank}",
            "Picture" to "{screenshot-region}",
            "Word" to "{expression}",
        )
        val (filled, error) = hiroiMarkers(fields, data)
        assertNull(error)
        // The first kanji dictionary that has something for the kanji
        assertEquals("食: eat, food|食: On ショク、ジキ · Kun く.う、た.べる", filled["Kanji"])
        assertEquals("-た: " + Deinflector.description("-た")!!.replace("\n", " ") + "<br>no such step", filled["Conjugation"])
        assertEquals("昨日、&#123;ラーメン}を<br>食べた。", filled["Context"])
        // Ranks: the lowest of the sorting dictionary's; occurrence counts: the highest
        assertEquals("300", filled["Rank"])
        assertEquals("900", hiroiMarkers(fields, CardData(term, "", 0, 0, frequencySort = FrequencySort("JPDB", ascending = false))).first["Rank"])
        assertEquals("""<img src="hiroi_screenshot_region_1.png">""", filled["Picture"])
        assertEquals("{expression}", filled["Word"])
        // Without a sorting dictionary or a region: empty
        val bare = hiroiMarkers(fields, CardData(term, "", 0, 0)).first
        assertEquals(listOf("", ""), listOf(bare["Rank"], bare["Picture"]))
    }

    @Test
    fun putsTheDictionaryStylesIntoTheNoteTypeIfOn() {
        val sheet = """[data-dictionary="J"] b { color: red; }"""
        val styled = format.copy(fields = linkedMapOf("Word" to "{expression}", "Meaning" to "<style>$sheet</style>m", "Notes" to ""))
        val styles = NoteTypeStyles(File.createTempFile("styles", ".json").apply { delete() })
        fun creator(update: Boolean) = CardCreator(styled, anki, renderer(styled), styles = styles, updateNoteType = update)

        // Off: the sheet is kept for the note type, which isn't touched
        val kept = creator(false).add(card("届く"), null)
        assertTrue(kept.stylesChanged)
        assertFalse(kept.noteTypeUpdated)
        assertTrue(requests.none { it.getString("action").endsWith("Styling") })

        // On: the note type gets what it lacks, added to its Styling, and only once
        val added = creator(true).add(card("届く"), null)
        assertEquals(42, added.noteId)
        assertTrue(added.noteTypeUpdated)
        assertEquals(StyleDedup.withSheets(".card { }", mapOf("J" to sheet)), styling)
        assertEquals("Vocab", requests.last().getJSONObject("params").getJSONObject("model").getString("name"))
        requests.clear()
        assertFalse(creator(true).add(card("届く"), null).noteTypeUpdated)
        assertTrue(requests.none { it.getString("action").endsWith("Styling") })

        // Where Anki can't change it, the card is added all the same, with why
        stylingError = "unsupported action"
        val failed = CardCreator(styled, anki, renderer(styled), styles = NoteTypeStyles(File.createTempFile("styles", ".json").apply { delete() }), updateNoteType = true)
            .add(card("届く"), null)
        assertEquals(42, failed.noteId)
        assertEquals("unsupported action", failed.noteTypeError)
    }
}
