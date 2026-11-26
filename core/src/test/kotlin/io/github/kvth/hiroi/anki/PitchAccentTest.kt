package io.github.kvth.hiroi.anki

import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.TermMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchAccentTest {
    private fun pitch(position: Int, nasal: Set<Int> = emptySet(), devoice: Set<Int> = emptySet()) = PitchAccent.Pitch(position, null, nasal, devoice, emptyList())

    private fun term(vararg meta: TermMeta) = TermGroup("鏡", "かがみ", "鏡", emptyList(), emptyList(), meta.toList())

    @Test
    fun groupsThePitchAccentsOfTheReadingByDictionary() {
        val groups = PitchAccent.groups(
            term(
                TermMeta("鏡", "pitch", """[{"reading":"かがみ","pitches":[{"position":3,"nasal":2,"tags":["名"]}]}]""", "NHK"),
                TermMeta("鏡", "pitch", """[{"reading":"きょう","pitches":[{"position":1}]}]""", "NHK"),
                TermMeta("鏡", "pitch", """[{"reading":"かがみ","pitches":[{"position":3},{"position":"LHH"}]}]""", "Kanjium"),
                TermMeta("鏡", "freq", "[100]", "JPDB"),
            ),
        )
        assertEquals(listOf("NHK", "Kanjium"), groups.map { it.dictionary })
        assertEquals(listOf(PitchAccent.Pitch(3, null, setOf(2), emptySet(), listOf("名"))), groups[0].pitches)
        assertEquals(listOf(pitch(3), PitchAccent.Pitch(0, "LHH", emptySet(), emptySet(), emptyList())), groups[1].pitches)
    }

    @Test
    fun drawsTheLineOverTheHighMoraeAsYomitan() {
        val text = PitchAccent.text("とどく", pitch(2))
        assertTrue(text, "data-position=\"0\" data-pitch=\"low\" data-pitch-next=\"high\"><span class=\"pronunciation-character\">と</span>" in text)
        assertTrue(text, "data-position=\"1\" data-pitch=\"high\" data-pitch-next=\"low\"><span class=\"pronunciation-character\">ど</span>" in text)
        assertTrue(text, "data-position=\"2\" data-pitch=\"low\" data-pitch-next=\"low\">" in text)
        // Small kana belong to the mora before them
        assertEquals(2, Regex("pronunciation-mora\"").findAll(PitchAccent.text("きょう", pitch(1))).count())
    }

    @Test
    fun marksNasalAndDevoicedMorae() {
        val nasal = PitchAccent.text("かがみ", pitch(3, nasal = setOf(2)))
        assertTrue(nasal, "data-nasal=\"true\"><span class=\"pronunciation-character-group\"><span class=\"pronunciation-character\">か</span>" +
            "<span class=\"pronunciation-nasal-diacritic\">゚</span><span class=\"pronunciation-nasal-indicator\"></span></span>" in nasal)
        val devoiced = PitchAccent.text("すき", pitch(2, devoice = setOf(1)))
        assertTrue(devoiced, "data-devoice=\"true\"><span class=\"pronunciation-character\">す</span><span class=\"pronunciation-devoice-indicator\"></span>" in devoiced)
    }

    @Test
    fun graphsThePitchWithTheParticleAfterTheWord() {
        val graph = PitchAccent.graph("とどく", pitch(2))
        assertTrue(graph, "viewBox=\"0 0 200 100\"" in graph)
        assertTrue(graph, "<path class=\"pronunciation-graph-line\" d=\"M25 75 L75 25 L125 75\"/>" in graph)
        assertTrue(graph, "<path class=\"pronunciation-graph-line-tail\" d=\"M125 75 L175 75\"/>" in graph)
        assertTrue("the last high mora is ringed", "<circle class=\"pronunciation-graph-dot-downstep1\" cx=\"75\" cy=\"25\" r=\"15\"/>" in graph)
        assertTrue(graph, "transform=\"translate(175,75)\"" in graph)
        // Heiban: the particle stays high
        assertTrue(PitchAccent.graph("とどく", pitch(0)).contains("translate(175,25)"))
    }

    @Test
    fun writesTheDownstepPosition() {
        assertTrue(PitchAccent.position(pitch(2)).contains("<span class=\"pronunciation-downstep-notation-number\">2</span>"))
        assertEquals("2", PitchAccent.Pitch(0, "LHL", emptySet(), emptySet(), emptyList()).positionText)
        assertEquals("0", PitchAccent.Pitch(0, "LHH", emptySet(), emptySet(), emptyList()).positionText)
    }

    @Test
    fun showsTheFormsAsked() {
        val both = PitchAccent.html("とどく", pitch(2), PitchAccent.Display())
        assertTrue(both.indexOf("pronunciation-text") in 0 until both.indexOf("pronunciation-downstep-notation"))
        assertFalse(both, "<svg" in both)
        val graph = PitchAccent.html("とどく", pitch(2), PitchAccent.Display(text = false, position = false, graph = true))
        assertTrue(graph.startsWith("<svg"))
    }
}
