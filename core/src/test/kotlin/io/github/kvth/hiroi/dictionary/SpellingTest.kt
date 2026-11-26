package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingTest {
    @Test
    fun editsAreOneLetterLeftOutPutInChangedOrSwapped() {
        val edits = Spelling.edits("hosue")
        assertTrue("house" in edits) // swapped
        assertTrue("hose" in edits) // left out
        assertTrue("hosuen" in edits) // put in
        assertTrue("hosta" !in edits) // two changed
        assertTrue("hosve" in edits) // changed
        assertTrue("water" in Spelling.edits("wter"))
        assertTrue("beautiful" in Spelling.edits("beatiful"))
        assertFalse("hosue" in edits)
    }
}
