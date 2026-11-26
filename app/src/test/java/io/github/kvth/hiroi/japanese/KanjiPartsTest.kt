package io.github.kvth.hiroi.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KanjiPartsTest {
    private val sample = KanjiParts(
        """
        # comment
        働	⿰亻動	1
        動	⿰重力	1
        休	⿰亻木	1
        体	⿰亻本	1
        林	⿰木木	1
        慟	⿰忄動	2
        都	⿰者阝	1
        陸	⿰阝坴	1
        鬱	⿳⿲木缶木冖⿰鬯彡	2
        丷	⿰①②
        """.trimIndent(),
    )

    @Test
    fun partsInOrderWithTheKanjiTheyAreFormsOf() {
        assertEquals(listOf(KanjiParts.Part("亻", "人"), KanjiParts.Part("動", "動")), sample.parts("働"))
        assertEquals("亻(人) + 動", sample.line("働"))
        assertEquals("木 + 木", sample.line("林"))
        // Nested descriptions: all their parts, in order
        assertEquals("木 + 缶 + 木 + 冖 + 鬯 + 彡", sample.line("鬱"))
        // 阝: 阜 on the left, 邑 on the right
        assertEquals("阝(阜) + 坴", sample.line("陸"))
        assertEquals("者 + 阝(邑)", sample.line("都"))
        // Parts without a character of their own
        assertEquals("… + …", sample.line("丷"))
        assertNull(sample.parts("丷").first().kanji)
        assertNull(sample.line("木"))
    }

    @Test
    fun usedInTheCommonestFirstWithTheFormsToo() {
        assertEquals(listOf("働", "慟"), sample.usedIn("動"))
        // 人's kanji are those with 亻
        assertEquals(listOf("休", "体", "働"), sample.usedIn("人"))
        assertEquals(listOf("休", "林", "鬱"), sample.usedIn("木"))
        assertTrue(sample.has("木"))
        assertTrue(sample.has("力"))
        assertFalse(sample.has("犬"))
    }

    @Test
    fun theAppsData() {
        val parts = KanjiParts(File("src/main/assets/kanji-parts.txt").readText())
        assertEquals("亻(人) + 動", parts.line("働"))
        assertEquals("重 + 力", parts.line("動"))
        // 𧈧, which phones' fonts don't have, as its own parts
        assertEquals("弓 + 厶 + 虫", parts.line("強"))
        assertTrue("休" in parts.usedIn("人").take(24))
        // The common ones first
        assertTrue(parts.usedIn("寺").take(8).containsAll(listOf("持", "時", "特", "等", "待")))
    }
}
