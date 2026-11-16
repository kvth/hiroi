package io.github.kvth.hiroi.marks

import io.github.kvth.hiroi.anki.AnkiConnect
import io.github.kvth.hiroi.anki.AnkiException
import org.json.JSONArray
import org.json.JSONObject

/** What a field script gave for one note: [words], or the [error] it threw. */
data class ScriptResult(val words: List<String>? = null, val error: String? = null)

/** The note id that a word got its colour from, by rule. */
data class RuleNote(val ruleId: String, val noteId: Long)

data class SyncInfo(val notes: Int, val words: Int, val read: Int, val time: Long, val signature: String) {
    fun toJson(): JSONObject = JSONObject().put("notes", notes).put("words", words).put("read", read).put("time", time).put("signature", signature)

    companion object {
        fun fromJson(o: JSONObject) = SyncInfo(o.getInt("notes"), o.getInt("words"), o.optInt("read"), o.getLong("time"), o.getString("signature"))
    }
}

/**
 * Reads the words to mark from Anki, as AnkiMark's sync does: every rule's search runs each time
 * (cards change state with every review), while note words are cached, so only notes that are new
 * or edited since the last sync are read. Without notesModTime (AnkiConnect Android) only new
 * notes are read, plus everything once a day.
 */
class MarkSync(
    private val client: AnkiConnect,
    /** Runs the field script on a batch of notes; one result per note. */
    private val runScript: (code: String, notes: List<AnkiConnect.Note>) -> List<ScriptResult>,
) {
    data class Config(
        val baseQuery: String,
        val rules: List<MarkRule>,
        val fields: List<String>,
        val fieldScript: String,
        val ankiUrl: String,
        val notesBatch: Int = 100,
    )

    data class CachedNote(val mod: Long, val words: List<String>)

    data class NoteCache(val source: String, val fullAt: Long, val notes: Map<Long, CachedNote>) {
        fun toJson(): JSONObject {
            val notes = JSONObject()
            for ((id, note) in this.notes) notes.put(id.toString(), JSONObject().put("mod", note.mod).put("words", JSONArray(note.words)))
            return JSONObject().put("source", source).put("fullAt", fullAt).put("notes", notes)
        }

        companion object {
            fun fromJson(o: JSONObject): NoteCache {
                val notes = o.getJSONObject("notes")
                val map = HashMap<Long, CachedNote>()
                for (id in notes.keys()) {
                    val n = notes.getJSONObject(id)
                    val words = n.getJSONArray("words")
                    map[id.toLong()] = CachedNote(n.getLong("mod"), List(words.length()) { words.getString(it) })
                }
                return NoteCache(o.getString("source"), o.getLong("fullAt"), map)
            }
        }
    }

    /** [dict]: every word with one entry per rule it matched; all rules, so toggling and reordering needs no sync. */
    data class Result(val dict: Map<String, List<RuleNote>>, val info: SyncInfo, val cache: NoteCache)

    fun run(config: Config, cache: NoteCache?, now: Long = System.currentTimeMillis()): Result {
        val rulesByNote = LinkedHashMap<Long, MutableList<String>>()
        for (rule in config.rules) {
            for (id in findRuleNotes(config.baseQuery, rule)) rulesByNote.getOrPut(id) { mutableListOf() } += rule.id
        }
        val ids = rulesByNote.keys.toList()

        // A changed field list, field script or Anki address invalidates every cached note.
        val script = config.fieldScript.trim()
        val source = JSONArray().put(config.ankiUrl.trim()).put(JSONArray(config.fields.sorted())).apply { if (script.isNotEmpty()) put(script) }.toString()
        val mods = client.notesModTime(ids)
        val fresh = cache != null && cache.source == source && (mods != null || now - cache.fullAt < FULL_REREAD_MS)
        val cached = if (fresh) cache!!.notes else emptyMap()
        val fullAt = if (fresh) cache!!.fullAt else now
        val notes = HashMap<Long, CachedNote>()
        val stale = mutableListOf<Long>()
        for (id in ids) {
            val hit = cached[id]
            if (hit != null && (mods == null || hit.mod == mods[id])) notes[id] = hit else stale += id
        }
        val batch = config.notesBatch.coerceIn(10, 1000)
        for (chunk in stale.chunked(batch)) {
            val read = client.notesInfo(chunk, batch)
            val scripted = if (script.isNotEmpty()) runScript(script, read) else emptyList()
            read.forEachIndexed { i, note ->
                val r = scripted.getOrNull(i)
                if (r?.error != null) throw AnkiException("Field script failed on note ${note.id}: ${r.error}")
                notes[note.id] = CachedNote(mods?.get(note.id) ?: note.mod, noteWords(note, config.fields, r?.words.orEmpty()))
            }
        }

        val dict = LinkedHashMap<String, MutableList<RuleNote>>()
        for (id in ids) {
            for (word in notes[id]?.words.orEmpty()) {
                val entry = dict.getOrPut(word) { mutableListOf() }
                for (rule in rulesByNote.getValue(id)) if (entry.none { it.ruleId == rule }) entry += RuleNote(rule, id)
            }
        }
        val info = SyncInfo(
            ids.size, dict.size, stale.size, now,
            MarkSettings.syncSignature(config.baseQuery, config.rules, config.fields, config.fieldScript),
        )
        return Result(dict, info, NoteCache(source, fullAt, notes))
    }

    /** Note count per rule id, or the search's error, for the settings screen. */
    fun checkRules(base: String, rules: List<MarkRule>): Map<String, kotlin.Result<Int>> =
        rules.associate { rule -> rule.id to runCatching { findRuleNotes(base, rule).size } }

    private fun findRuleNotes(base: String, rule: MarkRule): List<Long> {
        val query = MarkSettings.ruleQuery(base, rule.query)
            ?: throw AnkiException("Rule \"${rule.name}\" has no search and there is no base filter.")
        return try {
            client.findNotes(query)
        } catch (e: AnkiException) {
            // Anki wraps parts of its messages in invisible bidi isolate marks.
            throw AnkiException("Rule \"${rule.name}\": ${e.message.orEmpty().replace(Regex("[⁦-⁩]"), "")}")
        }
    }

    companion object {
        private const val FULL_REREAD_MS = 24 * 60 * 60 * 1000L

        /** A note's words: its chosen fields (or, having none of them, its first field) plus the field script's. */
        fun noteWords(note: AnkiConnect.Note, fields: List<String>, scriptWords: List<String> = emptyList()): List<String> {
            var values = fields.mapNotNull { note.fields[it] }
            if (values.isEmpty()) values = listOfNotNull(note.fields.values.firstOrNull())
            return (values + scriptWords).flatMap(NoteWords::extractWords).distinct()
        }
    }
}
