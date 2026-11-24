package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictionaryFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun term(expression: String, reading: String, glossary: String, sequence: Long = 0, score: Int = 0, tags: String = "") =
        TermEntry(expression, reading, tags, "v1", score, glossary, sequence, "")

    private val facts = DictionaryFacts("Test", "1", "rank-based", """{"title":"Test"}""", ".a{}")

    private fun write(build: DictionaryFile.Writer.() -> Unit, dictionary: ByteArray? = null): File {
        val file = folder.newFile()
        DictionaryFile.Writer(file, dictionary).use { writer ->
            writer.build()
            writer.finish(facts, reverseKeys = false)
        }
        return file
    }

    private fun withMeanings(rows: List<TermEntry>): File {
        val file = folder.newFile()
        DictionaryFile.Writer(file, null, meanings = true).use { writer ->
            writer.terms(rows)
            writer.finish(facts, reverseKeys = false)
        }
        return file
    }

    @Test
    fun findsTermsByTheWordsOfTheirDefinitionsBestFittingFirst() {
        val file = withMeanings(
            listOf(
                term("食事", "しょくじ", """["meal", "to eat a meal"]"""),
                term("食べる", "たべる", """["to eat"]"""),
                term("食う", "くう", """["to eat (vulgar)", "to live"]"""),
                term("家", "いえ", """["house", "home"]"""),
                term("家庭", "かてい", """["household; family"]"""),
                term("住宅", "じゅうたく", """["Haus, Wohnhaus"]"""),
            ),
        )
        DictionaryFile.open(file).use { d ->
            assertTrue(d.hasMeanings())
            fun find(vararg words: String, prefix: Boolean = false) = d.findTermsByMeaning(words.toList(), prefix, 10, "T", 0).map { it.first.expression }
            // The shortest definition first; then the earlier one
            assertEquals(listOf("食べる", "食う", "食事"), find("eat"))
            // Every word, as well as the worst fitting one fits
            assertEquals(listOf("食べる", "食う", "食事"), find("to", "eat"))
            assertEquals(listOf("食事"), find("eat", "meal"))
            assertEquals(emptyList<String>(), find("eat", "house"))
            // German as English, accents and case aside
            assertEquals(listOf("住宅"), find(*Meanings.words("HAUS").toTypedArray()))
            // The last word as the start of words, after the word itself
            assertEquals(listOf("家", "家庭"), find("house", prefix = true))
            assertEquals(listOf("家", "家庭"), find("hous", prefix = true))
            assertEquals(emptyList<String>(), find("ho", prefix = true))
            val fits = d.findTermsByMeaning(listOf("eat"), false, 10, "T", 0).map { it.second }
            assertEquals(fits.sorted(), fits)
        }
        // Kept in an export
        val exported = folder.newFile()
        DictionaryFile.open(file).use { d -> exported.outputStream().use { d.writePortable(it) } }
        val restored = File(folder.root, "restored")
        DictionaryFile.restore(exported, restored)
        DictionaryFile.open(restored).use { d -> assertEquals(listOf("食べる", "食う", "食事"), d.findTermsByMeaning(listOf("eat"), false, 10, "T", 0).map { it.first.expression }) }
    }

    @Test
    fun withoutMeaningsNothingIsFoundByThem() {
        val file = write({ terms(listOf(term("食べる", "たべる", """["to eat"]"""))) })
        DictionaryFile.open(file).use { d ->
            assertFalse(d.hasMeanings())
            assertEquals(emptyList<Pair<TermEntry, Int>>(), d.findTermsByMeaning(listOf("eat"), false, 10, "T", 0))
        }
    }

    @Test
    fun findsTermsByExpressionAndReadingWithTheirGlossaries() {
        val file = write({
            terms(listOf(term("食べる", "たべる", """["to eat"]""", 1, 5, "★"), term("猫", "ねこ", """["cat"]""", 2), term("寝子", "ねこ", """["sleeper"]""", 3)))
        })
        assertTrue(DictionaryFile.isDictionaryFile(file))
        DictionaryFile.open(file).use { d ->
            val eat = d.findTerms(listOf("たべる"), "Test", 1).single()
            assertEquals(term("食べる", "たべる", "", 1, 5, "★").copy(dictionary = "Test", priority = 1, id = eat.id), eat)
            assertEquals(listOf("猫", "寝子"), d.findTerms(listOf("ねこ", "猫"), "Test", 1).map { it.expression })
            assertEquals(emptyList<TermEntry>(), d.findTerms(listOf("犬", ""), "Test", 1))
            val all = d.findTerms(listOf("食べる", "ねこ"), "Test", 1)
            assertEquals(listOf("""["to eat"]""", """["cat"]""", """["sleeper"]"""), all.map { d.glossaries(listOf(it.id))[it.id] })
            assertEquals(listOf("寝子"), d.findTermsBySequence(listOf(3, 9), "Test", 1).map { it.expression })
            val f = d.facts()
            assertEquals(facts.copy(terms = 3), f)
        }
    }

    @Test
    fun glossariesAcrossManyBlocksWithADictionary() {
        val rows = List(5000) { i -> term("語$i", "ご$i", """[{"type":"structured-content","content":"meaning number $i ${"x".repeat(i % 50)}"}]""", i.toLong()) }
        val file = write({ terms(rows.subList(0, 2000)); terms(rows.subList(2000, 5000)) }, DictionaryFile.trainDictionary(rows.map { it.glossary }))
        DictionaryFile.open(file).use { d ->
            val found = d.findTerms(rows.map { it.reading }, "Test", 0)
            assertEquals(5000, found.size)
            val glossaries = d.glossaries(found.map { it.id })
            for (entry in found) assertEquals(rows[entry.sequence.toInt()].glossary, glossaries[entry.id])
            // And again, from the cache
            assertEquals(glossaries, d.glossaries(found.map { it.id }))
        }
    }

    @Test
    fun prefixAndSuffixSearchShortestFirst() {
        val file = write({
            terms(listOf(term("食べる", "たべる", "[]"), term("食べ物", "たべもの", "[]"), term("食", "しょく", "[]"), term("調べる", "しらべる", "[]")))
        })
        DictionaryFile.open(file).use { d ->
            assertEquals(listOf("食べる" to 3, "食べ物" to 3), d.findTermsByAffix("食べ", false, 10, "Test", 0).map { it.first.expression to it.second })
            assertEquals(listOf("食べる"), d.findTermsByAffix("食べ", false, 1, "Test", 0).map { it.first.expression })
            assertFalse(d.hasReverseKeys())
            d.buildReverseKeys()
            assertTrue(d.hasReverseKeys())
            assertEquals(listOf("食べる" to 3, "調べる" to 3), d.findTermsByAffix("べる", true, 10, "Test", 0).map { it.first.expression to it.second })
        }
        // Made once, kept beside the file
        DictionaryFile.open(file).use { d ->
            assertTrue(d.hasReverseKeys())
            assertEquals(listOf("食べる"), d.findTermsByAffix("たべる", true, 10, "Test", 0).map { it.first.expression })
        }
    }

    @Test
    fun keysSortInCodePointOrder() {
        // 𠮟 (U+20B9F) is a surrogate pair, which UTF-16 sorts before ｱ (U+FF71); code points after.
        val file = write({ terms(listOf(term("𠮟る", "しかる", "[]"), term("ｱ", "ｱ", "[]"), term("", "", "[]"))) })
        DictionaryFile.open(file).use { d ->
            for (key in listOf("𠮟る", "ｱ", "", "しかる")) assertEquals(key, 1, d.findTerms(listOf(key), "Test", 0).size)
            assertEquals(listOf("𠮟る"), d.findTermsByAffix("𠮟", false, 10, "Test", 0).map { it.first.expression })
            assertEquals(emptyList<TermEntry>(), d.findTerms(listOf("𠮟られる"), "Test", 0))
        }
    }

    @Test
    fun metaKanjiTagsAndImages() {
        val png = byteArrayOf(1, 2, 3, 4)
        val file = write({
            media("img/a.png", png, "image/png", 10 to 20)
            meta(listOf(TermMeta("猫", "freq", "[5]"), TermMeta("猫", "pitch", "[{}]"), TermMeta("犬", "freq", "[9]")), ofKanji = false)
            meta(listOf(TermMeta("猫", "freq", "[1]")), ofKanji = true)
            kanji(listOf(KanjiEntry("猫", "ビョウ", "ねこ", "jouyou", listOf("cat", "feline"), stats = """{"strokes":"11"}""")))
            tags(listOf(TagInfo("v1", "partOfSpeech", 0, "ichidan verb", 0), TagInfo("★", "popular", -1, "common", 10)))
        })
        DictionaryFile.open(file).use { d ->
            // A number is kept as one; "{}" isn't a pitch accent's shape, so it's kept as it is
            assertEquals(
                listOf(TermMeta("猫", "freq", "[5]", "T", 2, MetaData.Frequency(null, 5, null)), TermMeta("猫", "pitch", "[{}]", "T", 2)),
                d.findTermMeta(listOf("猫"), "T", 2),
            )
            assertEquals(listOf(TermMeta("猫", "freq", "[1]", "T", 2, MetaData.Frequency(null, 1, null))), d.findKanjiMeta("猫", "T", 2))
            assertEquals(listOf(KanjiEntry("猫", "ビョウ", "ねこ", "jouyou", listOf("cat", "feline"), "T", stats = """{"strokes":"11"}""")), d.findKanji("猫", "T"))
            assertEquals(listOf(TagInfo("★", "popular", -1, "common", 10, "T")), d.findTags(listOf("★", "nope"), "T"))
            val image = d.findMedia("img/a.png")!!
            assertEquals(listOf("image/png", 10, 20), listOf(image.mediaType, image.width, image.height))
            assertArrayEquals(png, image.read())
            assertNull(d.findMedia("img/b.png"))
            assertEquals(facts.copy(meta = 4, kanji = 1, frequencies = 3, images = 1), d.facts())
            assertEquals(emptyList<TermEntry>(), d.findTerms(listOf("猫"), "T", 0))
        }
    }

    @Test
    fun termsOutOfSequenceOrderAreFoundBySequenceWithTheirGlossaries() {
        val rows = listOf(5L, -3, 9, 5, 0, 9, 2).mapIndexed { i, seq -> term("語$i", "ご$i", "[\"g$i\"]", seq) }
        val file = write({ terms(rows.subList(0, 3)); terms(rows.subList(3, 7)) })
        DictionaryFile.open(file).use { d ->
            // By sequence, and in the order they came within one
            assertEquals(listOf("語0", "語3", "語2", "語5"), d.findTermsBySequence(listOf(9, 5, 7), "T", 0).map { it.expression })
            assertEquals(listOf("語1"), d.findTermsBySequence(listOf(-3), "T", 0).map { it.expression })
            for (entry in d.findTerms(rows.map { it.expression }, "T", 0)) {
                assertEquals(rows.first { it.expression == entry.expression }.glossary, d.glossaries(listOf(entry.id))[entry.id])
            }
        }
    }

    @Test
    fun anExportRestoresToTheSameDictionary() {
        val png = byteArrayOf(9, 8, 7)
        // Some dictionaries have entries without an expression
        val rows = List(3000) { i -> term(if (i % 1000 == 7) "" else "語${i * 7919 % 3000}", "ご$i", "[\"meaning $i\"]", (i * 31 % 500).toLong()) }
        val file = write({
            media("a.png", png, "image/png", 1 to 2)
            terms(rows)
            meta(listOf(TermMeta("語1", "freq", "[1]")), ofKanji = false)
            tags(listOf(TagInfo("v1", "partOfSpeech", 0, "ichidan verb", 0)))
        }, DictionaryFile.trainDictionary(rows.map { it.glossary }))
        val exported = folder.newFile()
        DictionaryFile.open(file).use { d -> exported.outputStream().use { d.writePortable(it) } }
        assertTrue(exported.length() < file.length())
        val restored = File(folder.root, "restored")
        DictionaryFile.restore(exported, restored)
        assertEquals(file.length(), restored.length())
        DictionaryFile.open(file).use { a ->
            DictionaryFile.open(restored).use { b ->
                val forms = rows.flatMap { listOf(it.expression, it.reading) }
                val found = a.findTerms(forms, "T", 0)
                assertEquals(rows.size, found.size)
                assertEquals(found, b.findTerms(forms, "T", 0))
                assertEquals(a.glossaries(found.map { it.id }), b.glossaries(found.map { it.id }))
                assertEquals(a.findTermsBySequence(listOf(0, 31, 499), "T", 0), b.findTermsBySequence(listOf(0, 31, 499), "T", 0))
                assertEquals(a.findTermsByAffix("語1", false, 50, "T", 0), b.findTermsByAffix("語1", false, 50, "T", 0))
                a.buildReverseKeys()
                b.buildReverseKeys()
                val endingIn1 = b.findTermsByAffix("1", true, 3000, "T", 0)
                assertEquals(a.findTermsByAffix("1", true, 3000, "T", 0), endingIn1)
                assertEquals(rows.count { it.expression.endsWith("1") || it.reading.endsWith("1") }, endingIn1.size)
                assertEquals(a.findTermMeta(listOf("語1"), "T", 0), b.findTermMeta(listOf("語1"), "T", 0))
                assertEquals(a.findTags(listOf("v1"), "T"), b.findTags(listOf("v1"), "T"))
                assertArrayEquals(png, b.findMedia("a.png")!!.read())
                assertEquals(a.facts(), b.facts())
            }
        }
    }

    @Test
    fun anExportCantBeOpenedBeforeRestoring() {
        val file = write({ terms(listOf(term("猫", "ねこ", "[]"))) })
        val exported = folder.newFile()
        DictionaryFile.open(file).use { d -> exported.outputStream().use { d.writePortable(it) } }
        assertTrue(DictionaryFile.isDictionaryFile(exported))
        assertTrue(runCatching { DictionaryFile.open(exported) }.isFailure)
    }

    @Test
    fun metaRowsReadBackAsTheyWereTypedOrNot() {
        val rows = listOf(
            "freq" to "[5]",
            "freq" to """[{"reading":"ねこ","frequency":120}]""",
            "freq" to """[{"reading":"猫","frequency":7}]""",
            "freq" to """[{"value":200,"displayValue":"200㋕"}]""",
            "freq" to """[{"reading":"ねこ","frequency":{"value":3,"displayValue":"3"}}]""",
            "freq" to """[{"reading":"ねこ","frequency":{"value":3,"displayValue":""}}]""",
            "freq" to """["12㋕"]""",
            "freq" to "[1.5]",
            "freq" to "[5000000000]",
            "freq" to "[-4]",
            "freq" to """[{"value":7}]""",
            "freq" to """[{"reading":"ねこ","frequency":{"value":3,"displayValue":"x"},"extra":1}]""",
            "freq" to """[{"value":1.0,"displayValue":"1"}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[{"position":1}]}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[{"position":0,"nasal":[],"devoice":[2,3]},{"position":2,"nasal":[1]}]}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[]}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[{"position":"HLL"}]}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[{"position":1,"nasal":2}]}]""",
            "pitch" to """[{"reading":"ねこ","pitches":[{"position":1,"tags":["a"]}]}]""",
            "ipa" to """[{"reading":"ねこ","transcriptions":[{"ipa":"neko"}]}]""",
            "freq" to """[{"value":1,"displayValue":"a\"b\\c\n"}]""",
        ).map { (mode, data) -> TermMeta("猫", mode, data) }
        val typed = setOf(0, 1, 2, 3, 4, 5, 9, 13, 14, 15, 20)
        val file = write({ meta(rows, ofKanji = false) })
        DictionaryFile.open(file).use { d ->
            val read = d.findTermMeta(listOf("猫"), "", 0)
            assertEquals(rows.size, read.size)
            for ((i, pair) in rows.zip(read).withIndex()) {
                val (original, back) = pair
                assertEquals("$i typed", i in typed, back.parsed != null)
                assertTrue("$i: ${back.data}", org.json.JSONArray(back.data).similar(org.json.JSONArray(original.data)))
                for (reading in listOf(null, "ねこ", "猫", "いぬ")) assertEquals("$i $reading", Yomitan.frequency(original, reading), Yomitan.frequency(back, reading))
                for (reading in listOf("ねこ", "いぬ")) assertEquals("$i $reading", Yomitan.pitch(original, reading), Yomitan.pitch(back, reading))
            }
        }
    }

    @Test
    fun sortingOnDiskMakesTheSameFile() {
        val rows = List(3000) { i -> term("語${i * 7919 % 3000}", "ご$i", "[\"meaning $i\"]", (i * 31 % 500).toLong()) }
        val meta = List(2000) { i -> TermMeta("語${i % 700}", "freq", "[{\"reading\":\"ご$i\",\"frequency\":$i}]") }
        val kanji = List(50) { i -> KanjiEntry("${'一' + i}", "on$i", "kun$i", "", listOf("m$i")) }
        fun build(writer: DictionaryFile.Writer) {
            writer.media("b.png", byteArrayOf(2), "image/png", 1 to 1)
            writer.media("a.png", byteArrayOf(1), "image/png", 1 to 1)
            writer.terms(rows.subList(0, 1000))
            writer.terms(rows.subList(1000, 3000))
            writer.meta(meta, ofKanji = false)
            writer.kanji(kanji)
            writer.finish(facts, reverseKeys = false)
        }
        val inMemory = folder.newFile()
        DictionaryFile.Writer(inMemory, null).use(::build)
        val onDisk = folder.newFile()
        DictionaryFile.Writer(onDisk, null, sortMemory = 64).use(::build)
        assertArrayEquals(inMemory.readBytes(), onDisk.readBytes())
        assertEquals(emptyList<String>(), folder.root.list()!!.filter { it.startsWith(onDisk.name + ".") || it.startsWith(inMemory.name + ".") })
        DictionaryFile.open(onDisk).use { d ->
            assertEquals(rows.size, d.findTerms(rows.map { it.reading }, "T", 0).size)
            assertEquals(meta.count { it.expression == "語5" }, d.findTermMeta(listOf("語5"), "T", 0).size)
            assertEquals(listOf("m3"), d.findKanji("${'一' + 3}", "T").single().meanings)
            assertArrayEquals(byteArrayOf(1), d.findMedia("a.png")!!.read())
        }
    }

    @Test
    fun filtersLetEveryKeyThroughAndNoOtherIsFound() {
        val rows = List(3000) { i -> term("語$i", "ご$i", "[]") }
        val meta = List(3000) { i -> TermMeta("語$i", "freq", "[$i]") }
        val file = write({ terms(rows); meta(meta, ofKanji = false) })
        val absent = List(1000) { "無$it" }
        DictionaryFile.open(file).use { d ->
            // A Bloom filter never says a key that's there isn't
            assertEquals(rows.size, d.findTerms(rows.map { it.expression }, "T", 0).size)
            assertEquals(rows.size, d.findTerms(rows.map { it.reading }, "T", 0).size)
            assertEquals(meta.size, d.findTermMeta(meta.map { it.expression }, "T", 0).size)
            assertEquals(emptyList<TermEntry>(), d.findTerms(absent, "T", 0))
            assertEquals(emptyList<TermMeta>(), d.findTermMeta(absent, "T", 0))
        }
    }

    @Test
    fun aFailedWriteLeavesNothing() {
        val file = folder.newFile()
        DictionaryFile.Writer(file, null).use { it.terms(listOf(term("猫", "ねこ", "[]"))) }
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), folder.root.list()!!.filter { it.startsWith(file.name + ".") })
    }

    @Test
    fun leavesOutFormsLongerThanTheLongestKey() {
        val forms = listOf("食べさせられた", "食べる", "食")
        assertSame(forms, fitting(forms, 7))
        assertEquals(listOf("食べる", "食"), fitting(forms, 3))
        // Counted in code points: 𠮟 is two chars in Kotlin but one character
        assertEquals(listOf("𠮟る"), fitting(listOf("𠮟る", "𠮟られる"), 2))
    }
}
