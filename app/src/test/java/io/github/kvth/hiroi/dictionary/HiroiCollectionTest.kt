package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HiroiCollectionTest {
    @Test
    fun listsTheDictionariesInTheirOrderWithTheirSwitches() {
        val items = listOf(
            HiroiCollection.Item("Jitendex", HiroiCollection.path(1), true),
            HiroiCollection.Item("JPDB", HiroiCollection.path(2), false),
        )
        assertEquals(items, HiroiCollection.items(HiroiCollection.manifest(items)))
        assertEquals("dictionaries/2.hd", items[1].path)
    }

    @Test
    fun refusesOtherFilesAndNewerCollections() {
        assertThrows(IllegalArgumentException::class.java) { HiroiCollection.items("""{"formatName":"dexie"}""") }
        assertThrows(IllegalArgumentException::class.java) { HiroiCollection.items("""{"hiroiDictionaries":${HiroiCollection.VERSION + 1}}""") }
        // Collections of SQLite files, before Hiroi's own format
        assertThrows(IllegalArgumentException::class.java) { HiroiCollection.items("""{"hiroiDictionaries":1}""") }
    }

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun aCollectionIsToldByItsFirstEntryAndReadFromTheStart() {
        val input = ByteArrayInputStream(zip(HiroiCollection.MANIFEST, HiroiCollection.path(1)))
        assertTrue(HiroiCollection.isCollection(input))
        // Still at the start: the manifest is the first entry read
        assertEquals(HiroiCollection.MANIFEST, ZipInputStream(input).nextEntry?.name)
        assertFalse(HiroiCollection.isCollection(ByteArrayInputStream(zip("index.json", "term_bank_1.json"))))
        assertFalse(HiroiCollection.isCollection(ByteArrayInputStream(zip(HiroiCollection.path(1), HiroiCollection.MANIFEST))))
        assertFalse(HiroiCollection.isCollection(ByteArrayInputStream("not a zip at all, but long enough to have a header".toByteArray())))
        assertFalse(HiroiCollection.isCollection(ByteArrayInputStream(ByteArray(0))))
    }
}
