package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryDbTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val folder by lazy { File(temp.root, "dictionaries") }
    private val list by lazy { File(temp.root, "dictionaries.json") }

    private fun db() = DictionaryDb(folder, list) { null }

    /** A Yomitan dictionary zip with [terms] (expression to definition) */
    private fun zip(title: String, vararg terms: Pair<String, String>, styles: String? = null): File {
        val file = temp.newFile("$title.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("index.json", """{"title":"$title","revision":"1","format":3}""")
            put("term_bank_1.json", terms.joinToString(",", "[", "]") { (expression, definition) -> """["$expression","","","",0,["$definition"],0,""]""" })
            if (styles != null) put("styles.css", styles)
        }
        return file
    }

    private fun DictionaryDb.titles() = dictionaries().map { it.title }

    @Test
    fun keepsTheListInItsFile() {
        val db = db()
        val a = db.import(zip("A", "犬" to "dog", styles = ".a{}")) {}
        val b = db.import(zip("B", "猫" to "cat")) {}
        db.import(zip("C", "鳥" to "bird")) {}
        db.moveTo(b.id, 0)
        db.setEnabled(a.id, false)

        // As another start of the app reads it
        val again = db()
        assertEquals(listOf("B", "A", "C"), again.titles())
        assertEquals(listOf("B", "C"), again.enabledDictionaries().map { it.title })
        assertEquals(mapOf("A" to ".a{}"), again.styles())
        assertTrue(again.summary(a.id)!!.contains("\"title\":\"A\""))
        assertEquals(1, again.dictionaries().first { it.title == "A" }.terms)
        assertEquals(listOf("cat"), again.findTerms(listOf("猫")).let(again::withGlossaries).map { it.glossary.removeSurrounding("[\"", "\"]") })
        // A dictionary that's off isn't searched
        assertTrue(again.findTerms(listOf("犬")).isEmpty())
    }

    @Test
    fun deletesADictionaryAndItsFile() {
        val db = db()
        val a = db.import(zip("A", "犬" to "dog")) {}
        db.import(zip("B", "猫" to "cat")) {}
        assertEquals(2, folder.list()!!.size)
        db.delete(a.id)
        assertEquals(listOf("B"), db().titles())
        assertEquals(1, folder.list()!!.size)
    }

    @Test
    fun anUpdateTakesTheOldOnesPlaceAndSwitch() {
        val db = db()
        db.import(zip("A", "犬" to "dog")) {}
        val old = db.import(zip("Jitendex 1", "猫" to "cat")) {}
        db.import(zip("C", "鳥" to "bird")) {}
        db.setEnabled(old.id, false)
        val new = db.replace(old.id, zip("Jitendex 2", "猫" to "a cat")) {}
        assertEquals(listOf("A", "Jitendex 2", "C"), db().titles())
        assertFalse(new.enabled)
        assertNull(db().dictionaries().firstOrNull { it.id == old.id })
    }

    @Test
    fun aFailedUpdateLeavesTheOldOne() {
        val db = db()
        val old = db.import(zip("A", "犬" to "dog")) {}
        val broken = temp.newFile("broken.zip").apply { writeText("not a zip") }
        assertTrue(runCatching { db.replace(old.id, broken) {} }.isFailure)
        assertEquals(listOf("A"), db().titles())
    }

    @Test
    fun aDictionaryIsImportedOnce() {
        val db = db()
        db.import(zip("A", "犬" to "dog")) {}
        assertTrue(runCatching { db.import(zip("A", "猫" to "cat")) {} }.isFailure)
        assertEquals(listOf("A"), db().titles())
    }

    @Test
    fun filesNoDictionaryHasAreDeletedOnFirstUse() {
        db().import(zip("A", "犬" to "dog")) {}
        File(folder, "left-by-an-import.hd.importing").writeText("")
        assertEquals(listOf("A"), db().titles())
        assertEquals(1, folder.list()!!.size)
    }

    @Test
    fun readsAListWrittenFromElsewhere() {
        val db = db()
        val a = db.import(zip("A", "犬" to "dog", styles = ".a{}")) {}
        val file = folder.list()!!.single()
        list.delete()
        // As the phone's SQLite list is carried over
        DictionaryDb.writeList(list, listOf(DictionaryDb.Listed(a.copy(enabled = false), file, ".a{}", """{"title":"A"}""")))
        val again = db()
        assertEquals(listOf(a.copy(enabled = false)), again.dictionaries())
        assertEquals("""{"title":"A"}""", again.summary(a.id))
        assertEquals(mapOf("A" to ".a{}"), again.styles())
    }
}
