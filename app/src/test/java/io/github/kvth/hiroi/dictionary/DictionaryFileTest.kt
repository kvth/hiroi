package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DictionaryFileTest {
    @Test
    fun leavesOutFormsLongerThanTheLongestKey() {
        val forms = listOf("食べさせられた", "食べる", "食")
        assertSame(forms, fitting(forms, 7))
        assertEquals(listOf("食べる", "食"), fitting(forms, 3))
        // Counted in code points: 𠮟 is two chars in Kotlin but one character
        assertEquals(listOf("𠮟る"), fitting(listOf("𠮟る", "𠮟られる"), 2))
    }
}
