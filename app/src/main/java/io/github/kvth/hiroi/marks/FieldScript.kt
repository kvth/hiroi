package io.github.kvth.hiroi.marks

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.kvth.hiroi.anki.AnkiBackend
import io.github.kvth.hiroi.anki.AnkiException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the user's field script (the body of a JavaScript function taking `note` and returning
 * words) like AnkiMark's sandbox does: in a WebView of its own, with no network or file access
 * and no bridge to the app, so DOMParser works. Call [run] off the main thread; the WebView lives
 * on the main thread until [close].
 */
class FieldScript(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    fun run(code: String, notes: List<AnkiBackend.Note>): List<ScriptResult> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "FieldScript.run blocks; call it off the main thread" }
        val script = "(function () {\n\"use strict\";\n$RUNNER\nreturn run(${JSONObject.quote(code)}, ${notesJson(notes)});\n})()"
        val answer = AtomicReference<String?>()
        val done = CountDownLatch(1)
        main.post {
            webView { view ->
                view.evaluateJavascript(script) { value ->
                    answer.set(value)
                    done.countDown()
                }
            }
        }
        if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) throw AnkiException("The field script didn't finish within $TIMEOUT_S s.")
        val reply = answer.get()?.takeIf { it != "null" }?.let(::JSONObject) ?: throw AnkiException("No answer from the field script.")
        if (reply.has("error")) throw AnkiException(reply.getString("error"))
        val results = reply.getJSONArray("results")
        return List(results.length()) { i ->
            val r = results.getJSONObject(i)
            if (r.has("error")) ScriptResult(error = r.getString("error"))
            else ScriptResult(words = r.getJSONArray("words").let { w -> List(w.length()) { w.getString(it) } })
        }
    }

    /** Calls [then] with a WebView that has loaded its empty page. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(then: (WebView) -> Unit) {
        web?.let { return then(it) }
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            private var loaded = false
            override fun onPageFinished(v: WebView, url: String?) {
                if (loaded) return
                loaded = true
                web = view
                then(view)
            }
        }
        view.loadDataWithBaseURL(null, "<!doctype html><meta charset=utf-8>", "text/html", "utf-8", null)
    }

    override fun close() {
        main.post {
            web?.destroy()
            web = null
        }
    }

    companion object {
        private const val TIMEOUT_S = 60L

        // As ankimark/sandbox.js: one { words } or { error } per note, or { error } if the code doesn't compile.
        private const val RUNNER = """
function wordList(value) {
  if (value == null) return [];
  if (typeof value === "string") return [value];
  if (Array.isArray(value) && value.every((v) => typeof v === "string")) return value;
  throw new TypeError("must return an array of strings");
}
function run(code, notes) {
  let fn;
  try {
    fn = new Function("note", code);
  } catch (err) {
    return { error: (err && err.name || "Error") + ": " + (err && err.message || err) };
  }
  return {
    results: notes.map((note) => {
      try {
        return { words: wordList(fn(note)) };
      } catch (err) {
        return { error: String(err && err.message !== undefined ? err.message : err) };
      }
    }),
  };
}"""

        /** The notes as the script sees them: { noteId, modelName, tags, fields: { name: html } }. */
        fun notesJson(notes: List<AnkiBackend.Note>) = JSONArray(notes.map { note ->
            JSONObject()
                .put("noteId", note.id)
                .put("modelName", note.modelName)
                .put("tags", JSONArray(note.tags))
                .put("fields", JSONObject(note.fields as Map<*, *>))
        }).toString()
    }
}
