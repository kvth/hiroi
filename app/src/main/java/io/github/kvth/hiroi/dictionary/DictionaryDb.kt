package io.github.kvth.hiroi.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.kvth.hiroi.settings.SettingsBackup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** [frequencies]: how many frequency values it has (terms and kanji); [frequencyMode] as its index.json says, if it does. */
data class DictionaryInfo(
    val id: Long,
    val title: String,
    val revision: String,
    val terms: Int,
    val meta: Int,
    val kanji: Int,
    val frequencies: Int = 0,
    val frequencyMode: String? = null,
    val enabled: Boolean = true,
    val images: Int = 0,
)

/**
 * The imported Yomitan dictionaries: each in a file of its own ([DictionaryFile]), and their list
 * here, in SQLite too, with their order (their priority: the first comes first) and switches.
 * The list is kept in memory, so a lookup only opens the files of the dictionaries it needs.
 */
class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "dictionaries.db", null, 9), DictionaryStore {

    /** The dictionaries' files */
    private val folder = File(context.filesDir, "dictionaries")

    /** Where images were before dictionaries had files of their own */
    private val legacyMediaDir = File(context.filesDir, "dictionary-media")

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dictionaries (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT, " +
                "terms INTEGER DEFAULT 0, meta INTEGER DEFAULT 0, kanji INTEGER DEFAULT 0, styles TEXT, " +
                "frequency_mode TEXT, frequencies INTEGER DEFAULT 0, position INTEGER DEFAULT 0, summary TEXT, enabled INTEGER DEFAULT 1, images INTEGER DEFAULT 0, " +
                "file TEXT NOT NULL)",
        )
    }

    /**
     * Up to 8, the dictionaries' rows were in tables here that they shared; they aren't carried
     * over to files of their own: the dictionaries need importing again.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 9) {
            for (table in listOf("dictionaries", "terms", "term_meta", "kanji", "kanji_meta", "tags", "media")) db.execSQL("DROP TABLE IF EXISTS $table")
            onCreate(db)
            legacyMediaDir.deleteRecursively()
        }
    }

    // ---- The list ------------------------------------------------------------------------------

    /** A dictionary in the list; [file] is its file's name in [folder]. */
    private class Entry(val info: DictionaryInfo, val position: Int, val file: String)

    @Volatile
    private var cached: List<Entry>? = null

    /** The dictionaries in their order */
    private fun entries(): List<Entry> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /** Whether files no dictionary has (left by an import that didn't finish) were deleted, as they are on first use */
    private var tidied = false

    private fun load(): List<Entry> {
        val entries = readableDatabase.rawQuery(
            "SELECT id, title, revision, terms, meta, kanji, frequencies, frequency_mode, enabled, images, position, file FROM dictionaries ORDER BY position, id", null,
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                val info = DictionaryInfo(
                    c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5),
                    c.getInt(6), c.getString(7), c.getInt(8) != 0, c.getInt(9),
                )
                Entry(info, c.getInt(10), c.getString(11))
            }
        }
        if (!tidied) {
            tidied = true
            val known = entries.mapTo(HashSet()) { it.file }
            // With their journals: "x.db-journal", "x.db.importing"
            folder.listFiles()?.filter { it.name.substringBefore(".db") + ".db" !in known }?.forEach { it.delete() }
        }
        return entries
    }

    /** After the list changed */
    private fun changed() {
        cached = null
    }

    fun dictionaries(): List<DictionaryInfo> = entries().map { it.info }

    fun hasDictionaries(): Boolean = entries().isNotEmpty()

    /** The dictionaries lookups use, in their order: those turned on. */
    fun enabledDictionaries(): List<DictionaryInfo> = dictionaries().filter { it.enabled }

    /** Each dictionary's styles.css by title, for showing its definitions as Yomitan does. */
    fun styles(): Map<String, String> =
        readableDatabase.rawQuery("SELECT title, styles FROM dictionaries WHERE styles IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    /** Dictionary [id]'s index.json as stored (with its update links), if it was stored. */
    fun summary(id: Long): String? =
        readableDatabase.rawQuery("SELECT summary FROM dictionaries WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * Turns dictionary [id] on or off, as Yomitan's switch per dictionary: one that's off stays
     * installed but is left out of lookups, frequency sorting and Anki's per-dictionary markers.
     */
    fun setEnabled(id: Long, enabled: Boolean) {
        writableDatabase.update("dictionaries", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    /**
     * Moves dictionary [id] up ([by] < 0) or down in the order, which is their priority as in
     * Yomitan: results show the definitions and frequencies of dictionaries higher up first.
     */
    fun move(id: Long, by: Int) {
        val order = dictionaries().map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        order.add(to, order.removeAt(from))
        setOrder(order)
    }

    private fun setOrder(order: List<Long>) {
        val db = writableDatabase
        DictionaryFile.inTransaction(db) {
            for ((i, dict) in order.withIndex()) {
                db.update("dictionaries", ContentValues().apply { put("position", i + 1) }, "id = ?", arrayOf(dict.toString()))
            }
        }
        changed()
    }

    /**
     * Puts the dictionaries [states] names first, in its order and switched on or off as it
     * says; the others keep their order after them. Returns the titles that aren't installed.
     */
    fun applyOrder(states: List<SettingsBackup.DictionaryState>): List<String> {
        val installed = dictionaries()
        val byTitle = installed.associateBy { it.title }
        val listed = states.mapNotNull { state -> byTitle[state.title]?.let { it to state.enabled } }
        val order = listed.map { it.first.id }.distinct() + installed.map { it.id }.filter { id -> listed.none { it.first.id == id } }
        setOrder(order)
        for ((info, enabled) in listed) setEnabled(info.id, enabled)
        return states.map { it.title }.filter { it !in byTitle }
    }

    /** Adds the dictionary in file [name] (in [folder]) to the end of the list, with what the file says about it. */
    private fun register(facts: DictionaryFacts, name: String, enabled: Boolean = true): DictionaryInfo {
        val db = writableDatabase
        val id = db.insertOrThrow("dictionaries", null, ContentValues().apply {
            put("title", facts.title)
            put("revision", facts.revision)
            put("frequency_mode", facts.frequencyMode)
            put("position", DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(position), 0) + 1 FROM dictionaries", null))
            put("summary", facts.index)
            put("styles", facts.styles)
            put("enabled", if (enabled) 1 else 0)
            put("file", name)
            putCounts(facts)
        })
        changed()
        return DictionaryInfo(id, facts.title, facts.revision, facts.terms, facts.meta, facts.kanji, facts.frequencies, facts.frequencyMode, enabled, facts.images)
    }

    private fun ContentValues.putCounts(facts: DictionaryFacts) {
        put("terms", facts.terms)
        put("meta", facts.meta)
        put("kanji", facts.kanji)
        put("frequencies", facts.frequencies)
        put("images", facts.images)
    }

    /** A name for a new dictionary file; while it's written, it has ".importing" after it. */
    private fun newFile(): String {
        folder.mkdirs()
        return UUID.randomUUID().toString() + ".db"
    }

    private fun writing(name: String) = File(folder, "$name.importing")

    private fun finishWriting(name: String) {
        if (!writing(name).renameTo(File(folder, name))) throw IOException("Couldn't move $name into place")
    }

    // ---- Lookups -----------------------------------------------------------------------------

    /** The dictionary files opened so far, by name; they stay open. */
    private val files = ConcurrentHashMap<String, DictionaryFile>()

    private fun file(entry: Entry): DictionaryFile = files.computeIfAbsent(entry.file) { DictionaryFile.open(File(folder, it)) }

    /** The dictionaries lookups search: those turned on */
    private fun searchable(): List<Entry> = entries().filter { it.info.enabled }

    private fun byTitle(title: String): Entry? = entries().firstOrNull { it.info.title == title }

    /**
     * Runs [query] on each of [entries] and gives their rows in the dictionaries' order. A
     * dictionary removed meanwhile (its file closed) gives none. One after the other: each takes
     * well under a millisecond, less than handing it to another thread.
     */
    private fun <T> each(entries: List<Entry>, query: (Entry, DictionaryFile) -> List<T>): List<T> =
        entries.flatMap { entry ->
            try {
                query(entry, file(entry))
            } catch (e: IllegalStateException) {
                emptyList()
            }
        }

    private fun <T> Future<T>.result(): T = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    override fun findTerms(forms: Collection<String>): List<TermEntry> {
        if (forms.isEmpty()) return emptyList()
        return each(searchable().filter { it.info.terms > 0 }) { e, f -> f.findTerms(forms, e.info.title, e.position) }
    }

    override fun withGlossaries(entries: List<TermEntry>): List<TermEntry> {
        val missing = entries.filter { it.glossary.isEmpty() }
        if (missing.isEmpty()) return entries
        val loaded = HashMap<Pair<String, Long>, String>()
        for ((title, rows) in missing.groupBy { it.dictionary }) {
            val entry = byTitle(title) ?: continue
            val glossaries = try {
                file(entry).glossaries(rows.map { it.id })
            } catch (e: IllegalStateException) {
                continue
            }
            for ((id, glossary) in glossaries) loaded[title to id] = glossary
        }
        // One whose dictionary was removed meanwhile shows no definitions.
        return entries.map { e -> if (e.glossary.isNotEmpty()) e else e.copy(glossary = loaded[e.dictionary to e.id] ?: "[]") }
    }

    override fun findTermsBySequence(dictionary: String, sequences: Collection<Long>): List<TermEntry> {
        if (sequences.isEmpty()) return emptyList()
        val entry = byTitle(dictionary) ?: return emptyList()
        return each(listOf(entry)) { e, f -> f.findTermsBySequence(sequences, e.info.title, e.position) }
    }

    /** Suffix search's keys are made on its first use, in each dictionary ([DictionaryFile.buildReverseKeys]). */
    override fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int): List<TermEntry> {
        if (affix.isEmpty()) return emptyList()
        val entries = searchable().filter { it.info.terms > 0 }
        if (suffix) synchronized(writing) { for (entry in entries) file(entry).buildReverseKeys() }
        return each(entries) { e, f -> f.findTermsByAffix(affix, suffix, limit, e.info.title, e.position) }
            .sortedBy { it.second }.take(limit).map { it.first }
            .sortedBy { it.expression.length }
    }

    /** Whether suffix search is ready; if not, the first one takes a while on big dictionaries. */
    fun suffixSearchReady(): Boolean = searchable().filter { it.info.terms > 0 }.all { file(it).hasReverseKeys() }

    override fun findTermMeta(expressions: Collection<String>): List<TermMeta> {
        if (expressions.isEmpty()) return emptyList()
        return each(searchable().filter { it.info.meta > 0 }) { e, f -> f.findTermMeta(expressions, e.info.title, e.position) }
    }

    override fun findKanji(character: String): List<KanjiEntry> =
        each(searchable().filter { it.info.kanji > 0 }) { e, f -> f.findKanji(character, e.info.title) }

    override fun findKanjiMeta(character: String): List<TermMeta> =
        each(searchable().filter { it.info.meta > 0 }) { e, f -> f.findKanjiMeta(character, e.info.title, e.position) }

    override fun findTags(dictionary: String, names: Collection<String>): List<TagInfo> {
        val entry = byTitle(dictionary) ?: return emptyList()
        return each(listOf(entry)) { e, f -> f.findTags(names, e.info.title) }
    }

    override fun findMedia(dictionary: String, path: String): DictionaryMedia? {
        val entry = byTitle(dictionary) ?: return null
        return try {
            file(entry).findMedia(path)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Whether lower values mean more common words in frequency dictionary [title] (ranks), as
     * Yomitan decides it: from the dictionary's frequencyMode, else by comparing its values for
     * common and rare words. Null if it can't tell.
     */
    fun frequencyAscending(title: String): Boolean? {
        val entry = entries().firstOrNull { it.info.title == title } ?: return null
        when (entry.info.frequencyMode) {
            "rank-based" -> return true
            "occurrence-based" -> return false
        }
        val values = file(entry).findTermMeta(MORE_COMMON + LESS_COMMON, title, 0)
            .mapNotNull { meta -> Yomitan.frequency(meta, null)?.value?.let { meta.expression to it } }
            .groupBy({ it.first }, { it.second })
        var result = 0
        for (common in MORE_COMMON.mapNotNull { values[it] }) {
            for (rare in LESS_COMMON.mapNotNull { values[it] }) {
                result += Integer.signum(common.max() - rare.min()) + Integer.signum(common.min() - rare.max())
            }
        }
        return if (result > 0) false else if (result < 0) true else null
    }

    // ---- Importing -----------------------------------------------------------------------------

    /** Held while a dictionary file changes after its import (suffix search's keys), so it isn't exported meanwhile */
    private val writing = Any()

    /**
     * Imports a Yomitan dictionary zip into a file of its own; [onProgress] gets short status
     * lines. A failed import leaves nothing behind. The banks are parsed on several threads while
     * this one writes them, in their order.
     */
    fun import(zip: File, onProgress: (String) -> Unit): DictionaryInfo {
        ZipFile(zip).use { file ->
            val indexEntry = file.getEntry("index.json")
                ?: throw IllegalArgumentException("Not a Yomitan dictionary (no index.json)")
            val indexJson = file.getInputStream(indexEntry).use { String(it.readBytes(), Charsets.UTF_8) }
            val index = Yomitan.parseIndex(indexJson)
            require(entries().none { it.info.title == index.title }) { "\"${index.title}\" is already imported" }
            val styles = file.getEntry("styles.css")?.let { entry -> file.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) } }
            val banks = file.entries().asSequence()
                .mapNotNull { entry -> BANK.matchEntire(entry.name)?.let { entry to it } }
                .sortedWith(compareBy({ it.second.groupValues[1] }, { it.second.groupValues[2].toInt() }))
                .map { (entry, match) -> entry to match.groupValues[1] }
                .toList()
            // Once suffix search was used, imports make its keys too.
            val reverseKeys = searchable().any { runCatching { file(it).hasReverseKeys() }.getOrDefault(false) }
            val name = newFile()
            val facts = DictionaryFile.Writer(writing(name)).use { writer ->
                // Images first, so the definitions can get their sizes as Yomitan stores them.
                val sizes = HashMap<String, Pair<Int, Int>>()
                for (entry in file.entries().asSequence().filter { !it.isDirectory && DictionaryImages.mediaType(it.name) != null }.toList()) {
                    val bytes = file.getInputStream(entry).use { it.readBytes() }
                    sizes[entry.name] = writer.media(entry.name, bytes)
                }
                if (sizes.isNotEmpty()) onProgress("${index.title}: ${sizes.size} images")
                val parsers = Executors.newFixedThreadPool(PARSE_THREADS)
                try {
                    // A few banks ahead of the writer, as many as the memory allows: a bank's text
                    // and rows take a few times its size, and some are tens of MB.
                    val budget = Runtime.getRuntime().maxMemory() / 3
                    val pending = ArrayDeque<Pair<Long, Future<(DictionaryFile.Writer) -> Unit>>>()
                    var next = 0
                    var parsing = 0L
                    fun parseMore() {
                        while (next < banks.size && pending.size < PARSE_THREADS * 2) {
                            val (entry, kind) = banks[next]
                            val cost = 4 * (entry.size.takeIf { it > 0 } ?: (entry.compressedSize * 10))
                            if (pending.isNotEmpty() && parsing + cost > budget) return
                            parsing += cost
                            next++
                            pending += cost to parsers.submit(Callable { parseBank(file, entry, kind, index.format, sizes) })
                        }
                    }
                    parseMore()
                    var written = 0
                    while (pending.isNotEmpty()) {
                        val (cost, parsed) = pending.removeFirst()
                        parsed.result()(writer)
                        parsing -= cost
                        parseMore()
                        onProgress("${index.title}: ${++written} / ${banks.size}")
                    }
                } finally {
                    parsers.shutdownNow()
                }
                writer.finish(DictionaryFacts(index.title, index.revision, index.frequencyMode, indexJson, styles), reverseKeys)
            }
            try {
                finishWriting(name)
                return register(facts, name)
            } catch (e: Exception) {
                SQLiteDatabase.deleteDatabase(writing(name))
                SQLiteDatabase.deleteDatabase(File(folder, name))
                throw e
            }
        }
    }

    /** Reads bank [entry], of [kind]; what it returns writes the rows. */
    private fun parseBank(zip: ZipFile, entry: ZipEntry, kind: String, format: Int, sizes: Map<String, Pair<Int, Int>>): (DictionaryFile.Writer) -> Unit {
        val json = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        when (kind) {
            "term_bank" -> {
                val rows = Yomitan.parseTermBank(json, format).let { rows ->
                    if (sizes.isEmpty()) rows else rows.map { it.copy(glossary = DictionaryImages.prepare(it.glossary, sizes::get)) }
                }
                return { it.terms(rows) }
            }
            "term_meta_bank", "kanji_meta_bank" -> {
                val rows = Yomitan.parseTermMetaBank(json)
                return { it.meta(rows, ofKanji = kind == "kanji_meta_bank") }
            }
            "kanji_bank" -> {
                val rows = Yomitan.parseKanjiBank(json, format)
                return { it.kanji(rows) }
            }
            "tag_bank" -> {
                val rows = Yomitan.parseTagBank(json)
                return { it.tags(rows) }
            }
        }
        return {}
    }

    /**
     * Replaces dictionary [oldId] with the one in [zip] (its update): the new one takes the old
     * one's place in the order and its on/off switch, and the old one goes once the new one is
     * imported. Its title may differ (Jitendex and JMdict have their date in it), and may be the
     * same: the old one is renamed meanwhile, and gets its title back if the import fails.
     */
    fun replace(oldId: Long, zip: File, onProgress: (String) -> Unit): DictionaryInfo {
        val db = writableDatabase
        val (title, position, enabled) = db.rawQuery("SELECT title, position, enabled FROM dictionaries WHERE id = ?", arrayOf(oldId.toString())).use { c ->
            require(c.moveToFirst()) { "The dictionary to update is gone" }
            Triple(c.getString(0), c.getLong(1), c.getInt(2))
        }
        fun rename(to: String) {
            db.update("dictionaries", ContentValues().apply { put("title", to) }, "id = ?", arrayOf(oldId.toString()))
            changed()
        }
        rename("$title (updating)")
        val info = try {
            import(zip, onProgress)
        } catch (e: Exception) {
            rename(title)
            throw e
        }
        db.update(
            "dictionaries",
            ContentValues().apply {
                put("position", position)
                put("enabled", enabled)
            },
            "id = ?", arrayOf(info.id.toString()),
        )
        changed()
        delete(oldId)
        return dictionaries().first { it.id == info.id }
    }

    fun delete(id: Long) {
        val db = writableDatabase
        val name = db.rawQuery("SELECT file FROM dictionaries WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        db.delete("dictionaries", "id = ?", arrayOf(id.toString()))
        changed()
        if (name != null) {
            files.remove(name)?.close()
            SQLiteDatabase.deleteDatabase(File(folder, name))
        }
    }

    // ---- Collections ---------------------------------------------------------------------------

    /** What importing a collection did: the dictionaries it added, and those it left out because they were already there. */
    class CollectionImport(val added: List<String>, val skipped: List<String>)

    /**
     * Writes every dictionary, in their order and with their switches, as a [HiroiCollection]:
     * their files, zipped. [onProgress] gets the MB written and the total. Returns how many
     * dictionaries it wrote.
     */
    fun exportCollection(out: OutputStream, onProgress: (Long, Long) -> Unit): Int = synchronized(writing) {
        val list = entries()
        val items = list.mapIndexed { i, entry -> HiroiCollection.Item(entry.info.title, HiroiCollection.path(i + 1), entry.info.enabled) }
        val total = list.sumOf { File(folder, it.file).length() }
        var written = 0L
        ZipOutputStream(out.buffered(BUFFER)).use { zip ->
            // Dictionary files pack to about half; the fastest level does nearly as well as the best.
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(HiroiCollection.MANIFEST))
            zip.write(HiroiCollection.manifest(items).toByteArray())
            zip.closeEntry()
            for ((entry, item) in list.zip(items)) {
                zip.putNextEntry(ZipEntry(item.path))
                File(folder, entry.file).inputStream().use { input ->
                    copy(input, zip) { n ->
                        written += n
                        onProgress(written shr 20, total shr 20)
                    }
                }
                zip.closeEntry()
            }
        }
        list.size
    }

    /**
     * Adds the dictionaries of a [HiroiCollection] after the ones here, with their switches;
     * those already here (by title) are left as they are. [onProgress] gets the MB read. A failed
     * import leaves nothing behind.
     */
    fun importCollection(input: InputStream, onProgress: (Long) -> Unit): CollectionImport {
        val titles = entries().mapTo(HashSet()) { it.info.title }
        var items: Map<String, HiroiCollection.Item>? = null
        val added = mutableListOf<DictionaryInfo>()
        val skipped = mutableListOf<String>()
        var read = 0L
        try {
            ZipInputStream(input.buffered(BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == HiroiCollection.MANIFEST) {
                        items = HiroiCollection.items(String(zip.readBytes(), Charsets.UTF_8)).associateBy { it.path }
                        continue
                    }
                    if (entry.isDirectory || !entry.name.endsWith(".db")) continue
                    val listed = items ?: throw IllegalArgumentException("Not a Hiroi dictionary collection")
                    val name = newFile()
                    try {
                        writing(name).outputStream().use { out ->
                            copy(zip, out) { n ->
                                read += n
                                onProgress(read shr 20)
                            }
                        }
                        val facts = DictionaryFile.open(writing(name)).use { it.facts() }
                        if (facts.title in titles) {
                            skipped += facts.title
                            SQLiteDatabase.deleteDatabase(writing(name))
                        } else {
                            finishWriting(name)
                            added += register(facts, name, listed[entry.name]?.enabled ?: true)
                            titles += facts.title
                        }
                    } catch (e: Exception) {
                        SQLiteDatabase.deleteDatabase(writing(name))
                        throw e
                    }
                }
            }
            if (items == null) throw IllegalArgumentException("Not a Hiroi dictionary collection")
        } catch (e: Exception) {
            added.forEach { delete(it.id) }
            throw e
        }
        return CollectionImport(added.map { it.title }, skipped)
    }

    /** Copies [input] to [out], telling [onCopied] the bytes of each piece. */
    private inline fun copy(input: InputStream, out: OutputStream, onCopied: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            onCopied(n)
        }
    }

    companion object {
        private val BANK = Regex("(term_bank|term_meta_bank|kanji_bank|kanji_meta_bank|tag_bank)_(\\d+)\\.json")

        // Yomitan's words for telling ranks from occurrence counts (sort-frequency-dictionary-controller.js)
        private val MORE_COMMON = listOf("来る", "言う", "出る", "入る", "方", "男", "女", "今", "何", "時")
        private val LESS_COMMON = listOf("行なう", "論じる", "過す", "行方", "人口", "猫", "犬", "滝", "理", "暁")

        private val CORES = Runtime.getRuntime().availableProcessors()

        /** Threads parsing banks while another writes them */
        private val PARSE_THREADS = (CORES - 1).coerceIn(1, 4)

        private const val BUFFER = 1 shl 16

        @Volatile
        private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also { instance = it }
            }
    }
}
