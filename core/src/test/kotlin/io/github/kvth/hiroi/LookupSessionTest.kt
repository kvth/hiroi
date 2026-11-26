package io.github.kvth.hiroi

import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.Screenshot
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.WordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.settings.MemoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LookupSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A host whose main thread is whichever thread calls it, which keeps what the session runs on the page */
    private class Host : LookupHost {
        val scripts: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        override fun onMain(action: () -> Unit) = action()
        override fun runScript(script: String) {
            scripts += script
        }
        override fun text(text: LookupText, vararg args: Any) = (listOf(text.name) + args).joinToString(" ")
        override fun toast(text: String) {}
        override fun log(message: String, error: Throwable?) {}
        override fun documentTitle() = ""
        override fun selection() = ""
        override fun screenshots(): Pair<Screenshot?, Screenshot?> = null to null
        override fun translate(sentence: String) = throw UnsupportedOperationException()
        override fun cardCreator(): CardCreator = throw UnsupportedOperationException()
        override fun browseInLocalAnki(query: String) = false
        override fun play(audio: ByteArray, volume: Int) {}
        override fun speak(text: String, volume: Int, onFailure: () -> Unit) {}
        override fun playFallback(sound: String, volume: Int) {}
        override fun audioSourceLabel(source: AudioSource) = source.type
        override fun choose(title: String, items: List<String>, onChosen: (Int) -> Unit) {}

        /** Waits until a script the page got [matches] */
        fun await(matches: (String) -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (synchronized(scripts) { scripts.none(matches) }) {
                check(System.nanoTime() < until) { "No such script: $scripts" }
                Thread.sleep(10)
            }
        }
    }

    private val host = Host()
    private val settings = DictionarySettings(MemoryStore())
    private val dictionaries by lazy { DictionaryDb(File(temp.root, "dictionaries"), File(temp.root, "dictionaries.json")) { null } }
    private val session by lazy {
        LookupSession(
            host, dictionaries, settings, AnkiSettings(MemoryStore()), AudioSettings(MemoryStore()), WordAudio(WordAudio::httpFetch),
            ResultsHtml.Colors("#000", "#666", "#00f"),
        )
    }

    @After
    fun close() = session.close()

    private fun import() {
        val zip = temp.newFile("test.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("index.json", """{"title":"Test","revision":"1","format":3}""")
            put("term_bank_1.json", """[["食べる","たべる","","v1",0,["to eat"],0,""],["猫","ねこ","","n",0,["cat"],0,""]]""")
        }
        dictionaries.import(zip) {}
    }

    private fun lookUp(text: String, offset: Int = 0): Lookup {
        val outcome = CompletableFuture<Lookup>()
        session.lookUp(text, offset) { outcome.complete(it) }
        return outcome.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun findsTheWordAndShowsIt() {
        import()
        val found = lookUp("猫が食べた", 2) as Lookup.Found
        assertEquals("食べる", found.result.terms.first().expression)
        session.show(found)
        assertTrue(host.scripts.first().startsWith("showResults("))
        assertTrue(host.scripts.any { it.startsWith("useTab(") })
    }

    @Test
    fun saysWhyThereAreNoResults() {
        assertEquals("NO_DICTIONARIES", (lookUp("猫") as Lookup.Message).text)
        import()
        assertEquals("NO_MATCH", (lookUp("犬") as Lookup.Message).text)
    }

    @Test
    fun aWordInADefinitionShowsInPlaceOfTheResultsWithABarBack() {
        import()
        session.show(lookUp("食べた") as Lookup.Found)
        session.handle("""{"type": "lookUpIn", "text": "猫を見る", "offset": 0}""")
        host.await { it.startsWith("backBar(") }
        assertTrue(host.scripts.contains("pushResults()"))
        assertTrue(host.scripts.contains("backBar(\"食べる\")"))
        assertTrue(session.back())
        assertTrue(host.scripts.contains("popResults()"))
        assertFalse(session.back())
    }

    @Test
    fun theChosenTabIsKeptForTheNextResults() {
        session.handle("""{"type": "selectTab", "tab": "EN"}""")
        assertEquals("EN", settings.lastTab)
        // What isn't a message is left alone
        session.handle("selectTab")
        session.handle("""{"type": "nothing"}""")
        assertEquals("EN", settings.lastTab)
    }
}
