package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YomitanTest {
    @Test
    fun parsesIndexOfEitherVersion() {
        assertEquals(Yomitan.Index("Jitendex", "2026.11.01", 3), Yomitan.parseIndex("""{"title":"Jitendex","revision":"2026.11.01","format":3}"""))
        assertEquals(1, Yomitan.parseIndex("""{"title":"Old","revision":"1","version":1}""").format)
        assertEquals("rank-based", Yomitan.parseIndex("""{"title":"JPDB","format":3,"frequencyMode":"rank-based"}""").frequencyMode)
        assertNull(Yomitan.parseIndex("""{"title":"X","format":3,"frequencyMode":"other"}""").frequencyMode)
    }

    @Test
    fun parsesTermBanks() {
        val v3 = Yomitan.parseTermBank("""[["食べる","たべる",null,"v1",10,["to eat"],123,"P"],["猫","",null,"",0,["cat"],5,""]]""", 3)
        assertEquals("v1", v3[0].rules)
        assertEquals("", v3[0].definitionTags)
        assertEquals(123L, v3[0].sequence)
        assertEquals("猫", v3[1].reading) // empty reading means the expression is the reading

        val v1 = Yomitan.parseTermBank("""[["犬","いぬ","n","",0,"dog","hound"]]""", 1)
        assertEquals(listOf("dog", "hound"), Yomitan.glossaryItems(v1[0].glossary))
        // Glossaries are kept as written
        assertEquals("""[ {"type":"text","text":"a\/b"} ]""", Yomitan.parseTermBank("""[["猫","ねこ","","",0,[ {"type":"text","text":"a\/b"} ],1,""]]""", 3)[0].glossary)
    }

    @Test
    fun parsesMetaKanjiAndTagBanks() {
        val meta = Yomitan.parseTermMetaBank("""[["食べる","freq",300],["食べる","freq",{"reading":"たべる","frequency":{"value":5,"displayValue":"5㋕"}}],["猫","pitch","x"]]""")
        assertEquals(listOf("[300]", """[{"reading":"たべる","frequency":{"value":5,"displayValue":"5㋕"}}]""", "[\"x\"]"), meta.map { it.data })
        assertEquals(300, Yomitan.frequency(meta[0], null)?.value)
        val kanji = Yomitan.parseKanjiBank("""[["日","ニチ ジツ","ひ か","jouyou",["day","sun"],{"strokes":"4"}]]""", 3).single()
        assertEquals(listOf("日", "ニチ ジツ", "ひ か", "jouyou"), listOf(kanji.character, kanji.onyomi, kanji.kunyomi, kanji.tags))
        assertEquals(listOf("day", "sun"), kanji.meanings)
        assertEquals("""{"strokes":"4"}""", kanji.stats)
        assertEquals("{}", Yomitan.parseKanjiBank("""[["日","ニチ","ひ","",["day"]]]""", 3).single().stats)
        assertEquals("{}", Yomitan.parseKanjiBank("""[["日","ニチ","ひ","","day","sun"]]""", 1).single().stats)
        assertEquals(TagInfo("v1", "partOfSpeech", -3, "Ichidan verb", 0), Yomitan.parseTagBank("""[["v1","partOfSpeech",-3,"Ichidan verb",0]]""").single())
    }

    @Test
    fun flattensStructuredContent() {
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"ul","content":[{"tag":"li","content":"to eat"},{"tag":"li","content":["to live on",{"tag":"br"},"(e.g. rice)"]}]},
            {"tag":"div","content":[{"tag":"ruby","content":["食",{"tag":"rt","content":"た"}]},"べる"]},
            {"tag":"img","path":"x.png"}
        ]}, "plain", ["食べる", ["v1"]]]"""
        assertEquals(
            listOf("• to eat\n• to live on\n(e.g. rice)\n食べる", "plain", "→ 食べる"),
            Yomitan.glossaryItems(glossary),
        )
    }

    @Test
    fun rendersJitendexEntriesLikeYomitan() {
        // Shape of Jitendex's 教える, shortened
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"ul","data":{"content":"sense-groups"},"content":[{"tag":"li","data":{"content":"sense-group"},"content":[
                {"tag":"span","data":{"class":"tag","content":"part-of-speech-info"},"content":"1-dan"},
                {"tag":"span","data":{"class":"tag","content":"part-of-speech-info"},"content":"transitive"},
                {"tag":"ol","content":[
                    {"tag":"li","style":{"listStyleType":"\"①\""},"data":{"content":"sense"},"content":[
                        {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"to teach"},{"tag":"li","content":"to instruct"}]},
                        {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"span","content":[{"tag":"ruby","content":["教",{"tag":"rt","content":"おし"}]},"えてくれます。"]}}
                    ]},
                    {"tag":"li","style":{"listStyleType":"\"②\""},"data":{"content":"sense"},"content":[
                        {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"to tell"}]}
                    ]}
                ]}
            ]}]}
        ]}]"""
        assertEquals(
            listOf("• 1-dan transitive\n① to teach; to instruct\n教えてくれます。\n② to tell"),
            Yomitan.glossaryItems(glossary),
        )
    }

    @Test
    fun separatesALabelFromTheGlossesAfterIt() {
        val glossary = """[{"type":"structured-content","content":[
            {"tag":"span","data":{"class":"tag","content":"misc-info"},"content":"slang"},
            {"tag":"ul","data":{"content":"glossary"},"content":[{"tag":"li","content":"marijuana"},{"tag":"li","content":"cannabis"}]}
        ]}]"""
        assertEquals(listOf("slang marijuana; cannabis"), Yomitan.glossaryItems(glossary))
    }

    @Test
    fun readsFrequenciesInAllShapes() {
        fun freq(data: String, reading: String? = "たべる") =
            Yomitan.frequency(TermMeta("食べる", "freq", "[$data]"), reading)

        assertEquals(Yomitan.Frequency(500, "500"), freq("500"))
        assertEquals(Yomitan.Frequency(500, "500㋕"), freq("\"500㋕\""))
        assertEquals(Yomitan.Frequency(7, "7 (rank)"), freq("""{"value":7,"displayValue":"7 (rank)"}"""))
        assertEquals(Yomitan.Frequency(42, "42"), freq("""{"reading":"たべる","frequency":42}"""))
        assertNull(freq("""{"reading":"くう","frequency":42}"""))
        // Any reading, for telling ranks from counts
        assertEquals(Yomitan.Frequency(42, "42"), freq("""{"reading":"くう","frequency":42}""", null))
        // The first number in a text, as Yomitan reads it
        assertEquals(Yomitan.Frequency(5000, "~5000"), freq("\"~5000\""))
        assertEquals(Yomitan.Frequency(null, "rare"), freq("\"rare\""))
        assertEquals(Yomitan.Frequency(12, "12.5"), freq("""{"value":12.5,"displayValue":"12.5"}"""))
    }

    @Test
    fun averagesFrequenciesWithTheHarmonicMean() {
        assertEquals(192, Yomitan.harmonicMean(listOf(120, 480)))
        assertEquals(7, Yomitan.harmonicMean(listOf(7)))
    }

    @Test
    fun readsPitchForTheMatchingReading() {
        val meta = TermMeta("昨日", "pitch", """[{"reading":"きのう","pitches":[{"position":2},{"position":0}]}]""")
        assertEquals("[2] [0]", Yomitan.pitch(meta, "きのう"))
        assertNull(Yomitan.pitch(meta, "さくじつ"))
    }
}
