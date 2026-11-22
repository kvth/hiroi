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
