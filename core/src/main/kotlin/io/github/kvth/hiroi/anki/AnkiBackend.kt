package io.github.kvth.hiroi.anki

/**
 * What Hiroi asks of Anki: [AnkiConnect] (desktop Anki, or AnkiConnect Android) or [AnkiDroidApi]
 * (AnkiDroid on this phone, directly). IPC or network: call off the main thread.
 */
interface AnkiBackend {
    /** [fields] in the note type's order; [mod] is when it was last edited, in seconds (0 if unknown). */
    data class Note(
        val id: Long,
        val fields: Map<String, String>,
        val modelName: String = "",
        val tags: List<String> = emptyList(),
        val mod: Long = 0,
    )

    /**
     * A note to add, with AnkiConnect's duplicate options: unless [allowDuplicate], it can't be
     * added while a note has the same first field, in the note type (all of them with
     * [checkAllModels]) and, for [duplicateScope] "deck", with a card in [scopeDeck] (or its
     * subdecks with [checkChildren]).
     */
    data class NewNote(
        val deck: String,
        val model: String,
        val fields: Map<String, String>,
        val tags: List<String> = emptyList(),
        val allowDuplicate: Boolean = true,
        val duplicateScope: String = "collection",
        val scopeDeck: String = deck,
        val checkChildren: Boolean = false,
        val checkAllModels: Boolean = false,
    )

    fun deckNames(): List<String>

    fun modelNames(): List<String>

    fun modelFieldNames(model: String): List<String>

    /** Whether each note would be added; false for a duplicate (by its options) or an empty first field. */
    fun canAddNotes(notes: List<NewNote>): List<Boolean>

    fun canAddNote(note: NewNote): Boolean = canAddNotes(listOf(note)).single()

    /** Adds [note] and returns its id. */
    fun addNote(note: NewNote): Long

    /** Replaces the given fields of note [id]; the others stay. */
    fun updateNoteFields(id: Long, fields: Map<String, String>)

    /** Stores a media file and returns the name Anki gave it, which may differ from [name]. */
    fun storeMediaFile(name: String, data: ByteArray): String

    fun findNotes(query: String): List<Long>

    fun notesInfo(ids: List<Long>, batch: Int = 100): List<Note>

    /** When each note was last edited, or null where Anki can't tell. An unreachable Anki still throws. */
    fun notesModTime(ids: List<Long>): Map<Long, Long>?

    /** Opens Anki's card browser on [query]. */
    fun guiBrowse(query: String)

    /** The note type's Styling (its CSS). */
    fun modelStyling(model: String): String

    fun updateModelStyling(model: String, css: String)

    /** Field names of (a sample of) the notes in [deck], in the order they appear. */
    fun fieldNames(deck: String): List<String> =
        notesInfo(findNotes(deckQuery(deck)).take(FIELD_SAMPLE)).flatMap { it.fields.keys }.distinct()

    /** The notes in [deck] whose [field] is one of [words] (ignoring HTML and furigana), by word. */
    fun notesInDeck(deck: String, field: String, words: Collection<String>): Map<String, List<Long>> {
        if (words.isEmpty()) return emptyMap()
        // A contains-search finds notes whose field has extra markup; the exact check happens below.
        val terms = words.joinToString(" OR ") { "\"${escape(field)}:*${escape(it)}*\"" }
        val ids = findNotes("${deckQuery(deck)} ($terms)").take(MAX_NOTES)
        val wanted = words.toSet()
        return notesInfo(ids)
            .mapNotNull { note -> note.fields[field]?.let(::fieldText)?.takeIf { it in wanted }?.let { it to note.id } }
            .groupBy({ it.first }, { it.second })
    }

    companion object {
        private const val MAX_NOTES = 300
        private const val FIELD_SAMPLE = 50

        fun deckQuery(deck: String) = "\"deck:${escape(deck)}\""

        /** Escapes text for an Anki search inside double quotes: \ " and the wildcards * _ */
        fun escape(text: String): String = text.replace(Regex("""([\\"*_])"""), """\\$1""")

        /** A field's plain text: HTML, ruby readings and Anki furigana (日本[にほん]) removed. */
        fun fieldText(html: String): String = html
            .replace(Regex("<(rt|rp)\\b[^>]*>.*?</\\1>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex(" ?([^\\s\\[\\]]*)\\[[^\\]]*\\]"), "$1")
            .trim()
    }
}
