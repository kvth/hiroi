package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.DuplicateNoteException
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.audio.AudioInfo
import io.github.kvth.hiroi.audio.AudioPlayer
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSources
import io.github.kvth.hiroi.audio.WordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Translator
import io.github.kvth.hiroi.dictionary.Wildcard
import io.github.kvth.hiroi.translation.Translations
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
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
    /** The text selected where the word was looked up, for {popup-selection-text}. */
    private val selection: () -> String = { "" },
    /** Where on the screen the text is (its box or region), for a cropped {screenshot}; null for all of it */
    private val screenshotArea: () -> Rect? = { null },
) {
    sealed interface Lookup {
        class Message(val text: String) : Lookup

        /**
         * [text] is the text the lookup ran on (for a card's sentence), [script] what shows the
         * results in the page (their beginning: the rest is added once it shows) and the styles
         * of the dictionaries in [styles], [notes] each result's Anki notes (by index), known
         * once Anki was asked and growing as cards are added.
         */
        class Found(
            val result: LookupResult,
            val text: String,
            val script: String,
            val styles: Set<String>,
            val notes: MutableMap<Int, MutableList<Long>>,
        ) : Lookup {
            /** Each result's existing notes with the same first field (Yomitan's duplicate check), by index. */
            val duplicates = HashMap<Int, List<Long>>()

            /** The recording chosen in a result's audio menu, by index: what its card's {audio} gets. */
            val audio = HashMap<Int, AudioInfo>()
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val dictionaries by lazy { DictionaryDb.get(activity) }
    val translator by lazy {
        Translator(
            dictionaries,
            { DictionarySettings(activity).frequencySort(dictionaries) },
            { DictionarySettings(activity).grouping() },
        ) { DictionarySettings(activity).options() }
    }
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
    ) { DictionarySettings(activity).aliases }

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        // Only Hiroi's own HTML: nothing from the network or files, script just for the Anki buttons.
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(AnkiBridge(), "Hiroi")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                pending.forEach { view.evaluateJavascript(it, null) }
                pending.clear()
            }
        }
        // One page for all results, which each lookup's script fills
        loadDataWithBaseURL(null, html.shell(), "text/html", "utf-8", null)
    }

    /** The dictionaries whose styles the results page has, or are on their way to it (in [pending]) */
    private val pageStyles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether the results page has loaded; scripts for it wait in [pending] until it has. */
    private var loaded = false
    private val pending = mutableListOf<String>()

    /** Runs [script] on the results page once it's loaded (a duplicate check can finish before). */
    private fun onPage(script: String) {
        if (loaded) web.evaluateJavascript(script, null) else pending += script
    }

    /**
     * Looks up the word at [offset] in [text] and calls [onResult] on the main thread, unless
     * another lookup or [cancel] came in between.
     */
    fun lookUp(text: String, offset: Int, onResult: (Lookup) -> Unit) =
        find("at $offset", text, onResult) { translator.lookUp(text, offset) }

    /**
     * A wildcard search ([Wildcard]), as [lookUp]; the result spans its affix, which is the
     * text of [Lookup.Found]. The first suffix search says first that it prepares for that, which
     * takes a while once on big dictionaries.
     */
    fun lookUpWildcard(wildcard: Wildcard, onResult: (Lookup) -> Unit) =
        find("for $wildcard", wildcard.affix, onResult) { post ->
            if (wildcard.suffix && !dictionaries.suffixSearchReady()) post(Lookup.Message(activity.getString(R.string.search_preparing_suffix)))
            translator.lookUpWildcard(wildcard.affix, wildcard.suffix)
        }

    /**
     * A search of [query] in Latin letters ([Translator.lookUpLatin]), as [lookUp]: in sections,
     * the word [kana] is all of (the query typed as romaji), the words of its meaning (English,
     * German…), and what it might have been meant as if neither finds anything; [correctRomaji]:
     * romaji is searched as kana, so romaji one edit away counts too.
     */
    fun lookUpLatin(query: String, kana: String?, correctRomaji: Boolean, onResult: (Lookup) -> Unit) {
        val titles = Translator.SectionTitles(
            japanese = { activity.getString(R.string.section_japanese, it) },
            meaning = activity.getString(R.string.section_meaning),
            didYouMean = { activity.getString(R.string.section_did_you_mean, it) },
        )
        find(
            "for $query", query, onResult,
            noMatch = { activity.getString(if (kana != null || dictionaries.meaningSearchAvailable()) R.string.no_match else R.string.search_meaning_unavailable) },
        ) { translator.lookUpLatin(query, kana, correctRomaji, titles) }
    }

    /**
     * Runs [search] on the worker, which can [post] something to show before its outcome.
     * [noMatch]: what to say if it finds nothing.
     */
    private fun find(
        what: String,
        text: String,
        onResult: (Lookup) -> Unit,
        noMatch: () -> String = { activity.getString(R.string.no_match) },
        search: (post: (Lookup) -> Unit) -> LookupResult?,
    ) {
        val generation = ++generation
        fun post(outcome: Lookup) = activity.runOnUiThread {
            if (generation == this.generation && !activity.isDestroyed) onResult(outcome)
        }
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            var searched = started
            var page: ResultsHtml.Page? = null
            // Formatting big structured-content glossaries takes a while too, so it happens here as well.
            val outcome = runCatching {
                when {
                    !dictionaries.hasDictionaries() -> Lookup.Message(activity.getString(R.string.no_dictionaries))
                    dictionaries.enabledDictionaries().isEmpty() -> Lookup.Message(activity.getString(R.string.no_enabled_dictionaries))
                    else -> when (val result = search(::post).also { searched = SystemClock.elapsedRealtime() }) {
                        null -> Lookup.Message(activity.getString(R.string.not_japanese))
                        else -> if (result.terms.isEmpty() && result.kanji.isEmpty()) {
                            Lookup.Message(noMatch())
                        } else {
                            val anki = AnkiSettings(activity)
                            val frequencies = ResultsHtml.Frequencies(
                                average = DictionarySettings(activity).averageFrequencies,
                                occurrenceBased = dictionaries.enabledDictionaries().filter { it.frequencyMode == "occurrence-based" }.mapTo(HashSet()) { it.title },
                            )
                            // Anki is asked once the results show (checkAnki), so they needn't wait for it
                            val results = html.Page(
                                result, AnkiStatus.ASKING.takeIf { anki.enabled }, styles, anki.cardFormat.complete, frequencies,
                                dictionaries::findMedia, AudioSettings(activity).enabled, pageStyles::contains,
                            ).also { page = it }
                            val script = results.show(FIRST_PART)
                            Lookup.Found(result, text, script, results.styledSoFar, notes = HashMap())
                        }
                    }
                }
            }.getOrElse {
                Log.e(TAG, "Lookup failed", it)
                Lookup.Message(activity.getString(R.string.lookup_failed, it.message ?: it.javaClass.simpleName))
            }
            Log.d(TAG, "Lookup $what took ${SystemClock.elapsedRealtime() - started} ms (search ${searched - started} ms)")
            post(outcome)
            // Then the results that didn't fit the first part, added to the page once it shows
            if (outcome !is Lookup.Found || generation != this.generation) return@execute
            val made = SystemClock.elapsedRealtime()
            val rest = runCatching { page?.rest() }.onFailure { Log.e(TAG, "Making the rest of the results failed", it) }.getOrNull() ?: return@execute
            val styles = page?.styledSoFar.orEmpty()
            Log.d(TAG, "The rest of the results took ${SystemClock.elapsedRealtime() - made} ms (${rest.length / 1024} KB)")
            activity.runOnUiThread {
                if (shown !== outcome) return@runOnUiThread
                pageStyles += styles
                onPage(rest)
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
        pageStyles += found.styles
        onPage(found.script)
        checkAnki(found)
        checkDuplicates(found)
        val audio = AudioSettings(activity)
        if (audio.enabled && audio.autoPlay && found.result.terms.isNotEmpty()) playAudio(0)
    }

    // ---- Audio ------------------------------------------------------------------------------

    private val player by lazy { AudioPlayer(activity) }
    private val wordAudio = WordAudio(WordAudio::httpFetch)
    private val audioWorker = Executors.newSingleThreadExecutor()

    /** Recordings already downloaded, by URL, so playing one again needs no download. */
    private val downloaded = HashMap<String, ByteArray>()

    private fun download(info: AudioInfo): ByteArray? {
        val url = info.url ?: return null
        synchronized(downloaded) { downloaded[url] }?.let { return it }
        return wordAudio.download(info)?.also { synchronized(downloaded) { downloaded[url] = it } }
    }

    /**
     * Plays result [index]'s word: the recording chosen in its menu, else the first one the
     * sources have, in their order, as Yomitan's 🔊 button does; the fallback sound if none has one.
     */
    private fun playAudio(index: Int) {
        val found = shown ?: return
        val term = found.result.terms.getOrNull(index) ?: return
        val settings = AudioSettings(activity)
        val chosen = found.audio[index]
        onPage("audioState($index, 'loading')")
        audioWorker.execute {
            val audio = if (chosen != null) {
                if (chosen.speak != null) WordAudio.Found(chosen, null) else download(chosen)?.let { WordAudio.Found(chosen, it) }
            } else {
                settings.sources.firstNotNullOfOrNull { source ->
                    wordAudio.infos(source, term.expression, term.reading).firstNotNullOfOrNull { info ->
                        if (info.speak != null) WordAudio.Found(info, null) else download(info)?.let { WordAudio.Found(info, it) }
                    }
                }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                onPage("audioState($index, '${if (audio == null) "none" else ""}')")
                Log.d(TAG, "Audio for ${term.expression}: ${audio?.let { "${it.info.source.type} ${it.info.url ?: "(speech)"} ${it.bytes?.size ?: 0} bytes" } ?: "none"}")
                when {
                    audio == null -> player.playFallback(settings.fallbackSound, settings.volume)
                    audio.bytes != null -> player.play(audio.bytes, settings.volume)
                    else -> player.speak(audio.info.speak.orEmpty(), settings.volume) {
                        onPage("audioState($index, 'none')")
                        toast(activity.getString(R.string.audio_no_tts))
                        player.playFallback(settings.fallbackSound, settings.volume)
                    }
                }
            }
        }
    }

    /**
     * Yomitan's audio menu: every recording each source has for result [index], to play one; the
     * one picked is also what the result's card gets for {audio}.
     */
    private fun audioMenu(index: Int) {
        val found = shown ?: return
        val term = found.result.terms.getOrNull(index) ?: return
        val sources = AudioSettings(activity).sources
        onPage("audioState($index, 'loading')")
        audioWorker.execute {
            val choices = sources.flatMap { source ->
                val infos = wordAudio.infos(source, term.expression, term.reading)
                if (infos.isEmpty()) listOf(source to null) else infos.map { info -> source to info.takeIf { it.speak != null || download(it) != null } }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                onPage("audioState($index, '')")
                val labels = choices.map { (source, info) ->
                    val name = AudioSources.label(activity, source) + (info?.name?.let { " · $it" } ?: "")
                    if (info == null) activity.getString(R.string.audio_menu_none, name) else name
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.audio_menu_title, term.expression))
                    .setItems(labels.toTypedArray()) { _, which ->
                        val info = choices[which].second ?: return@setItems
                        found.audio[index] = info
                        playAudio(index)
                    }
                    .show()
            }
        }
    }

    /**
     * Asks Anki which of the results' words have a note in the deck, after the results are
     * shown (and the rest of them added: the same worker makes it), and tags them.
     */
    private fun checkAnki(found: Lookup.Found) {
        if (!AnkiSettings(activity).enabled) return
        val terms = found.result.terms
        worker.execute {
            val status = ankiStatus(terms) ?: return@execute
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                for ((i, term) in terms.withIndex()) {
                    val ids = status.notes[term.expression].orEmpty()
                    if (ids.isEmpty()) continue
                    val notes = found.notes.getOrPut(i) { mutableListOf() }
                    notes += ids.filter { it !in notes }
                    onPage("inAnki($i, '${activity.getString(R.string.anki_in_deck)}')")
                }
                onPage("ankiChecked(${status.error?.let(JSONObject::quote) ?: "null"})")
            }
        }
    }

    /**
     * Looks for notes each result would duplicate, after the results are shown, and changes their
     * buttons as Yomitan does: "Already in Anki" (prevent), "Overwrite" or "＋ Anki again" (add
     * another), with "Open in Anki" on the existing notes.
     */
    private fun checkDuplicates(found: Lookup.Found) {
        val settings = AnkiSettings(activity)
        val format = settings.cardFormat
        if (!format.complete || !format.checkForDuplicates) return
        val terms = found.result.terms
        worker.execute {
            val duplicates = runCatching { CardCreator(activity, settings).duplicates(terms) }
                .onFailure { Log.w(TAG, "Duplicate check failed", it) }
                .getOrNull() ?: return@execute
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                for ((i, ids) in duplicates.withIndex()) {
                    if (ids.isEmpty()) continue
                    found.duplicates[i] = ids
                    val notes = found.notes.getOrPut(i) { mutableListOf() }
                    notes += ids.filter { it !in notes }
                    val (label, enabled) = when (format.duplicateBehavior) {
                        "prevent" -> R.string.anki_duplicate to false
                        "overwrite" -> R.string.anki_overwrite to true
                        else -> R.string.anki_add_again to true
                    }
                    val open = if (notes.size > 1) activity.getString(R.string.anki_open_notes, notes.size) else activity.getString(R.string.anki_open)
                    onPage("inAnki($i, '${activity.getString(R.string.anki_in_deck)}')")
                    onPage("duplicate($i, '${activity.getString(label)}', $enabled, '$open')")
                }
            }
        }
    }

    /** What a card from result [index] is made of. */
    private fun cardData(found: Lookup.Found, index: Int): CardData {
        val term = found.result.terms[index]
        val start = found.result.start
        return CardData(
            term, found.text, start, start + term.source.length,
            documentTitle = documentTitle(),
            parse = { sentence ->
                Furigana.forText(sentence) { text, i ->
                    translator.lookUp(text, i, definitions = false)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) }
                }
            },
            selection = selection(),
            audio = found.audio[index],
            translate = { sentence -> Translations(activity).translate(sentence).text },
            frequencySort = DictionarySettings(activity).frequencySort(dictionaries),
        )
    }

    fun close() {
        worker.shutdownNow()
        audioWorker.shutdownNow()
        player.release()
        web.destroy()
    }

    /** Asks Anki which of the headwords already have a note, if a deck is set up. */
    private fun ankiStatus(terms: List<TermGroup>): AnkiStatus? {
        val anki = AnkiSettings(activity)
        if (!anki.enabled || terms.isEmpty()) return null
        return try {
            AnkiStatus(anki.client().notesInDeck(anki.deck, anki.field, terms.map { it.expression }.distinct()))
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

        @JavascriptInterface
        fun playAudio(index: Int) {
            activity.runOnUiThread { this@DictionaryLookup.playAudio(index) }
        }

        @JavascriptInterface
        fun audioMenu(index: Int) {
            activity.runOnUiThread { this@DictionaryLookup.audioMenu(index) }
        }
    }

    /**
     * Makes a note from result [index] with the card format, or overwrites the note it duplicates
     * if the format says so; its button then shows how it went.
     */
    private fun addCard(index: Int) {
        val found = shown ?: return
        if (index !in found.result.terms.indices) return
        fun button(label: Int, enabled: Boolean) =
            web.evaluateJavascript("setCard($index, '${activity.getString(label)}', $enabled)", null)
        val settings = AnkiSettings(activity)
        val format = settings.cardFormat
        val overwrite = found.duplicates[index]?.firstOrNull()?.takeIf { format.checkForDuplicates && format.duplicateBehavior == "overwrite" }
        button(if (overwrite != null) R.string.anki_overwriting else R.string.anki_adding, false)
        val data = cardData(found, index)
        val full = screenshot()
        // {screenshot-region} is always just the box or region; {screenshot} only if the settings say so
        val region = full?.let { crop(it, screenshotArea()) }
        val screenshot = if (settings.screenshotCrop) region else full
        worker.execute {
            val result = runCatching {
                val creator = CardCreator(activity, settings, ::clipboardText)
                if (overwrite != null) creator.overwrite(overwrite, data, screenshot, region) else creator.add(data, screenshot, region)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || shown !== found) return@runOnUiThread
                result.fold(
                    { added ->
                        val notes = found.notes.getOrPut(index) { mutableListOf() }
                        if (added.noteId !in notes) notes += added.noteId
                        // Fields whose templates failed show {marker-render-error}, as in Yomitan; say which.
                        if (added.errors.isNotEmpty()) toast(activity.getString(R.string.anki_template_errors, added.errors.joinToString("\n")))
                        // The stylesheets kept for the note type changed: it needs them to show the card as before.
                        if (added.stylesChanged) toast(activity.getString(R.string.dedup_changed_toast))
                        button(if (overwrite != null) R.string.anki_overwritten else R.string.anki_added, false)
                        web.evaluateJavascript("inAnki($index, '${activity.getString(R.string.anki_in_deck)}')", null)
                    },
                    { error ->
                        if (error is DuplicateNoteException) {
                            button(R.string.anki_duplicate, false)
                        } else {
                            Log.w(TAG, "Adding a card failed", error)
                            button(if (overwrite != null) R.string.anki_overwrite else R.string.anki_add, true)
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

    /** The clipboard's text, for {clipboard-text}; Hiroi is in the foreground, so it may read it. */
    private fun clipboardText(): String? =
        activity.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

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
        const val ANKIDROID = "com.ichi2.anki"
        const val CROP_MARGIN_DP = 12

        /** About how many characters of definitions a results page has at first, about what the panel shows; the rest follow */
        const val FIRST_PART = 10_000
    }
}
