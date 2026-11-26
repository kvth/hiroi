package io.github.kvth.hiroi.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioTest {
    /** A database as the add-on makes it, with two words */
    private val store = object : LocalAudioStore {
        val entries = listOf(
            "食べる" to LocalRecording("jpod", null, null, "media/a1.opus", "たべる"),
            "食べる" to LocalRecording("forvo", "akitomo", "akitomo", "akitomo/食べる.opus", null),
            "食べる" to LocalRecording("nhk16", null, "たべる [2]", "audio/2017.opus", "たべる"),
            "食べる" to LocalRecording("forvo", "kaoring", "kaoring", "kaoring/食べる.opus", null),
            "テレビ" to LocalRecording("jpod", null, null, "media/b2.opus", "テレビ"),
        )
        val files = mapOf("media/a1.opus" to byteArrayOf(1, 2), "audio/2017.opus" to byteArrayOf(3))
        var asked: List<String> = emptyList()

        override fun recordings(term: String, readings: List<String>): List<LocalRecording> {
            asked = readings
            return entries.filter { (t, r) -> t == term && (r.reading == null || r.reading in readings) }.map { it.second }
        }

        override fun data(source: String, file: String) = files[file]
        override fun counts() = entries.groupingBy { it.second.source }.eachCount()
    }

    @Test
    fun namesRecordingsAsTheAddOnDoes() {
        assertEquals("NHK16 たべる [2]", LocalAudio.name(LocalRecording("nhk16", null, "たべる [2]", "f")))
        assertEquals("Forvo (akitomo)", LocalAudio.name(LocalRecording("forvo", "akitomo", "akitomo", "f")))
        assertEquals("JPod101", LocalAudio.name(LocalRecording("jpod", null, null, "f")))
        // Without what to show with it, just the source
        assertEquals("NHK16", LocalAudio.name(LocalRecording("nhk16", null, null, "f")))
        assertEquals("Forvo", LocalAudio.sourceName("forvo"))
    }

    @Test
    fun findsTheWordsRecordingsInTheSourcesOrder() {
        val audio = WordAudio({ error("no network") }, local = { store }, localOrder = { listOf("nhk16", "forvo", "jpod") })
        val infos = audio.infos(AudioSource("local"), "食べる", "たべる")
        assertEquals(listOf("NHK16 たべる [2]", "Forvo (akitomo)", "Forvo (kaoring)", "JPod101"), infos.map { it.name })
        assertEquals("local-audio:nhk16/audio/2017.opus", infos.first().url)
        // Asked in either kana
        assertEquals(listOf("たべる", "タベル"), store.asked)
        assertEquals(listOf("JPod101"), audio.infos(AudioSource("local"), "テレビ", "テレビ").map { it.name })
        assertEquals(emptyList<String>(), audio.infos(AudioSource("local"), "猫", "ねこ").map { it.name })
    }

    @Test
    fun readsRecordingsFromTheDatabaseWithoutTheNetwork() {
        val audio = WordAudio({ error("no network") }, local = { store })
        assertArrayEquals(byteArrayOf(3), audio.download(AudioInfo(AudioSource("local"), url = "local-audio:nhk16/audio/2017.opus")))
        assertNull(audio.download(AudioInfo(AudioSource("local"), url = "local-audio:forvo/akitomo/食べる.opus")))
        // The first the sources have, as {audio} takes it: NHK first in the add-on's order
        val found = audio.first(listOf(AudioSource("local"), AudioSource("jpod101")), "食べる", "たべる", speech = false)!!
        assertEquals("NHK16 たべる [2]", found.info.name)
        assertArrayEquals(byteArrayOf(3), found.bytes)
    }

    @Test
    fun noDatabaseNoRecordings() {
        val audio = WordAudio({ null }, local = { null })
        assertEquals(emptyList<AudioInfo>(), audio.infos(AudioSource("local"), "食べる", "たべる"))
        assertNull(audio.download(AudioInfo(AudioSource("local"), url = "local-audio:jpod/media/a1.opus")))
    }

    @Test
    fun urlsSayTheSourceAndFile() {
        assertEquals("forvo" to "akitomo/食べる.opus", LocalAudio.parse("local-audio:forvo/akitomo/食べる.opus"))
        assertNull(LocalAudio.parse("https://example.com/a.mp3"))
        assertEquals(listOf("nhk16", "jpod", "forvo"), LocalAudio.sorted(
            listOf(LocalRecording("forvo", null, null, ""), LocalRecording("jpod", null, null, ""), LocalRecording("nhk16", null, null, "")),
            listOf("nhk16", "jpod"),
        ).map { it.source })
    }
}
