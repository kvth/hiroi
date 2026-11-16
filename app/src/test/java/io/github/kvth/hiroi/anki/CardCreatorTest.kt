package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
                        .put("Notes", JSONObject().put("value", "my notes").put("order", 2)),
                ),
            )
            "updateNoteFields" -> JSONObject.NULL
            else -> error("unexpected action")
        }
        JSONObject().put("result", result).put("error", JSONObject.NULL).toString()
    }

    private val format = CardFormat(
        deck = "Mining::Core", model = "Vocab",
        fields = linkedMapOf("Word" to "{expression}", "Meaning" to "new meaning", "Notes" to ""),
    )

    private fun card(word: String) = CardData(TermGroup(word, "", word, emptyList(), emptyList(), emptyList()), word, 0, word.length)

    private fun query() = requests.last { it.getString("action") == "findNotes" }.getJSONObject("params").getString("query")

    @Test
    fun findsTheNotesEachCardWouldDuplicate() {
        assertEquals(listOf(listOf(7L), emptyList()), CardCreator(format, anki).duplicates(listOf(card("食べる"), card("届く"))))
        // One canAddNotes for all, a search only for the duplicate: in the note type, anywhere in the collection
        assertEquals(listOf("canAddNotes", "findNotes"), requests.map { it.getString("action") })
        assertEquals("\"note:Vocab\" \"Word:食べる\"", query())
    }

    @Test
    fun searchesInTheDuplicateScope() {
        CardCreator(format.copy(duplicateScope = "deck"), anki).duplicates(listOf(card("食べる")))
        assertEquals("\"deck:Mining::Core\" \"note:Vocab\" \"Word:食べる\"", query())
        CardCreator(format.copy(duplicateScope = "deck-root", checkAllModels = true), anki).duplicates(listOf(card("食べる")))
        assertEquals("\"deck:Mining\" \"Word:食べる\"", query())
        val options = requests.last { it.getString("action") == "canAddNotes" }
            .getJSONObject("params").getJSONArray("notes").getJSONObject(0).getJSONObject("options")
        assertEquals("deck", options.getString("duplicateScope"))
        assertEquals("Mining", options.getJSONObject("duplicateScopeOptions").getString("deckName"))
        assertTrue(options.getJSONObject("duplicateScopeOptions").getBoolean("checkChildren"))
    }

    @Test
    fun checksNothingWhenTurnedOff() {
        assertEquals(listOf(emptyList<Long>()), CardCreator(format.copy(checkForDuplicates = false), anki).duplicates(listOf(card("食べる"))))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun overwritesFieldByFieldWithTheirModes() {
        CardCreator(format, anki).overwrite(7, card("食べる"), null)
        // coalesce: the new meaning replaces the old one; the empty Notes keeps "my notes"; Word is unchanged
        val update = requests.last().getJSONObject("params").getJSONObject("note")
        assertEquals("updateNoteFields", requests.last().getString("action"))
        assertEquals(7, update.getLong("id"))
        assertEquals(mapOf("Meaning" to "new meaning"), update.getJSONObject("fields").toMap())

        CardCreator(format.copy(overwriteModes = mapOf("Meaning" to "append", "Notes" to "overwrite")), anki).overwrite(7, card("食べる"), null)
        assertEquals(
            mapOf("Meaning" to "old meaningnew meaning", "Notes" to ""),
            requests.last().getJSONObject("params").getJSONObject("note").getJSONObject("fields").toMap(),
        )
    }
}
