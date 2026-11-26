package io.github.kvth.hiroi

import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.DuplicateNoteException
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.Screenshot
import io.github.kvth.hiroi.audio.AudioInfo
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.WordAudio
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Translator
import io.github.kvth.hiroi.dictionary.Wildcard
import io.github.kvth.hiroi.japanese.KanjiParts
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What a lookup came to: a [Message] to show instead of results, or results [Found] */
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
         * definition showed in its place first, until [LookupSession.back] brings these results back.
         */
        var rest: String? = null
        var restStyles: Set<String> = emptySet()
        var restShown = false
    }
}

/** The texts a [LookupSession] shows, which the platform has in the user's language; [LookupHost.text] gives each one. */
enum class LookupText {
    SEARCH_PREPARING_SUFFIX,
    /** The Japanese word Latin letters spell (an argument) */
    SECTION_JAPANESE,
    SECTION_MEANING,
    /** What was meant, perhaps (an argument) */
    SECTION_DID_YOU_MEAN,
    NO_MATCH,
    SEARCH_MEANING_UNAVAILABLE,
    NO_DICTIONARIES,
    NO_ENABLED_DICTIONARIES,
    NOT_JAPANESE,
    /** Why (an argument) */
    LOOKUP_FAILED,
    AUDIO_NO_TTS,
    /** A source that has no recording (an argument) */
    AUDIO_MENU_NONE,
    /** The word (an argument) */
    AUDIO_MENU_TITLE,
    ANKI_IN_DECK,
    /** Why (an argument) */
    ANKI_UNREACHABLE,
    ANKI_DUPLICATE,
    ANKI_OVERWRITE,
    ANKI_ADD_AGAIN,
    /** How many notes (an argument) */
    ANKI_OPEN_NOTES,
    ANKI_OPEN,
    ANKI_OVERWRITING,
    ANKI_ADDING,
    /** The templates that failed (an argument) */
    ANKI_TEMPLATE_ERRORS,
    /** Why (an argument) */
    DEDUP_UPDATE_FAILED_TOAST,
    /** The note type (an argument) */
    DEDUP_UPDATED_TOAST,
    DEDUP_CHANGED_TOAST,
    ANKI_OVERWRITTEN,
    ANKI_ADDED,
    ANKI_ADD,
    /** Why (an argument) */
    ANKI_ADD_FAILED,
}

/**
 * What a [LookupSession] needs of the platform it runs on: the results page it fills, its main
 * thread, its texts, sound, Anki and what a card is made from.
 */
interface LookupHost {
    /** Runs [action] on the main thread, unless the window the results are in is gone by then */
    fun onMain(action: () -> Unit)

    /** Runs [script] on the results page ([ResultsHtml.shell]), once it's loaded */
    fun runScript(script: String)

    fun text(text: LookupText, vararg args: Any): String

    /** Shows [text] briefly, over the results */
    fun toast(text: String)

    /** A line for the log: what happened, or [error] what went wrong */
    fun log(message: String, error: Throwable? = null)

    /** The title of what the text is from, for {document-title} */
    fun documentTitle(): String

    /** The text selected where the word was looked up, for {popup-selection-text} */
    fun selection(): String

    /** The screenshot for {screenshot}, and the part of it with the text (its box or region) for {screenshot-region}; nulls without one */
    fun screenshots(): Pair<Screenshot?, Screenshot?>

    /** Translates [sentence], for {sentence-translation}; throws with the reason when it can't */
    fun translate(sentence: String): String

    /** Makes cards with the user's card format and Anki */
    fun cardCreator(): CardCreator

    /** Opens Anki's card browser with [query] if Anki is on this device; false if it isn't */
    fun browseInLocalAnki(query: String): Boolean

    fun play(audio: ByteArray, volume: Int)

    /** Says [text] with text-to-speech; [onFailure] if it can't */
    fun speak(text: String, volume: Int, onFailure: () -> Unit)

    /** Yomitan's sound for no audio: "none", "click" or "bloop" */
    fun playFallback(sound: String, volume: Int)

    /** What audio source [source] is called */
    fun audioSourceLabel(source: AudioSource): String

    /** Offers [items] under [title], and calls [onChosen] with the one chosen */
    fun choose(title: String, items: List<String>, onChosen: (Int) -> Unit)
}

/**
 * Looks words up in the [dictionaries] and fills a Yomitan-style results page with them, with
 * its "＋ Anki" and "Open in Anki" buttons, 🔊 and lookups from definitions. The page is the
 * [host]'s: [handle] takes what the page sends ([ResultsHtml]'s hiroi() messages). Its methods
 * are called on the host's main thread.
 */
class LookupSession(
    private val host: LookupHost,
    private val dictionaries: DictionaryDb,
    private val settings: DictionarySettings,
    private val anki: AnkiSettings,
    private val audioSettings: AudioSettings,
    private val wordAudio: WordAudio,
    colors: ResultsHtml.Colors,
) {
    private val worker = Executors.newSingleThreadExecutor()
    val translator = Translator(dictionaries, { settings.frequencySort(dictionaries) }, { settings.grouping() }) { settings.options() }
    private val styles by lazy { dictionaries.styles() } // read on the lookup thread
    private var generation = 0
    private var shown: Lookup.Found? = null

    /** The results that lookups from their definitions showed in place of, the last most recently ([lookUpIn]) */
    private val stack = ArrayList<Lookup.Found>()

    // The HTML itself is made on the lookup thread.
    val html = ResultsHtml(colors, { settings.aliases }) { kanjiParts }

    /** The dictionaries whose styles the results page has, or are on their way to it */
    private val pageStyles: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            if (wildcard.suffix && !dictionaries.suffixSearchReady()) post(Lookup.Message(host.text(LookupText.SEARCH_PREPARING_SUFFIX)))
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
            japanese = { host.text(LookupText.SECTION_JAPANESE, it) },
            meaning = host.text(LookupText.SECTION_MEANING),
            didYouMean = { host.text(LookupText.SECTION_DID_YOU_MEAN, it) },
        )
        find(
            "for $query", query, onResult,
            noMatch = { host.text(if (kana != null || dictionaries.meaningSearchAvailable()) LookupText.NO_MATCH else LookupText.SEARCH_MEANING_UNAVAILABLE) },
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
        noMatch: () -> String = { host.text(LookupText.NO_MATCH) },
        /** Looked up from a definition of the results shown, which stay on [stack] */
        nested: Boolean = false,
        search: (post: (Lookup) -> Unit) -> LookupResult?,
    ) {
        if (!nested) clearStack()
        val generation = ++generation
        fun post(outcome: Lookup) = host.onMain {
            if (generation == this.generation) onResult(outcome)
        }
        worker.execute {
            val started = now()
            var searched = started
            var page: ResultsHtml.Page? = null
            // Formatting big structured-content glossaries takes a while too, so it happens here as well.
            val outcome = runCatching {
                when {
                    !dictionaries.hasDictionaries() -> Lookup.Message(host.text(LookupText.NO_DICTIONARIES))
                    dictionaries.enabledDictionaries().isEmpty() -> Lookup.Message(host.text(LookupText.NO_ENABLED_DICTIONARIES))
                    else -> when (val result = search(::post).also { searched = now() }) {
                        null -> Lookup.Message(host.text(LookupText.NOT_JAPANESE))
                        else -> if (result.terms.isEmpty() && result.kanji.isEmpty()) {
                            Lookup.Message(noMatch())
                        } else {
                            val frequencies = ResultsHtml.Frequencies(
                                average = settings.averageFrequencies,
                                occurrenceBased = dictionaries.enabledDictionaries().filter { it.frequencyMode == "occurrence-based" }.mapTo(HashSet()) { it.title },
                            )
                            // Anki is asked once the results show (checkAnki), so they needn't wait for it
                            val results = html.Page(
                                result, AnkiStatus.ASKING.takeIf { anki.enabled }, styles, anki.cardFormat.complete, frequencies,
                                dictionaries::findMedia, audioSettings.enabled, pageStyles::contains, tabs(),
                            ).also { page = it }
                            val script = results.show(FIRST_PART)
                            Lookup.Found(result, text, script, results.styledSoFar, notes = HashMap())
                        }
                    }
                }
            }.getOrElse {
                host.log("Lookup failed", it)
                Lookup.Message(host.text(LookupText.LOOKUP_FAILED, it.message ?: it.javaClass.simpleName))
            }
            host.log("Lookup $what took ${now() - started} ms (search ${searched - started} ms)")
            post(outcome)
            // Then the results that didn't fit the first part, added to the page once it shows
            if (outcome !is Lookup.Found || generation != this.generation) return@execute
            val made = now()
            val rest = runCatching { page?.rest() }.onFailure { host.log("Making the rest of the results failed", it) }.getOrNull() ?: return@execute
            val styles = page?.styledSoFar.orEmpty()
            host.log("The rest of the results took ${now() - made} ms (${rest.length / 1024} KB)")
            host.onMain {
                outcome.rest = rest
                outcome.restStyles = styles
                if (shown === outcome) showRest(outcome)
            }
        }
    }

    /** The tabs results show in: each term dictionary's, in the dictionaries' order, and 漢字 if a kanji dictionary is on */
    private fun tabs(): ResultsHtml.Tabs {
        val byDictionary = settings.tabs(dictionaries)
        val enabled = dictionaries.enabledDictionaries()
        return ResultsHtml.Tabs(byDictionary, enabled.mapNotNull { byDictionary[it.title] }.distinct(), enabled.any { it.kanji > 0 })
    }

    /** Shows the tab chosen last, or All if the results shown have none in it (keeping it for the next ones) */
    private fun useLastTab() = host.runScript("useTab(${JSONObject.quote(settings.lastTab)})")

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
        host.runScript(rest)
    }

    private fun clearStack() {
        if (stack.isEmpty()) return
        stack.clear()
        host.runScript("clearStack()")
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
        host.runScript("pushResults()")
        show(outcome, back = parent.result.terms.firstOrNull()?.expression ?: parent.text)
    }

    /** What kanji are built from ([KanjiParts]), read on the lookup thread the first time a card needs it */
    private val kanjiParts: KanjiParts? by lazy {
        runCatching { KanjiParts.get() }.onFailure { host.log("Reading the kanji parts failed", it) }.getOrNull()
    }

    /** Back to the results a lookup from their definitions showed in place of ([lookUpIn]); false if there are none. */
    fun back(): Boolean {
        val parent = stack.removeLastOrNull() ?: return false
        generation++
        shown = parent
        host.runScript("popResults()")
        useLastTab()
        showRest(parent)
        // Anki's answers that came while they weren't shown
        checkAnki(parent)
        checkDuplicates(parent)
        return true
    }

    /** Shows [found] in the page */
    fun show(found: Lookup.Found) = show(found, null)

    /** [back]: the word of the results it's looked up from, for the bar back to them */
    private fun show(found: Lookup.Found, back: String?) {
        shown = found
        pageStyles += found.styles
        host.runScript(found.script)
        useLastTab()
        if (back != null) host.runScript("backBar(${JSONObject.quote(back)})")
        checkAnki(found)
        checkDuplicates(found)
        if (audioSettings.enabled && audioSettings.autoPlay && found.result.terms.isNotEmpty()) playAudio(0)
    }

    /**
     * What the results page sends: a JSON object whose "type" says what was tapped (hiroi() in
     * [ResultsHtml]'s script). Unknown ones are ignored.
     */
    fun handle(message: String) {
        val m = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (m.optString("type")) {
            "addCard" -> addCard(m.optInt("index", -1))
            "openNote" -> openNote(m.optInt("index", -1))
            "playAudio" -> playAudio(m.optInt("index", -1))
            "audioMenu" -> audioMenu(m.optInt("index", -1))
            "lookUpIn" -> lookUpIn(m.optString("text"), m.optInt("offset"))
            "lookUpKanji" -> lookUpKanji(m.optString("character"))
            // A tab was chosen: the next results show in it too
            "selectTab" -> settings.lastTab = m.optString("tab")
            "back" -> back()
        }
    }

    // ---- Audio ------------------------------------------------------------------------------

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
        val chosen = found.audio[index]
        host.runScript("audioState($index, 'loading')")
        audioWorker.execute {
            val audio = if (chosen != null) {
                if (chosen.speak != null) WordAudio.Found(chosen, null) else download(chosen)?.let { WordAudio.Found(chosen, it) }
            } else {
                audioSettings.sources.firstNotNullOfOrNull { source ->
                    wordAudio.infos(source, term.expression, term.reading).firstNotNullOfOrNull { info ->
                        if (info.speak != null) WordAudio.Found(info, null) else download(info)?.let { WordAudio.Found(info, it) }
                    }
                }
            }
            host.onMain {
                if (shown !== found) return@onMain
                host.runScript("audioState($index, '${if (audio == null) "none" else ""}')")
                host.log("Audio for ${term.expression}: ${audio?.let { "${it.info.source.type} ${it.info.url ?: "(speech)"} ${it.bytes?.size ?: 0} bytes" } ?: "none"}")
                val bytes = audio?.bytes
                val volume = audioSettings.volume
                when {
                    audio == null -> host.playFallback(audioSettings.fallbackSound, volume)
                    bytes != null -> host.play(bytes, volume)
                    else -> host.speak(audio.info.speak.orEmpty(), volume) {
                        host.runScript("audioState($index, 'none')")
                        host.toast(host.text(LookupText.AUDIO_NO_TTS))
                        host.playFallback(audioSettings.fallbackSound, volume)
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
        val sources = audioSettings.sources
        host.runScript("audioState($index, 'loading')")
        audioWorker.execute {
            val choices = sources.flatMap { source ->
                val infos = wordAudio.infos(source, term.expression, term.reading)
                if (infos.isEmpty()) listOf(source to null) else infos.map { info -> source to info.takeIf { it.speak != null || download(it) != null } }
            }
            host.onMain {
                if (shown !== found) return@onMain
                host.runScript("audioState($index, '')")
                val labels = choices.map { (source, info) ->
                    val name = host.audioSourceLabel(source) + (info?.name?.let { " · $it" } ?: "")
                    if (info == null) host.text(LookupText.AUDIO_MENU_NONE, name) else name
                }
                host.choose(host.text(LookupText.AUDIO_MENU_TITLE, term.expression), labels) { which ->
                    val info = choices[which].second ?: return@choose
                    found.audio[index] = info
                    playAudio(index)
                }
            }
        }
    }

    // ---- Anki -------------------------------------------------------------------------------

    /**
     * Asks Anki which of the results' words have a note in the deck, after the results are
     * shown (and the rest of them added: the same worker makes it), and tags them.
     */
    private fun checkAnki(found: Lookup.Found) {
        if (!anki.enabled) return
        val terms = found.result.terms
        worker.execute {
            val status = ankiStatus(terms) ?: return@execute
            host.onMain {
                if (shown !== found) return@onMain
                for ((i, term) in terms.withIndex()) {
                    val ids = status.notes[term.expression].orEmpty()
                    if (ids.isEmpty()) continue
                    val notes = found.notes.getOrPut(i) { mutableListOf() }
                    notes += ids.filter { it !in notes }
                    host.runScript("inAnki($i, ${JSONObject.quote(host.text(LookupText.ANKI_IN_DECK))})")
                }
                host.runScript("ankiChecked(${status.error?.let(JSONObject::quote) ?: "null"})")
            }
        }
    }

    /**
     * Looks for notes each result would duplicate, after the results are shown, and changes their
     * buttons as Yomitan does: "Already in Anki" (prevent), "Overwrite" or "＋ Anki again" (add
     * another), with "Open in Anki" on the existing notes.
     */
    private fun checkDuplicates(found: Lookup.Found) {
        val format = anki.cardFormat
        if (!format.complete || !format.checkForDuplicates) return
        val terms = found.result.terms
        worker.execute {
            val duplicates = runCatching { host.cardCreator().duplicates(terms) }
                .onFailure { host.log("Duplicate check failed", it) }
                .getOrNull() ?: return@execute
            host.onMain {
                if (shown !== found) return@onMain
                for ((i, ids) in duplicates.withIndex()) {
                    if (ids.isEmpty()) continue
                    found.duplicates[i] = ids
                    val notes = found.notes.getOrPut(i) { mutableListOf() }
                    notes += ids.filter { it !in notes }
                    val (label, enabled) = when (format.duplicateBehavior) {
                        "prevent" -> LookupText.ANKI_DUPLICATE to false
                        "overwrite" -> LookupText.ANKI_OVERWRITE to true
                        else -> LookupText.ANKI_ADD_AGAIN to true
                    }
                    val open = if (notes.size > 1) host.text(LookupText.ANKI_OPEN_NOTES, notes.size) else host.text(LookupText.ANKI_OPEN)
                    host.runScript("inAnki($i, ${JSONObject.quote(host.text(LookupText.ANKI_IN_DECK))})")
                    host.runScript("duplicate($i, ${JSONObject.quote(host.text(label))}, $enabled, ${JSONObject.quote(open)})")
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
            documentTitle = host.documentTitle(),
            parse = { sentence ->
                Furigana.forText(sentence) { text, i ->
                    translator.lookUp(text, i, definitions = false)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) }
                }
            },
            selection = host.selection(),
            audio = found.audio[index],
            translate = host::translate,
            frequencySort = settings.frequencySort(dictionaries),
            kanjiParts = kanjiParts,
        )
    }

    /** Asks Anki which of the headwords already have a note, if a deck is set up. */
    private fun ankiStatus(terms: List<TermGroup>): AnkiStatus? {
        if (!anki.enabled || terms.isEmpty()) return null
        return try {
            AnkiStatus(anki.client().notesInDeck(anki.deck, anki.field, terms.map { it.expression }.distinct()))
        } catch (e: Exception) {
            host.log("Anki check failed", e)
            AnkiStatus(emptyMap(), host.text(LookupText.ANKI_UNREACHABLE, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Makes a note from result [index] with the card format, or overwrites the note it duplicates
     * if the format says so; its button then shows how it went.
     */
    private fun addCard(index: Int) {
        val found = shown ?: return
        if (index !in found.result.terms.indices) return
        fun button(label: LookupText, enabled: Boolean) =
            host.runScript("setCard($index, ${JSONObject.quote(host.text(label))}, $enabled)")
        val format = anki.cardFormat
        val overwrite = found.duplicates[index]?.firstOrNull()?.takeIf { format.checkForDuplicates && format.duplicateBehavior == "overwrite" }
        button(if (overwrite != null) LookupText.ANKI_OVERWRITING else LookupText.ANKI_ADDING, false)
        val data = cardData(found, index)
        // {screenshot-region} is always just the box or region; {screenshot} only if the settings say so
        val (full, region) = host.screenshots()
        val screenshot = if (anki.screenshotCrop) region else full
        worker.execute {
            val result = runCatching {
                val creator = host.cardCreator()
                if (overwrite != null) creator.overwrite(overwrite, data, screenshot, region) else creator.add(data, screenshot, region)
            }
            host.onMain {
                if (shown !== found) return@onMain
                result.fold(
                    { added ->
                        val notes = found.notes.getOrPut(index) { mutableListOf() }
                        if (added.noteId !in notes) notes += added.noteId
                        // Fields whose templates failed show {marker-render-error}, as in Yomitan; say which.
                        if (added.errors.isNotEmpty()) host.toast(host.text(LookupText.ANKI_TEMPLATE_ERRORS, added.errors.joinToString("\n")))
                        // The stylesheets kept for the note type changed: it needs them to show the card as before.
                        when {
                            added.noteTypeError != null -> host.toast(host.text(LookupText.DEDUP_UPDATE_FAILED_TOAST, added.noteTypeError))
                            added.noteTypeUpdated -> host.toast(host.text(LookupText.DEDUP_UPDATED_TOAST, format.model))
                            added.stylesChanged && !anki.updateNoteTypeCss -> host.toast(host.text(LookupText.DEDUP_CHANGED_TOAST))
                        }
                        button(if (overwrite != null) LookupText.ANKI_OVERWRITTEN else LookupText.ANKI_ADDED, false)
                        host.runScript("inAnki($index, ${JSONObject.quote(host.text(LookupText.ANKI_IN_DECK))})")
                    },
                    { error ->
                        if (error is DuplicateNoteException) {
                            button(LookupText.ANKI_DUPLICATE, false)
                        } else {
                            host.log("Adding a card failed", error)
                            button(if (overwrite != null) LookupText.ANKI_OVERWRITE else LookupText.ANKI_ADD, true)
                            host.toast(host.text(LookupText.ANKI_ADD_FAILED, error.message ?: error.javaClass.simpleName))
                        }
                    },
                )
            }
        }
    }

    /**
     * Shows result [index]'s notes in Anki's card browser: Anki's own when it's on this device
     * and reached directly or at this device's address ([LookupHost.browseInLocalAnki]),
     * AnkiConnect's guiBrowse otherwise.
     */
    private fun openNote(index: Int) {
        val ids = shown?.notes?.get(index).orEmpty()
        if (ids.isEmpty()) return
        val query = "nid:" + ids.joinToString(",")
        val local = anki.ankiDroid || runCatching { URI(anki.url).host }.getOrNull() in setOf("127.0.0.1", "localhost")
        if (local && host.browseInLocalAnki(query)) return
        worker.execute {
            val result = runCatching { anki.client().guiBrowse(query) }
            result.exceptionOrNull()?.let { error ->
                host.onMain { host.toast(host.text(LookupText.ANKI_UNREACHABLE, error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    /** Stops the lookups and audio in progress; the session can't be used after. */
    fun close() {
        worker.shutdownNow()
        audioWorker.shutdownNow()
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        /** About how many characters of definitions a results page has at first, about what the panel shows; the rest follow */
        const val FIRST_PART = 10_000
    }
}
