package io.github.kvth.hiroi

/**
 * A browser's address bar, read off the screen for a card's {url} when the browser didn't tell
 * the assistant its page's address itself.
 */
object AddressBar {
    /**
     * The address bar's view ID in the browsers' layouts, without the package (forks keep their
     * browser's): Chrome and Chromium browsers (Brave, Edge, Kiwi, Vivaldi, …), Firefox and its
     * forks, Samsung Internet, Opera, DuckDuckGo.
     */
    val VIEW_IDS = listOf("url_bar", "mozac_browser_toolbar_url_view", "location_bar_edit_text", "url_field", "omnibarTextInput")

    private val WEB = Regex("^(https?|file)://", RegexOption.IGNORE_CASE)
    private val HOST = Regex("^(localhost|[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+)(:\\d+)?([/?#].*)?$")

    /**
     * The address in an address bar's [text], with the https:// that browsers leave out put back;
     * null for anything else there (search words, "Search or type web address").
     */
    fun url(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any(Char::isWhitespace)) return null
        if (WEB.containsMatchIn(t)) return t
        // Not a page from the web (about:blank, chrome://…, view-source:…) or not an address at all
        return if (HOST.matches(t)) "https://$t" else null
    }
}
