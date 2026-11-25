package io.github.kvth.hiroi.anki

import org.junit.Assert.assertEquals
import org.junit.Test

/** The parts of the AnkiDroid client that don't need AnkiDroid. */
class AnkiDroidApiTest {
    @Test
    fun duplicatesAreFoundWithAnkisOwnSearch() {
        assertEquals("(\"dupe:5,食べる\")", AnkiDroidApi.duplicateSearch("食べる", listOf(5), null, false))
        // dupe: takes wildcards as they are; only \ and " are escaped. Every note type, with checkAllModels
        assertEquals(
            """("dupe:5,a\\b \"c\" *_" OR "dupe:6,a\\b \"c\" *_")""",
            AnkiDroidApi.duplicateSearch("""a\b "c" *_""", listOf(5, 6), null, false),
        )
        // In a deck, but not its subdecks; or with them
        assertEquals(
            """("dupe:5,x") "deck:My\_Deck" -"deck:My\_Deck::*"""",
            AnkiDroidApi.duplicateSearch("x", listOf(5), "My_Deck", false),
        )
        assertEquals("""("dupe:5,x") "deck:Mining"""", AnkiDroidApi.duplicateSearch("x", listOf(5), "Mining", true))
    }

    @Test
    fun fieldsAreAllWrittenAtOnce() {
        val fields = listOf("食べる", "", "to eat")
        assertEquals(fields, AnkiDroidApi.splitFields(AnkiDroidApi.joinFields(fields)))
        assertEquals("a\u001fb", AnkiDroidApi.joinFields(listOf("a", "b")))
        assertEquals(
            listOf("食べる", "to eat", "new"),
            AnkiDroidApi.mergeFields(linkedMapOf("Word" to "食べる", "Meaning" to "to eat", "Notes" to ""), mapOf("Notes" to "new", "Other" to "x")),
        )
        assertEquals("nid:1,2,3", AnkiDroidApi.nidSearch(listOf(1, 2, 3)))
    }

    @Test
    fun mediaNamesWithoutExtensionAndSafeForAFile() {
        assertEquals("hiroi_audio_食べる_たべる", AnkiDroidApi.mediaName("hiroi_audio_食べる_たべる.mp3"))
        assertEquals("a_b_c", AnkiDroidApi.mediaName("a/b c.png"))
        assertEquals("hiroi_media", AnkiDroidApi.mediaName(".png"))
        // AnkiDroid's temporary file wants a name of 3 characters or more
        assertEquals("x__", AnkiDroidApi.mediaName("x"))
    }
}
