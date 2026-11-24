package io.github.kvth.hiroi.dictionary

/**
 * Reads the rows of a Yomitan bank file (a JSON array of arrays) without building a JSON tree:
 * strings, numbers, booleans and null come as Kotlin values (numbers as Long, or Double if they
 * have a fraction or exponent), arrays and objects inside a row as their JSON text, untouched
 * ([Raw]). Glossaries are stored as JSON text, so they need no parsing and writing back.
 */
internal class BankReader(private val input: java.io.Reader) {
    constructor(text: String) : this(java.io.StringReader(text))

    /** An array or object inside a row, as its JSON text */
    @JvmInline
    value class Raw(val json: String)

    // What's been read of [input] and not yet used: a bank can be far bigger than what's kept.
    private var buffer = CharArray(1 shl 16)
    private var length = 0
    private var pos = 0

    /** Where the value being read starts in [buffer], which keeps it until it's read; -1 if none */
    private var mark = -1

    /** Characters before [buffer]'s first, for errors */
    private var dropped = 0L

    /** Reads more of [input], dropping what's been used; false at its end. */
    private fun fill(): Boolean {
        val keep = if (mark >= 0) mark else pos
        if (keep > 0) {
            System.arraycopy(buffer, keep, buffer, 0, length - keep)
            length -= keep
            pos -= keep
            if (mark >= 0) mark -= keep
            dropped += keep
        }
        if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        val n = input.read(buffer, length, buffer.size - length)
        if (n <= 0) return false
        length += n
        return true
    }

    /** Whether there's a character at [pos], reading more if needed */
    private fun more(): Boolean = pos < length || fill()

    /** Whether there are [n] characters from [pos] on */
    private fun has(n: Int): Boolean {
        while (length - pos < n) if (!fill()) return false
        return true
    }

    /** What's from [mark] to [pos]; no more mark */
    private fun marked(): String = String(buffer, mark, pos - mark).also { mark = -1 }

    /** Calls [each] with the values of every row, in order. */
    fun forEachRow(each: (List<Any?>) -> Unit) {
        if (more() && buffer[pos] == '\uFEFF') pos++ // a byte order mark
        expect('[')
        if (peek() == ']') {
            pos++
            return
        }
        while (true) {
            expect('[')
            val values = ArrayList<Any?>(8)
            if (peek() == ']') {
                pos++
            } else {
                while (true) {
                    values += value()
                    when (next()) {
                        ',' -> continue
                        ']' -> break
                        else -> fail("',' or ']'")
                    }
                }
            }
            each(values)
            when (next()) {
                ',' -> continue
                ']' -> return
                else -> fail("',' or ']'")
            }
        }
    }

    private fun value(): Any? {
        val c = peek()
        return when {
            c == '"' -> string()
            c == '[' || c == '{' -> {
                mark = pos
                skipNested()
                Raw(marked())
            }
            c == 't' -> word("true", true)
            c == 'f' -> word("false", false)
            c == 'n' -> word("null", null)
            c == '-' || c in '0'..'9' -> number()
            else -> fail("a value")
        }
    }

    private fun string(): String {
        pos++ // the opening quote
        mark = pos
        // Most strings have no escapes: those are just what's between the quotes.
        while (more()) {
            when (buffer[pos]) {
                '"' -> return marked().also { pos++ }
                '\\' -> break
                else -> pos++
            }
        }
        val out = StringBuilder(pos - mark + 16).append(buffer, mark, pos - mark)
        mark = -1
        while (more()) {
            val c = buffer[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (!more()) break
                    when (val e = buffer[pos++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (!has(4)) fail("four hex digits")
                            out.append(String(buffer, pos, 4).toIntOrNull(16)?.toChar() ?: fail("four hex digits"))
                            pos += 4
                        }
                        else -> fail("an escape")
                    }
                }
                else -> out.append(c)
            }
        }
        fail("the end of the string")
    }

    /** Moves past the array or object at [pos], strings in it included. */
    private fun skipNested() {
        var depth = 0
        while (more()) {
            when (buffer[pos]) {
                '[', '{' -> depth++
                ']', '}' -> if (--depth == 0) {
                    pos++
                    return
                }
                '"' -> {
                    pos++
                    while (more() && buffer[pos] != '"') {
                        if (buffer[pos] == '\\') {
                            pos++
                            if (!more()) break
                        }
                        pos++
                    }
                }
            }
            pos++
        }
        fail("the end of the array or object")
    }

    private fun number(): Any {
        mark = pos
        var fraction = false
        while (more()) {
            when (buffer[pos]) {
                in '0'..'9', '-', '+' -> {}
                '.', 'e', 'E' -> fraction = true
                else -> break
            }
            pos++
        }
        val number = marked()
        return (if (fraction) null else number.toLongOrNull()) ?: number.toDoubleOrNull() ?: fail("a number")
    }

    private fun word(word: String, value: Any?): Any? {
        if (!has(word.length) || (word.indices).any { buffer[pos + it] != word[it] }) fail(word)
        pos += word.length
        return value
    }

    /** The next character that isn't whitespace, without moving past it. */
    private fun peek(): Char {
        while (more() && buffer[pos].isJsonSpace()) pos++
        if (!more()) fail("more")
        return buffer[pos]
    }

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) {
            pos--
            fail("'$c'")
        }
    }

    private fun fail(expected: String): Nothing =
        throw IllegalArgumentException("Bad JSON at character ${dropped + pos}: expected $expected")

    private fun Char.isJsonSpace() = this == ' ' || this == '\n' || this == '\r' || this == '\t'

    companion object {
        /** Row value [i] as text: "" for null or missing, numbers and nested JSON as written. */
        fun List<Any?>.str(i: Int): String = when (val v = getOrNull(i)) {
            null -> ""
            is String -> v
            is Raw -> v.json
            is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            else -> v.toString()
        }

        /** Row value [i] as a number, 0 if it isn't one, as org.json's optInt. */
        fun List<Any?>.int(i: Int): Int = when (val v = getOrNull(i)) {
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }

        fun List<Any?>.long(i: Int): Long = when (val v = getOrNull(i)) {
            is Long -> v
            is Double -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong() ?: 0
            else -> 0
        }

        /** Row value [i], which must be text. */
        fun List<Any?>.text(i: Int): String =
            getOrNull(i) as? String ?: throw IllegalArgumentException("Row value $i is not text: $this")

        /** [value] as JSON text. */
        fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> org.json.JSONObject.quote(value)
            is Raw -> value.json
            is Double -> if (value.isFinite()) value.toString() else "null"
            else -> value.toString()
        }
    }
}
