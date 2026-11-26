package io.github.kvth.hiroi.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class WordAudioTest {
    @Test
    fun asksJapanesePod101AsYomitanDoes() {
        assertEquals(
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E9%A3%9F%E3%81%B9%E3%82%8B&kana=%E3%81%9F%E3%81%B9%E3%82%8B",
            WordAudio.jpod101Url("食べる", "たべる"),
        )
        // A word in kana alone goes by its reading only
        assertEquals("https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kana=%E3%81%BE%E3%81%BE", WordAudio.jpod101Url("まま", "まま"))
    }

    @Test
    fun fillsCustomUrls() {
        assertEquals("http://localhost:5050/?term=猫&reading=ねこ&lang=ja&x={x}", WordAudio.customUrl("http://localhost:5050/?term={term}&reading={reading}&lang={language}&x={x}", "猫", "ねこ"))
    }

    @Test
    fun readsCustomJsonLists() {
        val source = AudioSource("custom-json", "http://localhost/{term}")
        val infos = WordAudio.customJson(source, """{"type":"audioSourceList","audioSources":[{"url":"http://a/1.mp3","name":"NHK"},{"url":"http://a/2.ogg"}]}""")
        assertEquals(listOf("http://a/1.mp3" to "NHK", "http://a/2.ogg" to null), infos.map { it.url to it.name })
    }

    @Test
    fun findsJishosRecording() {
        val html = """<div><audio id="audio_食べる:たべる"><source src="//d1vjc5dkcd3yh2.cloudfront.net/audio/abc.mp3" type="audio/mpeg"></audio></div>"""
        assertEquals("https://d1vjc5dkcd3yh2.cloudfront.net/audio/abc.mp3", WordAudio.jishoAudio(html, "食べる", "たべる", "https://jisho.org/search/%E9%A3%9F%E3%81%B9%E3%82%8B"))
        assertNull(WordAudio.jishoAudio(html, "食べる", "くう", "https://jisho.org/search/x"))
    }

    @Test
    fun skipsJapanesePod101sNoAudioClipAndTriesTheNextSource() {
        val noAudio = "the clip saying there's no audio".toByteArray()
        val real = byteArrayOf(1, 2, 3)
        val hash = MessageDigest.getInstance("SHA-256").digest(noAudio).joinToString("") { "%02x".format(it) }
        val audio = WordAudio({ url -> if ("languagepod101" in url) noAudio else real }, noAudio = hash)
        val sources = listOf(AudioSource("jpod101"), AudioSource("custom", "http://custom/{term}"))
        val found = audio.first(sources, "猫", "ねこ")!!
        assertEquals("custom", found.info.source.type)
        assertArrayEquals(real, found.bytes)
        // A real JapanesePod101 recording plays
        assertEquals("jpod101", WordAudio({ real }, noAudio = hash).first(sources, "猫", "ねこ")!!.info.source.type)
        // None anywhere
        assertNull(WordAudio({ null }).first(sources, "猫", "ねこ"))
    }

    @Test
    fun speaksOnlyWhenAllowed() {
        val sources = listOf(AudioSource("text-to-speech-reading"), AudioSource("custom", "http://x/{term}"))
        val audio = WordAudio({ byteArrayOf(9) })
        assertEquals("ねこ", audio.first(sources, "猫", "ねこ")!!.info.speak)
        // For a card: text-to-speech can't be stored, so the next source
        assertEquals("custom", audio.first(sources, "猫", "ねこ", speech = false)!!.info.source.type)
    }
}
