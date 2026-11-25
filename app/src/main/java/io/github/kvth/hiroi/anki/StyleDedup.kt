package io.github.kvth.hiroi.anki

import java.security.MessageDigest

/**
 * Takes the dictionaries' stylesheets out of a card's fields, as anki-yomitan-css-dedup does for
 * existing notes (a port of its dedup.py): Yomitan's templates copy each dictionary's whole
 * stylesheet into every glossary field. The rules limited to one dictionary (every selector has
 * `[data-dictionary="…"]`) go once into the note type's Styling instead, between the same markers
 * the add-on uses ([section]), so the two share that section. All other rules (Yomitan's compact
 * glossary rules, which match every glossary on a card) stay in the field as a small `<style>`.
 */
object StyleDedup {
    const val SHEETS_BEGIN = "/* BEGIN yomitan-dictionary-styles (generated, do not edit) */"
    const val SHEETS_END = "/* END yomitan-dictionary-styles */"

    private val STYLE_BLOCK = Regex("(<style[^>]*>)(.*?)(</style>)", RegexOption.DOT_MATCHES_ALL)
    private val DICTIONARY = Regex("data-dictionary=\"([^\"]+)\"")
    private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val NEGATING_PSEUDO = Regex(":(?:not|is|where)\\((?:[^()]|\\([^()]*\\))*\\)")

    /** A field without the dictionary-limited rules of its `<style>` blocks, and those rules (a stylesheet per block). */
    class Field(val text: String, val moved: List<String>)

    /**
     * Takes the rules limited to a dictionary out of [text]'s `<style>` blocks. A block keeps its
     * other rules (each one once per field); a block without any such rule stays as it is.
     */
    fun field(text: String): Field {
        val moved = mutableListOf<String>()
        val kept = HashSet<String>()
        val out = STYLE_BLOCK.replace(text) { m ->
            val (scoped, rest) = splitSheet(m.groupValues[2])
            if (scoped.isEmpty()) return@replace m.value
            moved += scoped
            val new = rest.filter { kept.add(it.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")) }
            if (new.isEmpty()) "" else m.groupValues[1] + new.joinToString("\n") + m.groupValues[3]
        }
        return Field(out, moved)
    }

    /** The dictionary a moved stylesheet is for; one stylesheet is kept per dictionary. */
    fun sheetKey(sheet: String): String =
        DICTIONARY.find(sheet)?.groupValues?.get(1)?.let(::unescape)
            ?: "sha1:" + MessageDigest.getInstance("SHA-1").digest(sheet.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    /** The note type's Styling section with [sheets] (by [sheetKey]), in the add-on's format. */
    fun section(sheets: Map<String, String>): String = buildString {
        append(SHEETS_BEGIN).append('\n')
        for ((key, sheet) in sheets) append("/* yomitan-sheet: ").append(key).append(" */\n").append(sheet).append('\n')
        append(SHEETS_END)
    }

    /**
     * The note type's Styling [css] with [sheets] in its section: added to the sheets already
     * there (the add-on's, or those of cards made elsewhere), a dictionary's replacing its old
     * one; a new section goes at the end. The rest of the Styling stays as it is.
     */
    fun withSheets(css: String, sheets: Map<String, String>): String {
        if (sheets.isEmpty()) return css
        val begin = css.indexOf(SHEETS_BEGIN)
        val end = if (begin < 0) -1 else css.indexOf(SHEETS_END, begin)
        if (end < 0) return if (css.isBlank()) section(sheets) else css.trimEnd() + "\n\n" + section(sheets)
        val body = css.substring(begin + SHEETS_BEGIN.length, end)
        val markers = SHEET_MARKER.findAll(body).toList()
        // Anything before the first sheet's marker, kept at the start of the section
        val before = body.substring(0, markers.firstOrNull()?.range?.first ?: body.length).trim()
        val merged = LinkedHashMap<String, String>()
        markers.forEachIndexed { i, m ->
            merged[m.groupValues[1]] = body.substring(m.range.last + 1, markers.getOrNull(i + 1)?.range?.first ?: body.length).trim('\n')
        }
        merged.putAll(sheets)
        val section = section(merged).let { if (before.isEmpty()) it else it.replaceFirst(SHEETS_BEGIN + "\n", SHEETS_BEGIN + "\n" + before + "\n") }
        return css.substring(0, begin) + section + css.substring(end + SHEETS_END.length)
    }

    private val SHEET_MARKER = Regex("""/\* yomitan-sheet: (.*?) \*/\n?""")

    /** A stylesheet split into the rules limited to a dictionary (to move, as one sheet) and the rest. */
    fun splitSheet(css: String): Pair<String, List<String>> {
        val scoped = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (chunk in topLevelChunks(css)) (if (dictionaryScoped(chunk)) scoped else rest) += chunk.raw
        return scoped.joinToString("\n") to rest
    }

    /** A top-level piece of a stylesheet: a rule, or anything else (a statement, junk). */
    private class Chunk(val raw: String, val selector: String? = null, val body: String? = null)

    private fun topLevelChunks(text: String): List<Chunk> {
        val css = COMMENT.replace(text, "")
        val chunks = mutableListOf<Chunk>()
        var depth = 0
        var start = 0
        var bodyStart = 0
        var quote: Char? = null
        var selector = ""
        for ((i, c) in css.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '{' -> {
                    if (depth == 0) {
                        selector = css.substring(start, i).trim()
                        bodyStart = i + 1
                    }
                    depth++
                }
                c == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0) {
                        chunks += Chunk(css.substring(start, i + 1).trim(), selector, css.substring(bodyStart, i))
                        start = i + 1
                    }
                }
                c == ';' && depth == 0 -> {
                    if (css.substring(start, i).isNotBlank()) chunks += Chunk(css.substring(start, i + 1).trim())
                    start = i + 1
                }
            }
        }
        if (css.substring(start).isNotBlank()) chunks += Chunk(css.substring(start).trim())
        return chunks
    }

    /** False for selectors browsers drop, like those broken by Yomitan's stray `<br>`. */
    private fun validSelector(selector: String): Boolean {
        var quote: Char? = null
        for (c in selector) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '<' -> return false
            }
        }
        return selector.isNotEmpty()
    }

    /** Whether the rule can only ever match inside entries of one dictionary. */
    private fun dictionaryScoped(chunk: Chunk): Boolean {
        val selector = chunk.selector ?: return false
        if (!validSelector(selector)) return false
        if (selector.startsWith("@")) { // @media etc.: scoped if everything inside is
            val inner = topLevelChunks(chunk.body.orEmpty())
            return inner.isNotEmpty() && inner.all(::dictionaryScoped)
        }
        // A selector list matches if any part does, so only what all parts need counts.
        val parts = splitSelectorList(selector).map { NEGATING_PSEUDO.replace(it, "") }
        return parts.map { part -> DICTIONARY.findAll(part).map { unescape(it.groupValues[1]) }.toSet() }
            .reduce { a, b -> a intersect b }
            .isNotEmpty()
    }

    private fun splitSelectorList(selector: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val buffer = StringBuilder()
        for (c in selector) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts += buffer.toString()
                    buffer.clear()
                    continue
                }
            }
            buffer.append(c)
        }
        parts += buffer.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(selector) }
    }

    private fun unescape(text: String) = text
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
