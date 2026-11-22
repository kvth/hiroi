package io.github.kvth.hiroi.dictionary

/**
 * Reads the rows of a Yomitan bank file (a JSON array of arrays) without building a JSON tree:
 * strings, numbers, booleans and null come as Kotlin values (numbers as Long, or Double if they
 * have a fraction or exponent), arrays and objects inside a row as their JSON text, untouched
 * ([Raw]). Glossaries are stored as JSON text, so they need no parsing and writing back.
 */
internal class BankReader(private val text: String) {
    /** An array or object inside a row, as its JSON text */
    @JvmInline
    value class Raw(val json: String)

    private var pos = 0

    /** Calls [each] with the values of every row, in order. */
    fun forEachRow(each: (List<Any?>) -> Unit) {
        if (text.startsWith('\uFEFF')) pos = 1 // a byte order mark
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
                val start = pos
                skipNested()
                Raw(text.substring(start, pos))
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
        val start = pos
        // Most strings have no escapes: those are just a substring.
        while (pos < text.length) {
            when (text[pos]) {
                '"' -> return text.substring(start, pos++)
                '\\' -> break
                else -> pos++
            }
        }
        val out = StringBuilder(pos - start + 16).append(text, start, pos)
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) break
                    when (val e = text[pos++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("four hex digits")
                            out.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("four hex digits"))
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
        while (pos < text.length) {
            when (text[pos]) {
                '[', '{' -> depth++
                ']', '}' -> if (--depth == 0) {
                    pos++
                    return
                }
                '"' -> {
                    pos++
                    while (pos < text.length && text[pos] != '"') pos += if (text[pos] == '\\') 2 else 1
                }
            }
            pos++
        }
        fail("the end of the array or object")
    }

    private fun number(): Any {
        val start = pos
        var fraction = false
        while (pos < text.length) {
            when (text[pos]) {
                in '0'..'9', '-', '+' -> {}
                '.', 'e', 'E' -> fraction = true
                else -> break
            }
            pos++
        }
        val number = text.substring(start, pos)
        return (if (fraction) null else number.toLongOrNull()) ?: number.toDoubleOrNull() ?: fail("a number")
    }

    private fun word(word: String, value: Any?): Any? {
        if (!text.startsWith(word, pos)) fail(word)
        pos += word.length
        return value
    }

    /** The next character that isn't whitespace, without moving past it. */
    private fun peek(): Char {
        while (pos < text.length && text[pos].isJsonSpace()) pos++
        if (pos >= text.length) fail("more")
        return text[pos]
    }

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
        if (next() != c) {
            pos--
            fail("'$c'")
        }
    }

    private fun fail(expected: String): Nothing =
        throw IllegalArgumentException("Bad JSON at character $pos: expected $expected")

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
