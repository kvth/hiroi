package io.github.kvth.hiroi.anki

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The dictionaries' stylesheets [StyleDedup] took out of cards, one per dictionary (the latest),
 * to add to the note type's Styling by hand: AnkiConnect Android can't change it. Kept in a file,
 * with whether they changed since they were last copied.
 */
class NoteTypeStyles(context: Context) {
    private val file = File(context.filesDir, "note-type-styles.json")

    /** The stylesheets by dictionary, in the order they came. */
    val sheets: Map<String, String>
        get() = synchronized(LOCK) { read().first }

    /** Whether [section] changed since [copied] was last called (and there is one). */
    val changedSinceCopied: Boolean
        get() = synchronized(LOCK) { read().let { (sheets, copied) -> sheets.isNotEmpty() && copied != StyleDedup.section(sheets).hashCode() } }

    /** The section to put at the end of the note type's Styling; null before any card had stylesheets. */
    fun section(): String? = sheets.takeIf { it.isNotEmpty() }?.let(StyleDedup::section)

    /** Keeps [moved] (stylesheets from a card); true if that changed [section]. */
    fun add(moved: List<String>): Boolean = synchronized(LOCK) {
        if (moved.isEmpty()) return false
        val (sheets, copied) = read()
        val updated = LinkedHashMap(sheets)
        for (sheet in moved) updated[StyleDedup.sheetKey(sheet)] = sheet
        if (updated == sheets) return false
        write(updated, copied)
        true
    }

    /** The section was copied (or shared) to be added to the note type. */
    fun copied() = synchronized(LOCK) {
        val (sheets, _) = read()
        write(sheets, StyleDedup.section(sheets).hashCode())
    }

    private fun read(): Pair<Map<String, String>, Int?> {
        if (!file.exists()) return emptyMap<String, String>() to null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap<String, String>() to null
        val sheets = json.optJSONObject("sheets") ?: JSONObject()
        return sheets.keys().asSequence().associateWithTo(LinkedHashMap()) { sheets.getString(it) } to
            (if (json.has("copied")) json.getInt("copied") else null)
    }

    private fun write(sheets: Map<String, String>, copied: Int?) {
        val json = JSONObject().put("sheets", JSONObject().also { o -> sheets.forEach { (k, v) -> o.put(k, v) } })
        if (copied != null) json.put("copied", copied)
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
    }

    private companion object {
        val LOCK = Any()
    }
}
