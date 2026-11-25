package io.github.kvth.hiroi.anki

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The dictionaries' stylesheets [StyleDedup] took out of cards, one per dictionary (the latest),
 * for the note type's Styling: added by hand (AnkiConnect Android can't change it), or by Hiroi
 * with "update the note type" on ([NoteTypeStyles.apply]). Kept in a file, with whether they
 * changed since they were last copied, and which note types have them.
 */
class NoteTypeStyles(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "note-type-styles.json"))

    /** The stylesheets by dictionary, in the order they came. */
    val sheets: Map<String, String>
        get() = synchronized(LOCK) { read().sheets }

    /** Whether [section] changed since [copied] was last called (and there is one). */
    val changedSinceCopied: Boolean
        get() = synchronized(LOCK) { read().let { it.sheets.isNotEmpty() && it.copied != StyleDedup.section(it.sheets).hashCode() } }

    /** Whether [model]'s Styling may lack some of the sheets: Hiroi hasn't put the latest there yet. */
    fun needsApplying(model: String): Boolean = synchronized(LOCK) {
        val state = read()
        state.sheets.isNotEmpty() && state.applied[model] != StyleDedup.section(state.sheets).hashCode()
    }

    /**
     * Puts the sheets into [model]'s Styling through [anki], merged with those already there
     * ([StyleDedup.withSheets]); written only if that changes it. Whether it did; null without sheets.
     */
    fun apply(anki: AnkiBackend, model: String): Boolean? {
        val sheets = sheets.takeIf { it.isNotEmpty() } ?: return null
        val css = anki.modelStyling(model)
        val updated = StyleDedup.withSheets(css, sheets)
        if (updated != css) anki.updateModelStyling(model, updated)
        synchronized(LOCK) {
            val state = read()
            write(state.copy(applied = state.applied + (model to StyleDedup.section(sheets).hashCode())))
        }
        return updated != css
    }

    /** The section to put at the end of the note type's Styling; null before any card had stylesheets. */
    fun section(): String? = sheets.takeIf { it.isNotEmpty() }?.let(StyleDedup::section)

    /** Keeps [moved] (stylesheets from a card); true if that changed [section]. */
    fun add(moved: List<String>): Boolean = synchronized(LOCK) {
        if (moved.isEmpty()) return false
        val state = read()
        val updated = LinkedHashMap(state.sheets)
        for (sheet in moved) updated[StyleDedup.sheetKey(sheet)] = sheet
        if (updated == state.sheets) return false
        write(state.copy(sheets = updated))
        true
    }

    /** The section was copied (or shared) to be added to the note type. */
    fun copied() = synchronized(LOCK) {
        val state = read()
        write(state.copy(copied = StyleDedup.section(state.sheets).hashCode()))
    }

    /** [copied] and [applied] (by note type) are hashes of the section then. */
    private data class State(val sheets: Map<String, String> = emptyMap(), val copied: Int? = null, val applied: Map<String, Int> = emptyMap())

    private fun read(): State {
        if (!file.exists()) return State()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return State()
        val sheets = json.optJSONObject("sheets") ?: JSONObject()
        val applied = json.optJSONObject("applied") ?: JSONObject()
        return State(
            sheets.keys().asSequence().associateWithTo(LinkedHashMap()) { sheets.getString(it) },
            if (json.has("copied")) json.getInt("copied") else null,
            applied.keys().asSequence().associateWith { applied.getInt(it) },
        )
    }

    private fun write(state: State) {
        val json = JSONObject()
            .put("sheets", JSONObject().also { o -> state.sheets.forEach { (k, v) -> o.put(k, v) } })
            .put("applied", JSONObject().also { o -> state.applied.forEach { (k, v) -> o.put(k, v) } })
        if (state.copied != null) json.put("copied", state.copied)
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
    }

    private companion object {
        val LOCK = Any()
    }
}
