package io.github.kvth.hiroi.marks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kvth.hiroi.anki.AnkiSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The words from Anki to mark, kept in a file between syncs, and the matcher made from them.
 * Syncs run one at a time on their own thread and outlive the screen that started them.
 */
object KnownWords {
    private const val TAG = "HiroiMarks"
    private const val SYNC_TIMEOUT_MS = 60_000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var loaded = false
    private var dict: Map<String, List<RuleNote>> = emptyMap()
    private var dictVersion = 0
    private var info: SyncInfo? = null
    private var matcherKey: Any? = null
    private var matcher: WordMatcher<RuleNote>? = null

    private var syncing = false
    private val waiting = mutableListOf<(kotlin.Result<SyncInfo>) -> Unit>()

    /** Called on the main thread when a sync changed the word list. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun dictFile(context: Context) = File(context.filesDir, "marks.json")
    private fun cacheFile(context: Context) = File(context.filesDir, "marks-notes.json")

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = dictFile(context)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            info = json.optJSONObject("syncInfo")?.let(SyncInfo::fromJson)
            dict = parseDict(json.getJSONObject("dict"))
        }.onFailure { Log.w(TAG, "Couldn't read the marked words", it) }
    }

    @Synchronized
    fun syncInfo(context: Context): SyncInfo? {
        load(context)
        return info
    }

    /**
     * The matcher for the enabled rules, each word with the first of them (top to bottom) that
     * one of its notes matched; null if there's nothing to mark. Reads the file the first time,
     * so call it off the main thread.
     */
    @Synchronized
    fun matcher(context: Context, settings: MarkSettings): WordMatcher<RuleNote>? {
        load(context)
        val rules = settings.rules.filter { it.enabled }.map { it.id }
        val options = settings.matcherOptions
        val key = listOf(dictVersion, rules, options)
        if (key != matcherKey) {
            val rank = rules.withIndex().associate { (i, id) -> id to i }
            val entries = HashMap<String, RuleNote>()
            for ((word, matches) in dict) {
                matches.filter { it.ruleId in rank }.minByOrNull { rank.getValue(it.ruleId) }?.let { entries[word] = it }
            }
            matcher = if (entries.isEmpty()) null else WordMatcher(entries, options)
            matcherKey = key
        }
        return matcher
    }

    /** Syncs when the last sync is older than the setting (if marking is on at all). */
    fun syncIfDue(context: Context) {
        val settings = MarkSettings(context)
        if (!settings.enabled || settings.syncMinutes <= 0) return
        executor.execute {
            val last = syncInfo(context)?.time ?: 0
            if (System.currentTimeMillis() - last > settings.syncMinutes * 60_000L) sync(context) { result ->
                result.exceptionOrNull()?.let { Log.w(TAG, "Sync failed: ${it.message}") }
            }
        }
    }

    /** Syncs now, or joins the sync already running; [onDone] runs on the main thread. */
    fun sync(context: Context, onDone: (kotlin.Result<SyncInfo>) -> Unit = {}) {
        val app = context.applicationContext
        synchronized(this) {
            waiting += onDone
            if (syncing) return
            syncing = true
        }
        executor.execute {
            val result = runCatching { runSync(app) }
            val callbacks = synchronized(this) {
                syncing = false
                waiting.toList().also { waiting.clear() }
            }
            main.post { callbacks.forEach { it(result) } }
        }
    }

    private fun runSync(context: Context): SyncInfo {
        load(context)
        val settings = MarkSettings(context)
        val anki = AnkiSettings(context)
        val config = MarkSync.Config(settings.baseQuery, settings.rules, settings.fields, settings.fieldScript, anki.url, settings.notesBatch)
        val cache = runCatching { cacheFile(context).takeIf { it.exists() }?.readText()?.let { MarkSync.NoteCache.fromJson(JSONObject(it)) } }.getOrNull()
        val result = FieldScript(context).use { script ->
            MarkSync(anki.client(SYNC_TIMEOUT_MS), script::run).run(config, cache)
        }
        cacheFile(context).writeText(result.cache.toJson().toString())
        val changed = synchronized(this) {
            val changed = result.dict != dict
            if (changed) {
                dict = result.dict
                dictVersion++
            }
            info = result.info
            changed
        }
        dictFile(context).writeText(JSONObject().put("syncInfo", result.info.toJson()).put("dict", dictJson(result.dict)).toString())
        Log.i(TAG, "Synced ${result.info.words} words from ${result.info.notes} notes (${result.info.read} read)")
        if (changed) main.post { listeners.forEach { it() } }
        return result.info
    }

    private fun dictJson(dict: Map<String, List<RuleNote>>): JSONObject {
        val out = JSONObject()
        for ((word, matches) in dict) out.put(word, JSONArray(matches.map { JSONArray().put(it.ruleId).put(it.noteId) }))
        return out
    }

    private fun parseDict(json: JSONObject): Map<String, List<RuleNote>> {
        val out = HashMap<String, List<RuleNote>>()
        for (word in json.keys()) {
            val matches = json.getJSONArray(word)
            out[word] = List(matches.length()) { i -> matches.getJSONArray(i).let { RuleNote(it.getString(0), it.getLong(1)) } }
        }
        return out
    }
}
