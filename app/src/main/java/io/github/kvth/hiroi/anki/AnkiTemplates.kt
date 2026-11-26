package io.github.kvth.hiroi.anki

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * stays loaded; [call] blocks, so use it off the main thread. The markers it knows: [AnkiMarkers].
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
