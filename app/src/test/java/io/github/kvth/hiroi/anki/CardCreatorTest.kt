package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCreatorTest {
    private val requests = mutableListOf<JSONObject>()

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
}
