package io.github.kvth.hiroi.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import io.github.kvth.hiroi.settings.SettingsBackup
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
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

/**
 * [frequencies]: how many frequency values it has (terms and kanji); [frequencyMode] as its
 * index.json says, if it does. [forms]: how many of its terms are forms of other words
 * ([Yomitan.FormOf]); null if it was imported before Hiroi counted them.
 */
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
    val forms: Int? = null,
)

/**
 * The imported Yomitan dictionaries: each in a file of its own ([DictionaryFile]), and their list
 * here, in SQLite, with their order (their priority: the first comes first) and switches.
 * The list is kept in memory, so a lookup only opens the files of the dictionaries it needs.
 */
class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "dictionaries.db", null, 11), DictionaryStore {

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
                "file TEXT NOT NULL, forms INTEGER)",
        )
    }

    /**
     * Before 10, dictionaries were SQLite files (up to 8, tables here that they shared). Those
     * aren't carried over to [DictionaryFile]s: the dictionaries need importing again. 11 counts
     * the forms of other words; the dictionaries imported before don't know theirs (null).
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 10) {
            for (table in listOf("dictionaries", "terms", "term_meta", "kanji", "kanji_meta", "tags", "media")) db.execSQL("DROP TABLE IF EXISTS $table")
            onCreate(db)
            legacyMediaDir.deleteRecursively()
            folder.deleteRecursively()
        } else if (oldVersion < 11) {
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN forms INTEGER")
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
            "SELECT id, title, revision, terms, meta, kanji, frequencies, frequency_mode, enabled, images, position, file, forms FROM dictionaries ORDER BY position, id", null,
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                val info = DictionaryInfo(
                    c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5),
                    c.getInt(6), c.getString(7), c.getInt(8) != 0, c.getInt(9), if (c.isNull(12)) null else c.getInt(12),
                )
                Entry(info, c.getInt(10), c.getString(11))
            }
        }
        if (!tidied) {
            tidied = true
            // A dictionary's file and its suffix search keys ("x.hd.rev"); not what imports leave
            // behind ("x.hd.importing", files they sort in).
            val known = entries.flatMapTo(HashSet()) { listOf(it.file, it.file + ".rev") }
            folder.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
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

    /** What dictionary [id]'s file says about it, with the counts Yomitan's details show; null if it's gone. */
    fun facts(id: Long): DictionaryFacts? {
        val entry = entries().firstOrNull { it.info.id == id } ?: return null
        return runCatching { file(entry).facts() }.getOrNull()
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
        val from = dictionaries().indexOfFirst { it.id == id }
        if (from >= 0) moveTo(id, from + by)
    }

    /** Moves dictionary [id] to place [to] in the order (0 the first), as Yomitan's "Move to…"; one past either end goes to it. */
    fun moveTo(id: Long, to: Int) {
        val order = dictionaries().map { it.id }.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return
        val place = to.coerceIn(order.indices)
        if (place == from) return
        order.add(place, order.removeAt(from))
        setOrder(order)
    }

    private fun setOrder(order: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((i, dict) in order.withIndex()) {
                db.update("dictionaries", ContentValues().apply { put("position", i + 1) }, "id = ?", arrayOf(dict.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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
            put("terms", facts.terms)
            put("meta", facts.meta)
            put("kanji", facts.kanji)
            put("frequencies", facts.frequencies)
            put("images", facts.images)
            put("forms", facts.forms)
        })
        changed()
        return DictionaryInfo(id, facts.title, facts.revision, facts.terms, facts.meta, facts.kanji, facts.frequencies, facts.frequencyMode, enabled, facts.images, facts.forms)
    }

    /** A name for a new dictionary file; while it's written, it has ".importing" after it. */
    private fun newFile(): String {
        folder.mkdirs()
        return UUID.randomUUID().toString() + ".hd"
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
     * dictionary removed meanwhile (its file closed) gives none. One after the other: with the
     * files' filters, a dictionary without the forms looked up takes microseconds.
     */
    private fun <T> each(entries: List<Entry>, query: (Entry, DictionaryFile) -> List<T>): List<T> =
        entries.flatMap { entry ->
            try {
                query(entry, file(entry))
            } catch (e: IllegalStateException) {
                emptyList()
            }
        }

    override fun findTerms(forms: Collection<String>): List<TermEntry> {
        if (forms.isEmpty()) return emptyList()
        return each(searchable().filter { it.info.terms > 0 }) { e, f -> f.findTerms(forms, e.info.title, e.position) }
    }

    override fun dictionariesWithForms(): Set<String> = searchable().filter { (it.info.forms ?: 0) > 0 }.mapTo(HashSet()) { it.info.title }

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

    override fun findKeys(affix: String, suffix: Boolean, limit: Int, accepts: (String) -> Boolean): List<String> {
        if (affix.isEmpty()) return emptyList()
        return each(searchable().filter { it.info.terms > 0 }) { _, f -> f.findKeys(affix, suffix, limit, accepts) }.distinct()
    }

    override fun findTermsByMeaning(query: String, limit: Int): List<Pair<TermEntry, Int>> {
        val words = Meanings.words(query)
        if (words.isEmpty()) return emptyList()
        return each(searchable().filter { it.info.terms > 0 }) { e, f -> f.findTermsByMeaning(words, true, limit, e.info.title, e.position) }
            .sortedBy { it.second }.take(limit)
    }

    override fun meaningWordCounts(words: Collection<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((word, count) in each(searchable().filter { it.info.terms > 0 }) { _, f -> if (f.hasMeanings()) words.map { it to f.meaningWordCount(it) } else emptyList() }) {
            if (count > 0) out.merge(word, count, Int::plus)
        }
        return out
    }

    override fun existingForms(forms: Collection<String>): Set<String> =
        each(searchable().filter { it.info.terms > 0 }) { _, f -> forms.filter(f::hasForm) }.toSet()

    /** Each dictionary's tab by its language ([ResultTabs.forLanguage]), by file: it doesn't change */
    private val languageTabCache = ConcurrentHashMap<String, String>()

    /** Each term dictionary's tab by the language of its definitions ([ResultTabs.forLanguage]), by title: those turned on, or all */
    fun languageTabs(enabledOnly: Boolean = true): Map<String, String> =
        (if (enabledOnly) searchable() else entries()).filter { it.info.terms > 0 }.associate { e ->
            e.info.title to languageTabCache.getOrPut(e.file) {
                runCatching { file(e).let { ResultTabs.forLanguage(it.targetLanguage(), it.hasMeanings()) } }.getOrDefault(ResultTabs.JAPANESE)
            }
        }

    /** Whether dictionary [id] can be searched by meaning */
    fun meaningSearchAvailableFor(id: Long): Boolean = entries().firstOrNull { it.info.id == id }?.let { it.info.terms > 0 && file(it).hasMeanings() } ?: false

    /** Whether a dictionary turned on can be searched by meaning (English, German…) */
    fun meaningSearchAvailable(): Boolean = searchable().any { it.info.terms > 0 && file(it).hasMeanings() }

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

    /** Held while a dictionary file is made for suffix search after its import, so it isn't exported meanwhile */
    private val writing = Any()

    /** How far a long piece of work is: a short status line, and [done] of [total]; a [total] of 0 is unknown. */
    class Progress(val text: String, val done: Long = 0, val total: Long = 0)

    /**
     * Imports a Yomitan dictionary zip into a file of its own ([write]); [onProgress] gets how
     * far it is. A failed import leaves nothing behind.
     */
    fun import(zip: File, onProgress: (Progress) -> Unit): DictionaryInfo {
        val title = ZipFile(zip).use { file ->
            val indexEntry = file.getEntry("index.json") ?: throw IllegalArgumentException("Not a Yomitan dictionary (no index.json)")
            Yomitan.parseIndex(file.getInputStream(indexEntry).use { String(it.readBytes(), Charsets.UTF_8) }).title
        }
        require(entries().none { it.info.title == title }) { "\"$title\" is already imported" }
        // Once suffix search was used, imports make its keys too.
        val reverseKeys = searchable().any { runCatching { file(it).hasReverseKeys() }.getOrDefault(false) }
        val name = newFile()
        try {
            val facts = write(zip, writing(name), reverseKeys, onProgress)
            if (reverseKeys) File(writing(name).path + ".rev").renameTo(File(folder, "$name.rev"))
            finishWriting(name)
            return register(facts, name)
        } catch (e: Exception) {
            for (f in listOf(writing(name), File(folder, name), File(writing(name).path + ".rev"), File(folder, "$name.rev"))) f.delete()
            throw e
        }
    }

    /**
     * Replaces dictionary [oldId] with the one in [zip] (its update): the new one takes the old
     * one's place in the order and its on/off switch, and the old one goes once the new one is
     * imported. Its title may differ (Jitendex and JMdict have their date in it), and may be the
     * same: the old one is renamed meanwhile, and gets its title back if the import fails.
     */
    fun replace(oldId: Long, zip: File, onProgress: (Progress) -> Unit): DictionaryInfo {
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
            File(folder, name).delete()
            File(folder, "$name.rev").delete()
        }
    }

    // ---- Collections ---------------------------------------------------------------------------

    /** What importing a collection did: the dictionaries it added, and those it left out because they were already there. */
    class CollectionImport(val added: List<String>, val skipped: List<String>)

    /**
     * Writes every dictionary, in their order and with their switches, as a [HiroiCollection]:
     * their files as [DictionaryFile.writePortable] writes them, zipped. [onProgress] gets the MB
     * written and the total. Returns how many dictionaries it wrote.
     */
    fun exportCollection(out: OutputStream, onProgress: (Long, Long) -> Unit): Int = synchronized(writing) {
        val list = entries()
        val items = list.mapIndexed { i, entry -> HiroiCollection.Item(entry.info.title, HiroiCollection.path(i + 1), entry.info.enabled) }
        val total = list.sumOf { file(it).portableSize() }
        var written = 0L
        ZipOutputStream(out.buffered(BUFFER)).use { zip ->
            // Glossaries and images are deflated already; the fastest level does the rest nearly as well as the best.
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(HiroiCollection.MANIFEST))
            zip.write(HiroiCollection.manifest(items).toByteArray())
            zip.closeEntry()
            for ((entry, item) in list.zip(items)) {
                zip.putNextEntry(ZipEntry(item.path))
                file(entry).writePortable(object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        zip.write(b, off, len)
                        written += len
                        onProgress(written shr 20, total shr 20)
                    }
                })
                zip.closeEntry()
            }
        }
        list.size
    }

    /**
     * Adds the dictionaries of a [HiroiCollection] after the ones here, with their switches;
     * those already here (by title) are left as they are. [onProgress] gets the bytes of [input]
     * read so far. A failed import leaves nothing behind.
     */
    fun importCollection(input: InputStream, onProgress: (Long) -> Unit): CollectionImport {
        val titles = entries().mapTo(HashSet()) { it.info.title }
        var items: Map<String, HiroiCollection.Item>? = null
        val added = mutableListOf<DictionaryInfo>()
        val skipped = mutableListOf<String>()
        var read = 0L
        try {
            val counted = object : FilterInputStream(input) {
                override fun read(): Int = super.read().also { if (it >= 0) onProgress(++read) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                    if (n > 0) {
                        read += n
                        onProgress(read)
                    }
                }
            }
            ZipInputStream(counted.buffered(BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == HiroiCollection.MANIFEST) {
                        items = HiroiCollection.items(String(zip.readBytes(), Charsets.UTF_8)).associateBy { it.path }
                        continue
                    }
                    if (entry.isDirectory || !entry.name.endsWith(HiroiCollection.EXTENSION)) continue
                    val listed = items ?: throw IllegalArgumentException("Not a Hiroi dictionary collection")
                    val name = newFile()
                    val portable = File(folder, "$name.portable")
                    try {
                        portable.outputStream().use { out -> zip.copyTo(out, BUFFER) }
                        // The keys again, which the collection leaves out
                        DictionaryFile.restore(portable, writing(name))
                        portable.delete()
                        val facts = DictionaryFile.open(writing(name)).use { it.facts() }
                        if (facts.title in titles) {
                            skipped += facts.title
                            writing(name).delete()
                        } else {
                            finishWriting(name)
                            added += register(facts, name, listed[entry.name]?.enabled ?: true)
                            titles += facts.title
                        }
                    } catch (e: Exception) {
                        portable.delete()
                        writing(name).delete()
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

    companion object {
        private val BANK = Regex("(term_bank|term_meta_bank|kanji_bank|kanji_meta_bank|tag_bank)_(\\d+)\\.json")

        // Yomitan's words for telling ranks from occurrence counts (sort-frequency-dictionary-controller.js)
        private val MORE_COMMON = listOf("来る", "言う", "出る", "入る", "方", "男", "女", "今", "何", "時")
        private val LESS_COMMON = listOf("行なう", "論じる", "過す", "行方", "人口", "猫", "犬", "滝", "理", "暁")

        private val CORES = Runtime.getRuntime().availableProcessors()

        /** Threads parsing banks while another writes them */
        private val PARSE_THREADS = (CORES - 1).coerceIn(1, 4)

        /** Rows a bank is read in at a time, and how many the glossaries' deflate dictionary is made from */
        private const val CHUNK = 2000
        private const val SAMPLE = 8000

        private const val BUFFER = 1 shl 16

        private class EnoughForSample : RuntimeException()

        /** A zip entry's size, guessed from its compressed size if the zip doesn't say */
        private fun size(entry: ZipEntry): Long = entry.size.takeIf { it > 0 } ?: (entry.compressedSize * 10)

        private fun <T> Future<T>.result(): T = try {
            get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

        /** An image's size: an SVG's from its attributes (100 × 100 if it has none, Yomitan's default), else the image's own. */
        private fun imageSize(type: String, bytes: ByteArray): Pair<Int, Int> =
            if (type == "image/svg+xml") {
                DictionaryImages.svgSize(String(bytes)) ?: (100 to 100)
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth > 0) options.outWidth to options.outHeight else 100 to 100
            }

        /**
         * Writes the Yomitan dictionary in [zip] as a [DictionaryFile] at [out] (and its suffix
         * search keys if [reverseKeys]); [onProgress] gets how far it is, by the bytes of the images
         * and banks done, then (of no telling how long) the index being written. The banks are read
         * as they're read, a chunk of rows at a time: some are 50 MB of JSON, some have a million
         * rows. Several threads parse them and deflate the glossaries while this one writes, in
         * their order.
         */
        fun write(zip: File, out: File, reverseKeys: Boolean, onProgress: (Progress) -> Unit): DictionaryFacts = ZipFile(zip).use { file ->
            val indexJson = file.getInputStream(file.getEntry("index.json")).use { String(it.readBytes(), Charsets.UTF_8) }
            val index = Yomitan.parseIndex(indexJson)
            val styles = file.getEntry("styles.css")?.let { entry -> file.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) } }
            val banks = file.entries().asSequence()
                .mapNotNull { entry -> BANK.matchEntire(entry.name)?.let { entry to it } }
                .sortedWith(compareBy({ it.second.groupValues[1] }, { it.second.groupValues[2].toInt() }))
                .map { (entry, match) -> entry to match.groupValues[1] }
                .toList()
            val images = file.entries().asSequence().filter { !it.isDirectory && DictionaryImages.mediaType(it.name) != null }.toList()
            val total = images.sumOf(::size) + banks.sumOf { size(it.first) }
            var done = 0L
            fun advance(entry: ZipEntry, step: String) {
                done += size(entry)
                onProgress(Progress("${index.title}: $step", done, total))
            }
            onProgress(Progress("${index.title}: reading entries", 0, total))
            fun reader(entry: ZipEntry) = InputStreamReader(file.getInputStream(entry), Charsets.UTF_8)
            fun json(entry: ZipEntry) = file.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }

            // What every glossary block starts from, from the start of the first term bank
            val sample = ArrayList<String>()
            banks.firstOrNull { it.second == "term_bank" }?.let { (entry, _) ->
                try {
                    reader(entry).use { r ->
                        Yomitan.parseTermBank(r, index.format, CHUNK) { rows ->
                            sample += rows.map { it.glossary }
                            if (sample.size >= SAMPLE) throw EnoughForSample()
                        }
                    }
                } catch (e: EnoughForSample) {
                }
            }

            // Definitions in English, German… are indexed by their words, for searching by meaning
            val meanings = Meanings.inLatinLetters(JSONObject(indexJson).optString("targetLanguage").ifEmpty { null }, sample)
            DictionaryFile.Writer(out, DictionaryFile.trainDictionary(sample), meanings = meanings).use { writer ->
                // Images first, so the definitions can get their sizes as Yomitan stores them.
                val sizes = HashMap<String, Pair<Int, Int>>()
                for (entry in images) {
                    val bytes = file.getInputStream(entry).use { it.readBytes() }
                    val type = DictionaryImages.mediaType(entry.name)!!
                    val size = imageSize(type, bytes)
                    writer.media(entry.name, bytes, type, size)
                    sizes[entry.name] = size
                    advance(entry, "reading images")
                }

                /** Reads bank [entry], of [kind], on a parsing thread; what it returns writes its rows. */
                fun parse(entry: ZipEntry, kind: String): () -> Unit = when (kind) {
                    "term_bank" -> {
                        val prepared = ArrayList<DictionaryFile.PreparedTerms>()
                        reader(entry).use { r ->
                            Yomitan.parseTermBank(r, index.format, CHUNK) { rows ->
                                val withSizes = if (sizes.isEmpty()) rows else rows.map { it.copy(glossary = DictionaryImages.prepare(it.glossary, sizes::get)) }
                                prepared += writer.prepareTerms(withSizes)
                            }
                        }
                        ({ prepared.forEach(writer::terms) })
                    }
                    "term_meta_bank", "kanji_meta_bank" -> {
                        val prepared = ArrayList<DictionaryFile.PreparedMeta>()
                        reader(entry).use { r -> Yomitan.parseTermMetaBank(r, CHUNK) { rows -> prepared += writer.prepareMeta(rows, kind == "kanji_meta_bank") } }
                        ({ prepared.forEach(writer::meta) })
                    }
                    "kanji_bank" -> Yomitan.parseKanjiBank(json(entry), index.format).let { rows -> { writer.kanji(rows) } }
                    "tag_bank" -> Yomitan.parseTagBank(json(entry)).let { rows -> { writer.tags(rows) } }
                    else -> ({})
                }

                val parsers = Executors.newFixedThreadPool(PARSE_THREADS)
                try {
                    // A few banks ahead of the writer, as many as the memory allows: a bank being
                    // read takes a chunk's rows and what the rows before it became.
                    val budget = Runtime.getRuntime().maxMemory() / 3
                    val pending = ArrayDeque<Pair<Long, Future<() -> Unit>>>()
                    var next = 0
                    var parsing = 0L
                    fun parseMore() {
                        while (next < banks.size && pending.size < PARSE_THREADS * 2) {
                            val (entry, kind) = banks[next]
                            val cost = minOf(4 * size(entry), 24L shl 20)
                            if (pending.isNotEmpty() && parsing + cost > budget) return
                            parsing += cost
                            next++
                            pending += cost to parsers.submit(Callable { parse(entry, kind) })
                        }
                    }
                    parseMore()
                    var written = 0
                    while (pending.isNotEmpty()) {
                        val (cost, parsed) = pending.removeFirst()
                        parsed.result()()
                        parsing -= cost
                        parseMore()
                        advance(banks[written++].first, "reading entries")
                    }
                } finally {
                    parsers.shutdownNow()
                }
                onProgress(Progress("${index.title}: writing the index…"))
                writer.finish(DictionaryFacts(index.title, index.revision, index.frequencyMode, indexJson, styles), reverseKeys)
            }
        }

        @Volatile
        private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also { instance = it }
            }
    }
}
