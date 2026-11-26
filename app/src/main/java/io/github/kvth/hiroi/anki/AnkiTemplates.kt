package io.github.kvth.hiroi.anki

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.kvth.hiroi.dictionary.DictionaryInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Yomitan's Anki note building, run as Yomitan runs it: its AnkiNoteBuilder, Handlebars template
 * renderer and helpers (assets/yomitan/anki.js, bundled by scripts/update-yomitan-anki.mjs) in a
 * WebView of its own, without network or file access. The WebView lives on the main thread and
 * stays loaded; [call] blocks, so use it off the main thread.
 */
object AnkiTemplates {
    private const val TIMEOUT_S = 30L
    const val ASSETS = "yomitan"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var started = false
    private val ready = CountDownLatch(1)
    private val calls = ConcurrentHashMap<Int, (String) -> Unit>()
    private val ids = AtomicInteger()

    /** Yomitan's default field templates. */
    fun defaultTemplates(context: Context): String =
        context.assets.open("$ASSETS/default-anki-field-templates.handlebars").bufferedReader().use { it.readText() }

    /** The markers Yomitan's card format editor offers for terms. */
    fun standardMarkers(context: Context): List<String> =
        context.assets.open("$ASSETS/markers.json").bufferedReader().use { JSONArray(it.readText()) }.let { a -> List(a.length()) { a.getString(it) } }

    /** The Yomitan release the templates come from. */
    fun version(context: Context): String = context.assets.open("$ASSETS/VERSION").bufferedReader().use { it.readText().trim() }

    /**
     * Inline templates ({{#*inline "name"}}) defined in [templates]: each is a marker, as in
     * Yomitan. Some are only helpers of other templates, but can still be used.
     */
    fun inlineMarkers(templates: String): List<String> =
        Regex("\\{\\{~?#\\*inline\\s+\"([^\"]+)\"").findAll(templates).map { it.groupValues[1] }.distinct().toList()

    /** Yomitan's per-dictionary markers (anki-template-util.js, getDynamicFieldMarkers). */
    fun dynamicMarkers(dictionaries: List<DictionaryInfo>): List<String> = dictionaries.flatMap { info ->
        listOfNotNull(
            "single-glossary-${kebabCase(info.title)}".takeIf { info.terms > 0 },
            "single-frequency-number-${kebabCase(info.title)}".takeIf { info.frequencies > 0 },
        )
    }

    /** {sentence-translation}: Hiroi's own marker (Yomitan has none for it), the sentence translated. */
    const val SENTENCE_TRANSLATION = "{sentence-translation}"

    /** {screenshot-region}: the screenshot cut to the box tapped or the region dragged out, whatever {screenshot} does */
    const val SCREENSHOT_REGION = "{screenshot-region}"

    /**
     * Hiroi's own markers, besides Yomitan's ([hiroiMarkers] fills them): the sentence translated;
     * each kanji of the word with its meanings, with its readings, and with its parts; the
     * inflections with what each means; the text the word was looked up in (the box, the region,
     * all of them, or what was searched), and that text before and after the word; the rank from
     * the frequency dictionary results are sorted by; the screenshot of just the box or region;
     * and when the card was made.
     */
    val HIROI_MARKERS = listOf(
        "sentence-translation", "kanji-meanings", "kanji-readings", "kanji-parts", "conjugation-description", "text-block",
        "text-block-prefix", "text-block-suffix", "frequency-sort-rank", "screenshot-region", "local-time",
    )

    /** Markers [format]'s fields use that neither [templates] nor the dictionaries define: they'd come out as {name-render-error}. */
    fun unknownMarkers(format: CardFormat, templates: String, dictionaries: List<DictionaryInfo>): List<String> {
        val known = inlineMarkers(templates).toSet() + dynamicMarkers(dictionaries) + HIROI_MARKERS
        return format.fields.values.flatMap { MARKER.findAll(it).map { m -> m.groupValues[1] } }.filter { it !in known }.distinct()
    }

    /** Yomitan's marker pattern (anki-util.js): {name} with letters, digits, - and _. */
    val MARKER = Regex("\\{([\\p{L}\\p{N}_-]+)\\}")

    fun kebabCase(name: String): String = name
        .replace(Regex("[\\s_　]"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .replace(Regex("--+"), "-")
        .trim('-')
        .lowercase()

    /** Calls [method] of hiroi-anki.js with [args] and returns its result. */
    fun call(context: Context, method: String, args: JSONObject): Any? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AnkiTemplates.call blocks; call it off the main thread" }
        start(context)
        if (!ready.await(TIMEOUT_S, TimeUnit.SECONDS)) throw AnkiException("Yomitan's templates didn't load")
        val id = ids.incrementAndGet()
        var answer: String? = null
        val done = CountDownLatch(1)
        calls[id] = { answer = it; done.countDown() }
        val script = "hiroiAnki.call($id, ${JSONObject.quote(method)}, $args)"
        main.post { web?.evaluateJavascript(script, null) }
        if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) {
            calls.remove(id)
            throw AnkiException("Yomitan's templates didn't answer")
        }
        val reply = JSONObject(answer!!)
        if (reply.has("error")) throw AnkiException(reply.getString("error"))
        return reply.opt("result")
    }

    private var web: WebView? = null

    @Synchronized
    private fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val bundle = app.assets.open("$ASSETS/anki.js").bufferedReader().use { it.readText() }
        main.post { create(app, bundle) }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun create(context: Context, bundle: String) {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.addJavascriptInterface(Bridge, "HiroiAnkiBridge")
        view.webViewClient = object : WebViewClient() {
            private var loaded = false
            override fun onPageFinished(v: WebView, url: String?) {
                if (loaded) return
                loaded = true
                v.evaluateJavascript(bundle, null)
            }
        }
        web = view
        view.loadDataWithBaseURL(null, "<!doctype html><meta charset=utf-8><body></body>", "text/html", "utf-8", null)
    }

    /** What hiroi-anki.js calls back: once loaded, and with each answer. */
    private object Bridge {
        @JavascriptInterface
        fun ready() {
            ready.countDown()
        }

        @JavascriptInterface
        fun done(id: Int, json: String) {
            calls.remove(id)?.invoke(json)
        }
    }
}
