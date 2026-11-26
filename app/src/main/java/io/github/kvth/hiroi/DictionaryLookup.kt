package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiDroidApi
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.Screenshot
import io.github.kvth.hiroi.anki.asScreenshot
import io.github.kvth.hiroi.audio.AudioPlayer
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.AudioSources
import io.github.kvth.hiroi.audio.wordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.Wildcard
import io.github.kvth.hiroi.dictionary.get
import io.github.kvth.hiroi.translation.Translations

/**
 * The results of a [LookupSession] in [web], a WebView, on the phone: the session's host and the
 * page's bridge to it. Used by the overlay and the search screen. [documentTitle] and
 * [screenshot] fill a card's {document-title} and {screenshot}.
 */
class DictionaryLookup(
    private val activity: Activity,
    private val documentTitle: () -> String,
    private val screenshot: () -> Bitmap?,
    /** The text selected where the word was looked up, for {popup-selection-text}. */
    private val selection: () -> String = { "" },
    /** Where on the screen the text is (its box or region), for a cropped {screenshot}; null for all of it */
    private val screenshotArea: () -> Rect? = { null },
) {
    private val anki = AnkiSettings(activity)
    private val audio = AudioSettings(activity)

    private val session = LookupSession(
        Host(),
        DictionaryDb.get(activity),
        DictionarySettings(activity),
        anki,
        audio,
        audio.wordAudio(activity),
        // Theme colours, so the results match the panel
        ResultsHtml.Colors(
            css(themeColor(android.R.attr.textColorPrimary)),
            css(themeColor(android.R.attr.textColorSecondary)),
            css(themeColor(android.R.attr.colorAccent)),
            css(themeColor(android.R.attr.colorBackground)),
        ),
    )

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        // Only Hiroi's own HTML: nothing from the network or files, script just for the page's buttons.
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(Bridge(), "Hiroi")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                pending.forEach { view.evaluateJavascript(it, null) }
                pending.clear()
            }
        }
        // One page for all results, which each lookup's script fills
        loadDataWithBaseURL(null, session.html.shell(), "text/html", "utf-8", null)
    }

    /** Whether the results page has loaded; scripts for it wait in [pending] until it has. */
    private var loaded = false
    private val pending = mutableListOf<String>()

    private val player by lazy { AudioPlayer(activity) }

    fun lookUp(text: String, offset: Int, onResult: (Lookup) -> Unit) = session.lookUp(text, offset, onResult)

    fun lookUpWildcard(wildcard: Wildcard, onResult: (Lookup) -> Unit) = session.lookUpWildcard(wildcard, onResult)

    fun lookUpLatin(query: String, kana: String?, correctRomaji: Boolean, onResult: (Lookup) -> Unit) =
        session.lookUpLatin(query, kana, correctRomaji, onResult)

    fun cancel() = session.cancel()

    fun back(): Boolean = session.back()

    fun show(found: Lookup.Found) = session.show(found)

    fun close() {
        session.close()
        player.release()
        web.destroy()
    }

    /** What the page sends ([ResultsHtml]'s hiroi()), handed to the session on the main thread */
    private inner class Bridge {
        @JavascriptInterface
        fun post(message: String) {
            activity.runOnUiThread { if (!activity.isDestroyed) session.handle(message) }
        }
    }

    private inner class Host : LookupHost {
        override fun onMain(action: () -> Unit) = activity.runOnUiThread { if (!activity.isDestroyed) action() }

        // A duplicate check can finish before the page has loaded
        override fun runScript(script: String) {
            if (loaded) web.evaluateJavascript(script, null) else pending += script
        }

        override fun text(text: LookupText, vararg args: Any): String = activity.getString(text.resource(), *args)

        override fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

        override fun log(message: String, error: Throwable?) {
            if (error == null) Log.d(TAG, message) else Log.w(TAG, message, error)
        }

        override fun documentTitle() = this@DictionaryLookup.documentTitle()

        override fun selection() = this@DictionaryLookup.selection()

        override fun screenshots(): Pair<Screenshot?, Screenshot?> {
            val full = screenshot() ?: return null to null
            return full.asScreenshot() to crop(full, screenshotArea()).asScreenshot()
        }

        override fun translate(sentence: String) = Translations(activity).translate(sentence).text

        override fun cardCreator() = CardCreator(activity, anki, ::clipboardText)

        /**
         * AnkiDroid's own link: Hiroi is in the foreground, so it may open it (AnkiConnect Android
         * in the background may not).
         */
        override fun browseInLocalAnki(query: String): Boolean {
            val intent = AnkiDroidApi.browseIntent(query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(activity.packageManager) == null) return false
            activity.startActivity(intent)
            return true
        }

        override fun play(audio: ByteArray, volume: Int) = player.play(audio, volume)

        override fun speak(text: String, volume: Int, onFailure: () -> Unit) = player.speak(text, volume, onFailure)

        override fun playFallback(sound: String, volume: Int) = player.playFallback(sound, volume)

        override fun audioSourceLabel(source: AudioSource) = AudioSources.label(activity, source)

        override fun choose(title: String, items: List<String>, onChosen: (Int) -> Unit) {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(items.toTypedArray()) { _, which -> onChosen(which) }
                .show()
        }
    }

    /** The clipboard's text, for {clipboard-text}; Hiroi is in the foreground, so it may read it. */
    private fun clipboardText(): String? =
        activity.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()

    /** [area] of [full] (screen coordinates) with a margin around it, within the picture; all of it without an area. */
    private fun crop(full: Bitmap, area: Rect?): Bitmap {
        if (area == null) return full
        val margin = (CROP_MARGIN_DP * activity.resources.displayMetrics.density).toInt()
        val inside = Rect(area.left - margin, area.top - margin, area.right + margin, area.bottom + margin)
        if (!inside.intersect(0, 0, full.width, full.height) || inside.isEmpty) return full
        return Bitmap.createBitmap(full, inside.left, inside.top, inside.width(), inside.height())
    }

    private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun themeColor(attr: Int): Int {
        val attrs = activity.obtainStyledAttributes(intArrayOf(attr))
        try {
            return attrs.getColor(0, Color.WHITE)
        } finally {
            attrs.recycle()
        }
    }

    private companion object {
        const val TAG = "HiroiLookup"
        const val CROP_MARGIN_DP = 12

        fun LookupText.resource(): Int = when (this) {
            LookupText.SEARCH_PREPARING_SUFFIX -> R.string.search_preparing_suffix
            LookupText.SECTION_JAPANESE -> R.string.section_japanese
            LookupText.SECTION_MEANING -> R.string.section_meaning
            LookupText.SECTION_DID_YOU_MEAN -> R.string.section_did_you_mean
            LookupText.NO_MATCH -> R.string.no_match
            LookupText.SEARCH_MEANING_UNAVAILABLE -> R.string.search_meaning_unavailable
            LookupText.NO_DICTIONARIES -> R.string.no_dictionaries
            LookupText.NO_ENABLED_DICTIONARIES -> R.string.no_enabled_dictionaries
            LookupText.NOT_JAPANESE -> R.string.not_japanese
            LookupText.LOOKUP_FAILED -> R.string.lookup_failed
            LookupText.AUDIO_NO_TTS -> R.string.audio_no_tts
            LookupText.AUDIO_MENU_NONE -> R.string.audio_menu_none
            LookupText.AUDIO_MENU_TITLE -> R.string.audio_menu_title
            LookupText.ANKI_IN_DECK -> R.string.anki_in_deck
            LookupText.ANKI_UNREACHABLE -> R.string.anki_unreachable
            LookupText.ANKI_DUPLICATE -> R.string.anki_duplicate
            LookupText.ANKI_OVERWRITE -> R.string.anki_overwrite
            LookupText.ANKI_ADD_AGAIN -> R.string.anki_add_again
            LookupText.ANKI_OPEN_NOTES -> R.string.anki_open_notes
            LookupText.ANKI_OPEN -> R.string.anki_open
            LookupText.ANKI_OVERWRITING -> R.string.anki_overwriting
            LookupText.ANKI_ADDING -> R.string.anki_adding
            LookupText.ANKI_TEMPLATE_ERRORS -> R.string.anki_template_errors
            LookupText.DEDUP_UPDATE_FAILED_TOAST -> R.string.dedup_update_failed_toast
            LookupText.DEDUP_UPDATED_TOAST -> R.string.dedup_updated_toast
            LookupText.DEDUP_CHANGED_TOAST -> R.string.dedup_changed_toast
            LookupText.ANKI_OVERWRITTEN -> R.string.anki_overwritten
            LookupText.ANKI_ADDED -> R.string.anki_added
            LookupText.ANKI_ADD -> R.string.anki_add
            LookupText.ANKI_ADD_FAILED -> R.string.anki_add_failed
        }
    }
}
