package io.github.kvth.hiroi.anki

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnkiConnectTest {
    private val requests = mutableListOf<JSONObject>()

    /** Answers like AnkiConnect, from a deck with these Word fields. */
    private fun anki(vararg words: String) = AnkiConnect("secret") { body ->
        val request = JSONObject(body).also { requests += it }
        val result: Any = when (request.getString("action")) {
            "findNotes" -> JSONArray(words.indices.map { it + 1L })
            "notesInfo" -> JSONArray().also { notes ->
                val ids = request.getJSONObject("params").getJSONArray("notes")
                for (i in 0 until ids.length()) {
                    val id = ids.getLong(i)
                    val fields = JSONObject()
                        .put("Word", JSONObject().put("value", words[(id - 1).toInt()]).put("order", 0))
                        .put("Meaning", JSONObject().put("value", "…").put("order", 1))
                    notes.put(JSONObject().put("noteId", id).put("fields", fields))
                }
            }
            "deckNames" -> JSONArray(listOf("Japanese", "Default"))
            else -> error("unexpected action")
        }
        JSONObject().put("result", result).put("error", JSONObject.NULL).toString()
    }

    @Test
    fun findsWhichWordsHaveNotesIgnoringMarkup() {
        val found = anki("<b>食べる</b>", "使う[つかう]", "<ruby>友達<rt>ともだち</rt></ruby>", "食べ物")
            .notesInDeck("Japanese::Core", "Word", listOf("食べる", "使う", "友達", "届く"))
        assertEquals(mapOf("食べる" to listOf(1L), "使う" to listOf(2L), "友達" to listOf(3L)), found)

        val find = requests.first { it.getString("action") == "findNotes" }
        assertEquals(6, find.getInt("version"))
        assertEquals("secret", find.getString("key"))
        assertEquals(
            "\"deck:Japanese::Core\" (\"Word:*食べる*\" OR \"Word:*使う*\" OR \"Word:*友達*\" OR \"Word:*届く*\")",
            find.getJSONObject("params").getString("query"),
        )
    }

    @Test
    fun escapesSearchSyntax() {
        assertEquals("""a\*b\_c\"d\\e""", AnkiConnect.escape("""a*b_c"d\e"""))
        assertEquals("日本語", AnkiConnect.fieldText(" 日本[にほん] 語[ご]"))
    }

    @Test
    fun listsDecksAndFields() {
        val anki = anki("食べる")
        assertEquals(listOf("Default", "Japanese"), anki.deckNames())
        assertEquals(listOf("Word", "Meaning"), anki.fieldNames("Japanese"))
    }

    @Test
    fun reportsAnkiConnectErrors() {
        val anki = AnkiConnect(null) { """{"result": null, "error": "valid api key must be provided"}""" }
        assertThrows(AnkiException::class.java) { anki.deckNames() }
    }
}
