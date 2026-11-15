package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YomitanTest {
    @Test
    fun parsesIndexOfEitherVersion() {
        assertEquals(Yomitan.Index("Jitendex", "2026.11.01", 3), Yomitan.parseIndex("""{"title":"Jitendex","revision":"2026.11.01","format":3}"""))
        assertEquals(1, Yomitan.parseIndex("""{"title":"Old","revision":"1","version":1}""").format)
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
    fun readsFrequenciesInAllShapes() {
        fun freq(data: String, reading: String = "たべる") =
            Yomitan.frequency(TermMeta("食べる", "freq", "[$data]"), reading)

        assertEquals(Yomitan.Frequency(500, "500"), freq("500"))
        assertEquals(Yomitan.Frequency(500, "500㋕"), freq("\"500㋕\""))
        assertEquals(Yomitan.Frequency(7, "7 (rank)"), freq("""{"value":7,"displayValue":"7 (rank)"}"""))
        assertEquals(Yomitan.Frequency(42, "42"), freq("""{"reading":"たべる","frequency":42}"""))
        assertNull(freq("""{"reading":"くう","frequency":42}"""))
    }

    @Test
    fun readsPitchForTheMatchingReading() {
        val meta = TermMeta("昨日", "pitch", """[{"reading":"きのう","pitches":[{"position":2},{"position":0}]}]""")
        assertEquals("[2] [0]", Yomitan.pitch(meta, "きのう"))
        assertNull(Yomitan.pitch(meta, "さくじつ"))
    }
}
