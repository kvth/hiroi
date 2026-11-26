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
import io.github.kvth.hiroi.anki.AnkiDroidApi
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.DuplicateNoteException
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.asScreenshot
import io.github.kvth.hiroi.audio.AudioInfo
import io.github.kvth.hiroi.audio.AudioPlayer
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSources
import io.github.kvth.hiroi.audio.WordAudio
import io.github.kvth.hiroi.audio.wordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Translator
import io.github.kvth.hiroi.dictionary.Wildcard
import io.github.kvth.hiroi.dictionary.frequencySort
import io.github.kvth.hiroi.dictionary.tabs
import io.github.kvth.hiroi.japanese.KanjiParts
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

            /**
             * The script with the rest of the results (made after the first part shows), its
             * dictionaries' styles, and whether the page has it: not if a lookup from a
             * definition showed in its place first, until [back] brings these results back.
             */
            var rest: String? = null
            var restStyles: Set<String> = emptySet()
            var restShown = false
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

    /** The results that lookups from their definitions showed in place of, the last most recently ([lookUpIn]) */
    private val stack = ArrayList<Lookup.Found>()

    // Theme colours are resolved here; the HTML itself is made on the lookup thread.
    private val html = ResultsHtml(
        ResultsHtml.Colors(
            css(themeColor(android.R.attr.textColorPrimary)),
            css(themeColor(android.R.attr.textColorSecondary)),
            css(themeColor(android.R.attr.colorAccent)),
            css(themeColor(android.R.attr.colorBackground)),
        ),
        { DictionarySettings(activity).aliases },
    ) { kanjiParts }

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
        /** Looked up from a definition of the results shown, which stay on [stack] */
        nested: Boolean = false,
        search: (post: (Lookup) -> Unit) -> LookupResult?,
    ) {
        if (!nested) clearStack()
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
                                dictionaries::findMedia, AudioSettings(activity).enabled, pageStyles::contains, tabs(),
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
                outcome.rest = rest
                outcome.restStyles = styles
                if (shown === outcome) showRest(outcome)
            }
        }
    }

    /** The tabs results show in: each term dictionary's, in the dictionaries' order, and 漢字 if a kanji dictionary is on */
    private fun tabs(): ResultsHtml.Tabs {
        val byDictionary = DictionarySettings(activity).tabs(dictionaries)
        val enabled = dictionaries.enabledDictionaries()
        return ResultsHtml.Tabs(byDictionary, enabled.mapNotNull { byDictionary[it.title] }.distinct(), enabled.any { it.kanji > 0 })
    }

    /** Shows the tab chosen last, or All if the results shown have none in it (keeping it for the next ones) */
    private fun useLastTab() = onPage("useTab(${JSONObject.quote(DictionarySettings(activity).lastTab)})")

    /** Drops the lookup in progress, and the shown results' buttons. */
    fun cancel() {
        generation++
        shown = null
        clearStack()
    }

    /** The rest of [found]'s results, into the page, once */
    private fun showRest(found: Lookup.Found) {
        val rest = found.rest ?: return
        if (found.restShown) return
        found.restShown = true
        pageStyles += found.restStyles
        onPage(rest)
    }

    private fun clearStack() {
        if (stack.isEmpty()) return
        stack.clear()
        onPage("clearStack()")
    }

    /**
     * Looks up the word at [offset] in [text], a definition of the results shown (or the word a
     * link in one goes to), as Yomitan's nested popups do: its results show in their place, with
     * a bar back to them. Nothing happens if it finds nothing.
     */
    private fun lookUpIn(text: String, offset: Int) {
        val parent = shown ?: return
        find("in a definition at $offset", text, { showNested(parent, it) }, nested = true) { translator.lookUp(text, offset) }
    }

    /**
     * Shows kanji [character]'s card alone, in place of the results shown, as [lookUpIn]: a part
     * of a kanji, or one built with it, tapped on a card. If no kanji dictionary has it, a card
     * with what it's built from and what's built with it.
     */
    private fun lookUpKanji(character: String) {
        val parent = shown ?: return
        find("for kanji $character", character, { showNested(parent, it) }, nested = true) {
            val result = translator.lookUpKanji(character)
            if (result.kanji.isNotEmpty() || kanjiParts?.has(character) != true) result
            else result.copy(kanji = listOf(KanjiEntry(character, "", "", "", emptyList())))
        }
    }

    /** [outcome] in place of [parent] (still shown), with a bar back to it; nothing if it found nothing */
    private fun showNested(parent: Lookup.Found, outcome: Lookup) {
        if (outcome !is Lookup.Found || shown !== parent) return
        stack += parent
        onPage("pushResults()")
        show(outcome, back = parent.result.terms.firstOrNull()?.expression ?: parent.text)
    }

    /** What kanji are built from (assets/kanji-parts.txt), read on the lookup thread the first time a card needs it */
    private val kanjiParts: KanjiParts? by lazy {
        runCatching { KanjiParts.get() }.onFailure { Log.e(TAG, "Reading the kanji parts failed", it) }.getOrNull()
    }

    /** Back to the results a lookup from their definitions showed in place of ([lookUpIn]); false if there are none. */
    fun back(): Boolean {
        val parent = stack.removeLastOrNull() ?: return false
        generation++
        shown = parent
        onPage("popResults()")
        useLastTab()
        showRest(parent)
        // Anki's answers that came while they weren't shown
        checkAnki(parent)
        checkDuplicates(parent)
        return true
    }

    /** Shows [found] in [web]; [back]: the word of the results it's looked up from, for the bar back to them. */
    fun show(found: Lookup.Found) = show(found, null)

    private fun show(found: Lookup.Found, back: String?) {
        shown = found
        pageStyles += found.styles
        onPage(found.script)
        useLastTab()
        if (back != null) onPage("backBar(${JSONObject.quote(back)})")
        checkAnki(found)
        checkDuplicates(found)
        val audio = AudioSettings(activity)
        if (audio.enabled && audio.autoPlay && found.result.terms.isNotEmpty()) playAudio(0)
    }

    // ---- Audio ------------------------------------------------------------------------------

    private val player by lazy { AudioPlayer(activity) }
    private val wordAudio = AudioSettings(activity).wordAudio(activity)
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
                val bytes = audio?.bytes
                when {
                    audio == null -> player.playFallback(settings.fallbackSound, settings.volume)
                    bytes != null -> player.play(bytes, settings.volume)
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
            kanjiParts = kanjiParts,
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

        @JavascriptInterface
        fun lookUpIn(text: String, offset: Int) {
            activity.runOnUiThread { this@DictionaryLookup.lookUpIn(text, offset) }
        }

        /** A tab was chosen: the next results show in it too */
        @JavascriptInterface
        fun selectTab(tab: String) {
            activity.runOnUiThread { DictionarySettings(activity).lastTab = tab }
        }

        @JavascriptInterface
        fun lookUpKanji(character: String) {
            activity.runOnUiThread { this@DictionaryLookup.lookUpKanji(character) }
        }

        @JavascriptInterface
        fun back() {
            activity.runOnUiThread { this@DictionaryLookup.back() }
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
                if (overwrite != null) creator.overwrite(overwrite, data, screenshot?.asScreenshot(), region?.asScreenshot())
                else creator.add(data, screenshot?.asScreenshot(), region?.asScreenshot())
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
                        when {
                            added.noteTypeError != null -> toast(activity.getString(R.string.dedup_update_failed_toast, added.noteTypeError))
                            added.noteTypeUpdated -> toast(activity.getString(R.string.dedup_updated_toast, format.model))
                            added.stylesChanged && !settings.updateNoteTypeCss -> toast(activity.getString(R.string.dedup_changed_toast))
                        }
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
        val local = settings.ankiDroid || Uri.parse(settings.url).host in setOf("127.0.0.1", "localhost")
        val ankiDroid = AnkiDroidApi.browseIntent(query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        const val CROP_MARGIN_DP = 12

        /** About how many characters of definitions a results page has at first, about what the panel shows; the rest follow */
        const val FIRST_PART = 10_000
    }
}
