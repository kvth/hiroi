package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HiroiCollectionTest {
    @Test
    fun listsTheDictionariesInTheirOrderWithTheirSwitches() {
        val items = listOf(
            HiroiCollection.Item("Jitendex", HiroiCollection.path(1), true),
            HiroiCollection.Item("JPDB", HiroiCollection.path(2), false),
        )
        assertEquals(items, HiroiCollection.items(HiroiCollection.manifest(items)))
        assertEquals("dictionaries/2.db", items[1].path)
    }

    @Test
    fun refusesOtherFilesAndNewerCollections() {
        assertThrows(IllegalArgumentException::class.java) { HiroiCollection.items("""{"formatName":"dexie"}""") }
        assertThrows(IllegalArgumentException::class.java) { HiroiCollection.items("""{"hiroiDictionaries":${HiroiCollection.VERSION + 1}}""") }
    }
}
