package io.github.kvth.hiroi

/** A rectangle on the screen, in pixels */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val area get() = maxOf(0, right - left).toLong() * maxOf(0, bottom - top)

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/**
 * What the screen showed about where its text is from, besides the text: the page or book's
 * [title] for {document-title}, the page's [url] read off the address bar for {url}, where the
 * [pictures] are for {screenshot-picture}, and where a video was ([playbackTime]) for {playback-time}.
 */
data class ScreenSource(
    val title: String? = null,
    val url: String? = null,
    val pictures: List<Box> = emptyList(),
    val playbackTime: String? = null,
) {
    /**
     * The picture the text in [area] is on (the manga page, a panel): the smallest one around its
     * middle that's no smaller than it, so not an icon next to it. Without an area (all boxes),
     * the biggest picture. Null if there's none.
     */
    fun pictureAround(area: Box?): Box? {
        if (area == null) return pictures.maxByOrNull { it.area }
        val x = (area.left + area.right) / 2
        val y = (area.top + area.bottom) / 2
        return pictures.filter { it.contains(x, y) && it.area >= area.area }.minByOrNull { it.area }
    }
}

/**
 * A view of the app the text is from, as the accessibility tree or the assistant's view
 * structure has it: [viewId] is its ID's name without the package ("url_bar"); [hint]: its
 * text is only the hint of an empty field.
 */
class SourceNode(
    val className: String?,
    val viewId: String?,
    val text: String?,
    val description: String?,
    /** What a control says about its state (a seek bar's position, on Android 11 and later) */
    val state: String?,
    val box: Box,
    val editable: Boolean = false,
    val hint: Boolean = false,
)

/**
 * Makes a [ScreenSource] from the views of the app the text is from, each [visit]ed before its
 * children; [inToolbar] if it's inside a view [toolbar] said is one.
 */
class ScreenSourceCollector {
    private var webTitle: String? = null
    private var toolbarTitle: String? = null
    private var url: String? = null
    private val pictures = mutableListOf<Box>()
    private var positionTime: String? = null
    private var seekBarTime: String? = null
    private var elapsedTime: String? = null

    /** Whether [node] is an app bar, whose first text is the title of what's open (a book, an episode) */
    fun toolbar(node: SourceNode) = node.viewId in TOOLBAR_IDS

    fun visit(node: SourceNode, inToolbar: Boolean) {
        val text = node.text?.trim()?.takeIf { it.isNotEmpty() && !node.hint }
        val description = node.description?.trim()?.takeIf { it.isNotEmpty() }
        val className = node.className.orEmpty()
        // A browser's page (Chrome's, or a WebView in an app) is named after its <title>
        if (className == WEB_VIEW && webTitle == null) webTitle = text ?: description
        if (inToolbar && toolbarTitle == null && !node.editable && text != null) toolbarTitle = text
        if (node.viewId in AddressBar.VIEW_IDS && url == null && text != null) url = AddressBar.url(text)
        if ("Image" in className && "Button" !in className && pictures.size < MAX_PICTURES && node.box.area > 0) pictures += node.box
        if (node.viewId in PLAYER_POSITION_IDS && positionTime == null && text != null) positionTime = PlaybackTime.first(text)
        if (("SeekBar" in className || "Slider" in className) && seekBarTime == null) {
            seekBarTime = listOfNotNull(description, node.state, text).firstNotNullOfOrNull(PlaybackTime::first)
        }
        if (elapsedTime == null && text != null) elapsedTime = PlaybackTime.elapsedOfTotal(text)
    }

    /** What was found, with the app window's [windowTitle] as the title if the views had none */
    fun result(windowTitle: String? = null) = ScreenSource(
        title = listOf(webTitle, toolbarTitle, windowTitle).firstNotNullOfOrNull(::title),
        url = url,
        pictures = pictures.toList(),
        playbackTime = positionTime ?: seekBarTime ?: elapsedTime,
    )

    /** [candidate] if it's a title: not an activity's class name or an address, which windows and pages without one show */
    private fun title(candidate: String?): String? = candidate?.trim()?.takeIf {
        it.isNotEmpty() && !CLASS_NAME.matches(it) && AddressBar.url(it) == null
    }

    companion object {
        const val WEB_VIEW = "android.webkit.WebView"

        /** App bars' IDs: Material's and AppCompat's toolbar, the framework's action bar, and titles in them */
        val TOOLBAR_IDS = setOf("toolbar", "action_bar", "toolbar_title")

        /** Video players' elapsed time: ExoPlayer's and Media3's (most players on Android), VLC's */
        val PLAYER_POSITION_IDS = setOf("exo_position", "player_overlay_time")

        private val CLASS_NAME = Regex("^[\\w$]+(\\.[\\w$]+)+$")
        private const val MAX_PICTURES = 500
    }
}

/** Where a video (or audio) was, read from its player's controls */
object PlaybackTime {
    private val TIME = Regex("(?<![\\d:])(\\d{1,2}:)?\\d{1,2}:\\d{2}(?![\\d:])")
    private val ELAPSED_OF_TOTAL = Regex("^\\s*($TIME)\\s*/\\s*$TIME\\s*$")

    /** The first time (12:34 or 1:02:03) in [text]: a seek bar's "12:34 of 23:40" or the elapsed time's "12:34" */
    fun first(text: String): String? = TIME.find(text)?.value

    /** The elapsed time from text that's just "elapsed / total", as players show it: "12:34 / 23:40" */
    fun elapsedOfTotal(text: String): String? = ELAPSED_OF_TOTAL.find(text)?.groupValues?.get(1)
}
