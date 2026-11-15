package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkersTest {
    private fun entry(dictionary: String, glossary: String, tags: String = "", rules: String = "v1") =
        TermEntry("食べる", "たべる", tags, rules, 0, glossary, 0, "", dictionary, 1)

    private val term = TermGroup(
        expression = "食べる", reading = "たべる", source = "食べなかった", inflections = listOf("negative", "-た"),
        entries = listOf(
            entry("Jitendex", """[{"type":"structured-content","content":{"tag":"span","data":{"class":"tag"},"style":{"fontSize":"0.8em"},"content":"to eat"}}]""", "★"),
            entry("JMdict", """["to eat", "to live on"]"""),
        ),
        meta = listOf(
            TermMeta("食べる", "freq", "[120]", "JPDB", 2),
            TermMeta("食べる", "freq", """[{"reading":"たべる","frequency":{"value":480,"displayValue":"480"}}]""", "BCCWJ", 3),
            TermMeta("食べる", "pitch", """[{"reading":"たべる","pitches":[{"position":2}]}]""", "Kanjium", 4),
        ),
    )
    private val data = CardData(term, "昨日、パンを食べなかった。今日は食べる。", 6, 12, "Kindle")

    @Test
    fun fillsTheBasicMarkers() {
        assertEquals("食べる", Markers.value("expression", data))
        assertEquals("食[た]べる", Markers.value("furigana-plain", data))
        assertEquals("<ruby>食<rt>た</rt></ruby>べる", Markers.value("furigana", data))
        assertEquals("negative « -た", Markers.value("conjugation", data))
        assertEquals("Ichidan verb", Markers.value("part-of-speech", data))
        assertEquals("Jitendex", Markers.value("dictionary", data))
        assertEquals("Kindle", Markers.value("document-title", data))
        assertNull(Markers.value("jpmn-primary-definition", data))
    }

    @Test
    fun cutsTheSentenceAroundTheWord() {
        assertEquals("昨日、パンを食べなかった。", Markers.value("sentence", data))
        assertEquals("昨日、パンを", Markers.value("cloze-prefix", data))
        assertEquals("食べなかった", Markers.value("cloze-body", data))
        assertEquals("。", Markers.value("cloze-suffix", data))
        assertEquals("たべなかった", Markers.value("cloze-body-kana", data))
    }

    @Test
    fun rendersTheGlossaryLikeYomitan() {
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>" +
                "<li data-dictionary=\"Jitendex\">(<i>★, Jitendex</i>) <span data-sc-class=\"tag\" style=\"font-size:0.8em\">to eat</span></li>" +
                "<li data-dictionary=\"JMdict\">(<i>JMdict</i>) <ul><li>to eat</li><li>to live on</li></ul></li>" +
                "</ol></div>",
            Markers.value("glossary", data),
        )
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><span data-sc-class=\"tag\" style=\"font-size:0.8em\">to eat</span></div>",
            Markers.value("glossary-first-brief", data),
        )
    }

    @Test
    fun spacesJitendexLabels() {
        val labels = """{"type":"structured-content","content":[
            {"tag":"span","data":{"class":"tag"},"content":"5-dan"},{"tag":"span","data":{"class":"tag"},"content":"intransitive"}]}"""
        assertEquals(
            "<span data-sc-class=\"tag\">5-dan</span> <span data-sc-class=\"tag\">intransitive</span>",
            StructuredHtml.item(org.json.JSONObject(labels)),
        )
    }

    @Test
    fun computesFrequenciesAndPitch() {
        assertEquals("<ul style=\"text-align: left;\"><li>JPDB: 120</li><li>BCCWJ: 480</li></ul>", Markers.value("frequencies", data))
        assertEquals("192", Markers.value("frequency-harmonic-rank", data)) // 2 / (1/120 + 1/480)
        assertEquals("300", Markers.value("frequency-average-rank", data))
        assertEquals("2", Markers.value("pitch-accent-positions", data))
        assertEquals("kifuku", Markers.value("pitch-accent-categories", data)) // a verb with a downstep
        assertEquals("た<span style=\"text-decoration:overline;\">べ</span>ꜜる", Markers.value("pitch-accents", data))
    }

    @Test
    fun rendersTemplatesWithTextAndMedia() {
        assertEquals(
            "昨日、パンを<b>食べなかった</b>。 [sound:a.mp3]",
            Markers.render("{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix} {audio}{jpmn-unknown}", data, mapOf("audio" to "[sound:a.mp3]")),
        )
        assertEquals(setOf("cloze-prefix", "audio"), Markers.used("{cloze-prefix} and {audio}"))
    }
}
