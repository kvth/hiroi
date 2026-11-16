package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.DuplicateNoteException
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Translator
import java.util.concurrent.Executors

/**
 * Looks words up in the imported dictionaries and shows them in [web] as a Yomitan-style results
 * page, with its "＋ Anki" and "Open in Anki" buttons. Used by the overlay and the search screen.
 * [documentTitle] and [screenshot] fill a card's {document-title} and {screenshot}.
 */
class DictionaryLookup(
    private val activity: Activity,
    private val documentTitle: () -> String,
    private val screenshot: () -> Bitmap?,
) {
    sealed interface Lookup {
        class Message(val text: String) : Lookup

        /**
         * [text] is the text the lookup ran on (for a card's sentence), [html] the results page,
         * [notes] each result's Anki notes (by index), growing as cards are added.
         */
        class Found(val result: LookupResult, val text: String, val html: String, val notes: MutableMap<Int, MutableList<Long>>) : Lookup
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val dictionaries by lazy { DictionaryDb.get(activity) }
    val translator by lazy { Translator(dictionaries) { DictionarySettings(activity).frequencySort(dictionaries) } }
    private val styles by lazy { dictionaries.styles() } // read on the lookup thread
    private var generation = 0
    private var shown: Lookup.Found? = null

    // Theme colours are resolved here; the HTML itself is made on the lookup thread.
    private val html = ResultsHtml(
        ResultsHtml.Colors(
            css(themeColor(android.R.attr.textColorPrimary)),
            css(themeColor(android.R.attr.textColorSecondary)),
            css(themeColor(android.R.attr.colorAccent)),
        ),
    )

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        // Only Hiroi's own HTML: nothing from the network or files, script just for the Anki buttons.
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(AnkiBridge(), "Hiroi")
    }

    /**
     * Looks up the word at [offset] in [text] and calls [onResult] on the main thread, unless
     * another lookup or [cancel] came in between.
     */
    fun lookUp(text: String, offset: Int, onResult: (Lookup) -> Unit) {
        val generation = ++generation
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            // Formatting big structured-content glossaries takes a while too, so it happens here as well.
            val outcome = runCatching {
                when {
                    !dictionaries.hasDictionaries() -> Lookup.Message(activity.getString(R.string.no_dictionaries))
                    else -> when (val result = translator.lookUp(text, offset)) {
                        null -> Lookup.Message(activity.getString(R.string.not_japanese))
                        else -> if (result.terms.isEmpty() && result.kanji.isEmpty()) {
                            Lookup.Message(activity.getString(R.string.no_match))
                        } else {
                            val cards = AnkiSettings(activity).cardFormat.complete
                            val anki = ankiStatus(result)
                            val frequencies = ResultsHtml.Frequencies(
                                average = DictionarySettings(activity).averageFrequencies,
                                occurrenceBased = dictionaries.dictionaries().filter { it.frequencyMode == "occurrence-based" }.mapTo(HashSet()) { it.title },
                            )
                            Lookup.Found(
                                result, text, html.document(result, anki, styles, cards, frequencies),
                                notes = result.terms.indices.associateWithTo(HashMap()) { i ->
                                    anki?.notes?.get(result.terms[i].expression).orEmpty().toMutableList()
                                },
                            )
                        }
                    }
                }
            }.getOrElse {
                Log.e(TAG, "Lookup failed", it)
                Lookup.Message(activity.getString(R.string.lookup_failed, it.message ?: it.javaClass.simpleName))
            }
            Log.d(TAG, "Lookup at $offset took ${SystemClock.elapsedRealtime() - started} ms")
            activity.runOnUiThread {
                if (generation == this.generation && !activity.isDestroyed) onResult(outcome)
            }
        }
    }

    /** Drops the lookup in progress, and the shown results' buttons. */
    fun cancel() {
        generation++
        shown = null
    }

    /** Shows [found] in [web]. */
    fun show(found: Lookup.Found) {
        shown = found
        web.loadDataWithBaseURL(null, found.html, "text/html", "utf-8", null)
    }

    fun close() {
        worker.shutdownNow()
        web.destroy()
    }

    /** Asks Anki which of the headwords already have a note, if a deck is set up. */
    private fun ankiStatus(result: LookupResult): AnkiStatus? {
        val anki = AnkiSettings(activity)
        if (!anki.enabled || result.terms.isEmpty()) return null
        return try {
            AnkiStatus(anki.client().notesInDeck(anki.deck, anki.field, result.terms.map { it.expression }.distinct()))
        } catch (e: Exception) {
            Log.w(TAG, "Anki check failed", e)
            AnkiStatus(emptyMap(), activity.getString(R.string.anki_unreachable, e.message ?: e.javaClass.simpleName))
        }
    }

    /** The results page's "＋ Anki" buttons, by result index. */
    private inner class AnkiBridge {
        @JavascriptInterface
        fun addCard(index: Int) {
            activity.runOnUiThread { this@DictionaryLookup.addCard(index) }
        }

        @JavascriptInterface
        fun openNote(index: Int) {
            activity.runOnUiThread { this@DictionaryLookup.openNote(index) }
        }
    }

    /** Makes a note from result [index] with the card format; its button then shows how it went. */
    private fun addCard(index: Int) {
        val found = shown ?: return
        val term = found.result.terms.getOrNull(index) ?: return
        fun button(label: Int, enabled: Boolean) =
            web.evaluateJavascript("setCard($index, '${activity.getString(label)}', $enabled)", null)
        button(R.string.anki_adding, false)
        val start = found.result.start
        val data = CardData(
            term, found.text, start, start + term.source.length,
            documentTitle = documentTitle(),
            furigana = { sentence ->
                Furigana.forText(sentence) { text, i ->
                    translator.lookUp(text, i)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) }
                }
            },
        )
        val screenshot = screenshot()
        worker.execute {
            val result = runCatching { CardCreator(AnkiSettings(activity)).add(data, screenshot) }
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                result.fold(
                    { noteId ->
                        found.notes.getOrPut(index) { mutableListOf() } += noteId
                        button(R.string.anki_added, false)
                        web.evaluateJavascript("inAnki($index, '${activity.getString(R.string.anki_in_deck)}')", null)
                    },
                    { error ->
                        if (error is DuplicateNoteException) {
                            button(R.string.anki_duplicate, false)
                        } else {
                            Log.w(TAG, "Adding a card failed", error)
                            button(R.string.anki_add, true)
                            toast(activity.getString(R.string.anki_add_failed, error.message ?: error.javaClass.simpleName))
                        }
                    },
                )
            }
        }
    }

    /**
     * Shows result [index]'s notes in Anki's card browser: AnkiDroid's own link when Anki is on
     * this phone (Hiroi is in the foreground, so it may open it; AnkiConnect Android in the
     * background may not), AnkiConnect's guiBrowse for desktop Anki.
     */
    private fun openNote(index: Int) {
        val ids = shown?.notes?.get(index).orEmpty()
        if (ids.isEmpty()) return
        val query = "nid:" + ids.joinToString(",")
        val settings = AnkiSettings(activity)
        val local = Uri.parse(settings.url).host in setOf("127.0.0.1", "localhost")
        val ankiDroid = Intent(Intent.ACTION_VIEW, Uri.parse("anki://x-callback-url/browser?search=" + Uri.encode(query)))
            .setPackage(ANKIDROID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (local && ankiDroid.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(ankiDroid)
            return
        }
        worker.execute {
            val result = runCatching { settings.client().guiBrowse(query) }
            result.exceptionOrNull()?.let { error ->
                activity.runOnUiThread { toast(activity.getString(R.string.anki_unreachable, error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

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
        const val ANKIDROID = "com.ichi2.anki"
    }
}
