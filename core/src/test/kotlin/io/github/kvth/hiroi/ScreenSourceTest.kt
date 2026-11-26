package io.github.kvth.hiroi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenSourceTest {
    private val screen = Box(0, 0, 1080, 2400)

    private fun node(
        className: String? = "android.widget.TextView", viewId: String? = null, text: String? = null, description: String? = null,
        state: String? = null, box: Box = screen, editable: Boolean = false, hint: Boolean = false,
    ) = SourceNode(className, viewId, text, description, state, box, editable, hint)

    /** Visits [nodes] as a tree given by indentation: each pair is its depth and the node */
    private fun collect(vararg nodes: Pair<Int, SourceNode>, windowTitle: String? = null): ScreenSource {
        val collector = ScreenSourceCollector()
        val toolbars = ArrayDeque<Int>() // depths of the app bars the next node is in
        for ((depth, n) in nodes) {
            while (toolbars.isNotEmpty() && toolbars.last() >= depth) toolbars.removeLast()
            val inToolbar = toolbars.isNotEmpty() || collector.toolbar(n)
            if (collector.toolbar(n)) toolbars.addLast(depth)
            collector.visit(n, inToolbar)
        }
        return collector.result(windowTitle)
    }

    @Test
    fun aBrowsersPageGivesTitleAndAddress() {
        val source = collect(
            0 to node("android.widget.FrameLayout"),
            1 to node("android.view.ViewGroup", viewId = "toolbar"),
            2 to node("android.widget.EditText", viewId = "url_bar", text = "kvth.github.io/mokuro-reader/#/reader/x", editable = true),
            1 to node(ScreenSourceCollector.WEB_VIEW, text = "そこは魅惑の 指名キャスト"),
            2 to node("android.widget.Image", box = Box(0, 300, 1080, 1900)),
            2 to node(text = "無防備に", box = Box(100, 400, 300, 600)),
            windowTitle = "com.google.android.apps.chrome.Main",
        )
        assertEquals("そこは魅惑の 指名キャスト", source.title)
        assertEquals("https://kvth.github.io/mokuro-reader/#/reader/x", source.url)
        assertEquals(listOf(Box(0, 300, 1080, 1900)), source.pictures)
    }

    @Test
    fun anAppsTitleComesFromItsAppBarElseItsWindow() {
        val reader = collect(
            0 to node("android.view.ViewGroup", viewId = "toolbar"),
            1 to node("android.widget.ImageButton", description = "Back"),
            1 to node(text = "よつばと！ 1"),
            1 to node(text = "第1話"),
            0 to node(text = "本文"),
            windowTitle = "Reader",
        )
        assertEquals("よつばと！ 1", reader.title)
        // Text after the app bar isn't in it
        assertNull(collect(0 to node("android.view.ViewGroup", viewId = "toolbar"), 0 to node(text = "本文")).title)
        assertEquals("Reader", collect(0 to node(text = "本文"), windowTitle = "Reader").title)
        // Class names and addresses aren't titles
        assertNull(collect(0 to node(text = "本文"), windowTitle = "com.example.MainActivity").title)
        assertNull(collect(0 to node(ScreenSourceCollector.WEB_VIEW, text = "example.com/page")).title)
    }

    @Test
    fun anEmptyAddressBarHasNoAddress() {
        assertNull(collect(0 to node("android.widget.EditText", viewId = "url_bar", text = "Search or type web address", editable = true, hint = true)).url)
        assertNull(collect(0 to node("android.widget.EditText", viewId = "mozac_browser_toolbar_url_view", text = "猫 画像", editable = true)).url)
    }

    @Test
    fun thePlaybackTimeComesFromThePlayersControls() {
        // ExoPlayer's elapsed time first, then a seek bar's description, then "elapsed / total" text
        assertEquals("12:34", collect(
            0 to node(text = "0:05 / 23:40"),
            0 to node("android.widget.SeekBar", description = "3:00"),
            0 to node(viewId = "exo_position", text = "12:34"),
            0 to node(viewId = "exo_duration", text = "23:40"),
        ).playbackTime)
        assertEquals("1:02:03", collect(0 to node("android.widget.SeekBar", description = "1:02:03 of 1:30:00")).playbackTime)
        assertEquals("4:56", collect(0 to node("android.widget.SeekBar", state = "4:56")).playbackTime)
        assertEquals("0:05", collect(0 to node(text = "0:05 / 23:40")).playbackTime)
        // A clock, a page slider or an episode's length is no playback time
        assertNull(collect(0 to node(text = "23:12"), 0 to node("android.widget.SeekBar", description = "Page 12 of 200"), 0 to node(text = "24分")).playbackTime)
    }

    @Test
    fun thePictureIsTheSmallestAroundTheText() {
        val page = Box(0, 300, 1080, 1900)
        val panel = Box(0, 300, 540, 900)
        val icon = Box(150, 450, 200, 500)
        val source = ScreenSource(pictures = listOf(page, panel, icon))
        assertEquals(panel, source.pictureAround(Box(100, 400, 300, 600)))
        assertEquals(page, source.pictureAround(Box(600, 1000, 700, 1200)))
        assertNull(source.pictureAround(Box(100, 2000, 300, 2100)))
        // All boxes: the biggest picture
        assertEquals(page, source.pictureAround(null))
        assertNull(ScreenSource().pictureAround(null))
    }

    @Test
    fun findsTimesAsPlayersShowThem() {
        assertEquals("12:34", PlaybackTime.first("12:34"))
        assertEquals("0:12", PlaybackTime.first("elapsed time: 0:12, total time: 3:40"))
        assertNull(PlaybackTime.first("2026-11-26"))
        assertNull(PlaybackTime.first("123:45:678"))
        assertEquals("1:02:03", PlaybackTime.elapsedOfTotal("1:02:03 / 1:30:00"))
        assertNull(PlaybackTime.elapsedOfTotal("12:34"))
        assertNull(PlaybackTime.elapsedOfTotal("Ep. 3 12:34 / 23:40"))
    }
}
