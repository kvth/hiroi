package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusejiTest {
    @Test
    fun censoredCharactersAreAnyCharacter() {
        assertTrue(Fuseji.matches("ク○ガキ", "クソガキ"))
        assertTrue(Fuseji.matches("ク×ガキ", "クソガキ"))
        assertTrue(Fuseji.matches("○ぬ", "死ぬ"))
        assertFalse(Fuseji.matches("ク○ガキ", "クソガキ共"))
        assertFalse(Fuseji.matches("ク○ガキ", "ワルガキ"))
    }

    @Test
    fun onlyPartlyCensored() {
        assertTrue(Fuseji.isCensored("ク○ガキ"))
        assertTrue(Fuseji.isCensored("○ね"))
        assertFalse(Fuseji.isCensored("○○"))
        assertFalse(Fuseji.isCensored("クソガキ"))
    }
}
