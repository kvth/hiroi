package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BankReaderTest {
    private fun rows(json: String) = mutableListOf<List<Any?>>().also { out -> BankReader(json).forEachRow { out += it } }

    @Test
    fun readsValuesAndKeepsNestedJsonAsWritten() {
        val glossary = """[{"type":"structured-content","content":{"tag":"span","content":"a \"b\" ]}["}}, "x"]"""
        val row = rows("""[["食べる", "たべる", null, "v1", -10, $glossary, 123, "P", true, 1.5, 2e3]]""").single()
        assertEquals(listOf("食べる", "たべる", null, "v1", -10L), row.take(5))
        assertEquals(glossary, (row[5] as BankReader.Raw).json)
        assertEquals(listOf(123L, "P", true, 1.5, 2000.0), row.drop(6))
    }

    @Test
    fun decodesEscapes() {
        val row = rows("""[["a\"b\\c\/d\n\t猫😀"]]""").single()
        assertEquals("a\"b\\c/d\n\t猫😀", row[0])
    }

    @Test
    fun readsEmptyBanksRowsAndWhitespace() {
        assertTrue(rows(" [ ] ").isEmpty())
        assertEquals(listOf(emptyList<Any?>(), listOf<Any?>("a")), rows("﻿[\n [ ],\r\n\t[ \"a\" ]\n]"))
    }

    /** Gives one character at a time, so that every value is read across many refills */
    private class Trickle(private val text: String) : java.io.Reader() {
        private var at = 0
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (at >= text.length) return -1
            buffer[offset] = text[at++]
            return 1
        }
        override fun close() {}
    }

    @Test
    fun readsAStreamAsItsText() {
        val part = """長い説明 \"quoted\" """
        val glossary = """[{"type":"structured-content","content":"${part.repeat(5000)}"}]"""
        val bank = "\uFEFF[" + List(300) { i -> """["語$i","ご$i","",null,$i,$glossary,${i * 1000000000L},"\u3042\n",true,1.5e3]""" }.joinToString(",\n") + "]"
        val fromText = rows(bank)
        val fromStream = mutableListOf<List<Any?>>().also { out -> BankReader(Trickle(bank)).forEachRow { out += it } }
        assertEquals(300, fromText.size)
        assertEquals(fromText, fromStream)
        assertEquals("あ\n", fromStream[7][7])
        val terms = mutableListOf<List<TermEntry>>()
        Yomitan.parseTermBank(java.io.StringReader(bank.removePrefix("\uFEFF")), 3, 128) { terms += it }
        assertEquals(listOf(128, 128, 44), terms.map { it.size })
        assertEquals(Yomitan.parseTermBank(bank.removePrefix("\uFEFF"), 3), terms.flatten())
    }

    @Test
    fun failsOnBrokenJson() {
        assertThrows(IllegalArgumentException::class.java) { rows("""[["a", ]]""") }
        assertThrows(IllegalArgumentException::class.java) { rows("""[["a"]""") }
        assertThrows(IllegalArgumentException::class.java) { rows("""{"a": 1}""") }
        assertThrows(IllegalArgumentException::class.java) { rows("""[["unterminated]]""") }
    }

    @Test
    fun writesValuesBackAsJson() {
        val values = listOf("猫 \"x\"", 12L, 1.5, true, null, BankReader.Raw("""{"a":[1]}"""))
        val json = values.joinToString(",", "[", "]") { BankReader.json(it) }
        assertEquals("""["猫 \"x\"",12,1.5,true,null,{"a":[1]}]""", JSONArray(json).toString())
    }
}
