package io.github.kvth.hiroi.marks

import io.github.kvth.hiroi.anki.AnkiBackend
import io.github.kvth.hiroi.anki.AnkiConnect
import io.github.kvth.hiroi.anki.AnkiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkSyncTest {
    /** A note: its Word field, whether it's new, and when it was last edited. */
    private class FakeNote(var word: String, var new: Boolean, var mod: Long, val glossary: String = "")

    private val notes = linkedMapOf(
        1L to FakeNote("食べる", new = false, mod = 10),
        2L to FakeNote("使う[つかう]", new = true, mod = 10),
        3L to FakeNote("<b>友達</b>", new = false, mod = 10, glossary = "forms: 友だち"),
    )
    private var modTimes = true
    private val actions = mutableListOf<String>()
    private val read = mutableListOf<Long>()

    /** Understands the rule searches below: "is:new", "-is:new" and "bad:" (an invalid search). */
    private val client = AnkiConnect(null) { body ->
        val request = JSONObject(body)
        val action = request.getString("action")
        actions += action
        val params = request.getJSONObject("params")
        val result: Any = when (action) {
            "findNotes" -> {
                val query = params.getString("query")
                if ("bad:" in query) return@AnkiConnect JSONObject().put("result", JSONObject.NULL).put("error", "invalid search").toString()
                JSONArray(notes.filter { (_, n) -> if ("-is:new" in query) !n.new else if ("is:new" in query) n.new else true }.keys.toList())
            }
            "notesModTime" -> {
                if (!modTimes) return@AnkiConnect JSONObject().put("result", JSONObject.NULL).put("error", "unsupported action").toString()
                val ids = params.getJSONArray("notes")
                JSONArray((0 until ids.length()).map { JSONObject().put("noteId", ids.getLong(it)).put("mod", notes.getValue(ids.getLong(it)).mod) })
            }
            "notesInfo" -> {
                val ids = params.getJSONArray("notes")
                JSONArray((0 until ids.length()).map { i ->
                    val id = ids.getLong(i)
                    read += id
                    val n = notes.getValue(id)
                    val fields = JSONObject()
                        .put("Word", JSONObject().put("value", n.word).put("order", 0))
                        .put("Glossary", JSONObject().put("value", n.glossary).put("order", 1))
                    JSONObject().put("noteId", id).put("modelName", "Vocab").put("tags", JSONArray()).put("fields", fields).put("mod", n.mod)
                })
            }
            else -> error("unexpected action $action")
        }
        JSONObject().put("result", result).put("error", JSONObject.NULL).toString()
    }

    private val rules = listOf(
        MarkRule("learning", "Learning", "-is:new", "#ffa600"),
        MarkRule("new", "New", "is:new", "#408cff"),
    )
    private val config = MarkSync.Config("\"deck:Japanese\"", rules, listOf("Word"), "", "http://127.0.0.1:8765")

    private fun sync(config: MarkSync.Config = this.config, cache: MarkSync.NoteCache? = null, now: Long = 1000, script: (String, List<AnkiBackend.Note>) -> List<ScriptResult> = { _, _ -> error("no script") }) =
        MarkSync(client, script).run(config, cache, now)

    @Test
    fun givesEachWordTheRulesItsNotesMatch() {
        val result = sync()
        assertEquals(
            mapOf(
                "食べる" to listOf(RuleNote("learning", 1)),
                "友達" to listOf(RuleNote("learning", 3)),
                "使う" to listOf(RuleNote("new", 2)),
            ),
            result.dict,
        )
        assertEquals(3, result.info.notes)
        assertEquals(3, result.info.words)
        assertEquals(3, result.info.read)
    }

    @Test
    fun readsOnlyNotesThatAreNewOrEdited() {
        val first = sync()
        read.clear()
        notes.getValue(2).apply { word = "使用"; mod = 20 }
        val second = sync(cache = first.cache, now = 2000)
        assertEquals(listOf(2L), read)
        assertEquals(1, second.info.read)
        assertTrue("使用" in second.dict)
        // Rule searches run every time: the card moved from new to learning.
        notes.getValue(2).new = false
        assertEquals(listOf(RuleNote("learning", 2)), sync(cache = second.cache, now = 3000).dict["使用"])
    }

    @Test
    fun withoutEditTimesRereadsEverythingOnceADay() {
        modTimes = false
        val first = sync()
        read.clear()
        sync(cache = first.cache, now = 2000)
        assertEquals(emptyList<Long>(), read)
        sync(cache = first.cache, now = 1000 + 25 * 60 * 60 * 1000L)
        assertEquals(listOf(1L, 3L, 2L), read)
    }

    @Test
    fun changedFieldsInvalidateTheCache() {
        val first = sync()
        read.clear()
        sync(config.copy(fields = listOf("Word", "Glossary")), first.cache, now = 2000)
        assertEquals(3, read.size)
    }

    @Test
    fun addsTheFieldScriptsWords() {
        val result = sync(config.copy(fieldScript = "return ...")) { _, batch ->
            batch.map { note -> ScriptResult(words = listOf(note.fields.getValue("Glossary").removePrefix("forms: "))) }
        }
        assertEquals(listOf(RuleNote("learning", 3)), result.dict["友だち"])
    }

    @Test
    fun aFailingFieldScriptStopsTheSync() {
        val error = assertThrows(AnkiException::class.java) {
            sync(config.copy(fieldScript = "throw 1")) { _, batch -> batch.map { ScriptResult(error = "boom") } }
        }
        assertEquals("Field script failed on note 1: boom", error.message)
    }

    @Test
    fun namesTheRuleWithAnInvalidSearch() {
        val bad = config.copy(rules = rules + MarkRule("x", "Broken", "bad:", "#000000"))
        assertEquals("Rule \"Broken\": invalid search", assertThrows(AnkiException::class.java) { sync(bad) }.message)
        val checked = MarkSync(client) { _, _ -> emptyList() }.checkRules(bad.baseQuery, bad.rules)
        assertEquals(2, checked.getValue("learning").getOrThrow())
        assertTrue(checked.getValue("x").isFailure)
    }

    @Test
    fun usesTheFirstFieldWhenNoneIsChosen() {
        val note = AnkiBackend.Note(1, linkedMapOf("Expression" to "犬", "Meaning" to "dog"))
        assertEquals(listOf("犬"), MarkSync.noteWords(note, emptyList()))
        assertEquals(listOf("犬"), MarkSync.noteWords(note, listOf("Missing")))
    }

    @Test
    fun noteCacheSurvivesJson() {
        val cache = sync().cache
        assertEquals(cache, MarkSync.NoteCache.fromJson(JSONObject(cache.toJson().toString())))
    }

    @Test
    fun ruleQueriesCombineTheBaseFilter() {
        assertEquals("(deck:A) (is:new)", MarkSettings.ruleQuery("deck:A", "is:new"))
        assertEquals("(deck:A)", MarkSettings.ruleQuery("deck:A", " "))
        assertNull(MarkSettings.ruleQuery("", ""))
        assertEquals("((deck:A) (is:new)) OR ((deck:A))", MarkSettings.unionQuery("deck:A", listOf(rules[1], rules[0].copy(query = ""))))
    }
}
