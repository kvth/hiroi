package io.github.kvth.hiroi.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import io.github.kvth.hiroi.settings.SettingsBackup
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

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

/** Imported Yomitan dictionaries in SQLite. Priority is import order: the first imported comes first. */
class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "dictionaries.db", null, 7), DictionaryStore {

    /** The dictionaries' images, a folder per dictionary; the media table says what they are. */
    private val mediaDir = File(context.filesDir, "dictionary-media")

    init {
        setWriteAheadLoggingEnabled(true) // lookups keep working during an import
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dictionaries (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT, " +
                "terms INTEGER DEFAULT 0, meta INTEGER DEFAULT 0, kanji INTEGER DEFAULT 0, styles TEXT, " +
                "frequency_mode TEXT, frequencies INTEGER DEFAULT 0, position INTEGER DEFAULT 0, summary TEXT, enabled INTEGER DEFAULT 1, images INTEGER DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE terms (dict INTEGER, expression TEXT, reading TEXT, definition_tags TEXT, rules TEXT, " +
                "score INTEGER, glossary TEXT, sequence INTEGER, term_tags TEXT)",
        )
        db.execSQL("CREATE INDEX terms_expression ON terms (expression)")
        db.execSQL("CREATE INDEX terms_reading ON terms (reading)")
        db.execSQL("CREATE TABLE term_meta (dict INTEGER, expression TEXT, mode TEXT, data TEXT)")
        db.execSQL("CREATE INDEX term_meta_expression ON term_meta (expression)")
        db.execSQL("CREATE TABLE kanji (dict INTEGER, character TEXT, onyomi TEXT, kunyomi TEXT, tags TEXT, meanings TEXT)")
        db.execSQL("CREATE INDEX kanji_character ON kanji (character)")
        createKanjiMeta(db)
        createTags(db)
        createMedia(db)
    }

    private fun createMedia(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (dict INTEGER, path TEXT, media_type TEXT, width INTEGER, height INTEGER, file TEXT)")
        db.execSQL("CREATE INDEX media_path ON media (dict, path)")
    }

    private fun createTags(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tags (dict INTEGER, name TEXT, category TEXT, ord INTEGER, notes TEXT, score INTEGER)")
        db.execSQL("CREATE INDEX tags_name ON tags (name)")
    }

    private fun createKanjiMeta(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kanji_meta (dict INTEGER, character TEXT, mode TEXT, data TEXT)")
        db.execSQL("CREATE INDEX kanji_meta_character ON kanji_meta (character)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: the dictionary's styles.css, which Yomitan applies to its definitions
        if (oldVersion < 2) db.execSQL("ALTER TABLE dictionaries ADD COLUMN styles TEXT")
        // 3: frequency lists: their mode (rank or occurrence) and size, and kanji frequencies
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN frequency_mode TEXT")
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN frequencies INTEGER DEFAULT 0")
            db.execSQL("UPDATE dictionaries SET frequencies = (SELECT COUNT(*) FROM term_meta m WHERE m.dict = dictionaries.id AND m.mode = 'freq')")
            createKanjiMeta(db)
        }
        // 4: tag banks, for Anki cards rendered with Yomitan's templates (dictionaries need importing again to get theirs)
        if (oldVersion < 4) createTags(db)
        // 5: the order of the dictionaries (their priority), and what their index.json says, for exporting them
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN position INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN summary TEXT")
            db.execSQL("UPDATE dictionaries SET position = id")
        }
        // 6: dictionaries can be turned off, as in Yomitan
        if (oldVersion < 6) db.execSQL("ALTER TABLE dictionaries ADD COLUMN enabled INTEGER DEFAULT 1")
        // 7: dictionary images (dictionaries need importing again to get theirs)
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE dictionaries ADD COLUMN images INTEGER DEFAULT 0")
            createMedia(db)
        }
    }

    /** Each dictionary's styles.css by title, for showing its definitions as Yomitan does. */
    fun styles(): Map<String, String> =
        readableDatabase.rawQuery("SELECT title, styles FROM dictionaries WHERE styles IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun dictionaries(): List<DictionaryInfo> =
        readableDatabase.rawQuery(
            "SELECT id, title, revision, terms, meta, kanji, frequencies, frequency_mode, enabled, images FROM dictionaries ORDER BY position, id", null,
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                DictionaryInfo(
                    c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5),
                    c.getInt(6), c.getString(7), c.getInt(8) != 0, c.getInt(9),
                )
            }
        }

    /**
     * Whether lower values mean more common words in frequency dictionary [title] (ranks), as
     * Yomitan decides it: from the dictionary's frequencyMode, else by comparing its values for
     * common and rare words. Null if it can't tell.
     */
    fun frequencyAscending(title: String): Boolean? {
        val info = dictionaries().firstOrNull { it.title == title } ?: return null
        when (info.frequencyMode) {
            "rank-based" -> return true
            "occurrence-based" -> return false
        }
        val values = findTermMeta(MORE_COMMON + LESS_COMMON)
            .filter { it.dictionary == title }
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

    fun hasDictionaries(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM dictionaries LIMIT 1", null).use { it.moveToFirst() }

    /** The dictionaries lookups use, in their order: those turned on. */
    fun enabledDictionaries(): List<DictionaryInfo> = dictionaries().filter { it.enabled }

    /**
     * Turns dictionary [id] on or off, as Yomitan's switch per dictionary: one that's off stays
     * installed but is left out of lookups, frequency sorting and Anki's per-dictionary markers.
     */
    fun setEnabled(id: Long, enabled: Boolean) {
        writableDatabase.update("dictionaries", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    /** Imports a Yomitan dictionary zip; [onProgress] gets short status lines. A failed import leaves nothing behind. */
    fun import(zip: File, onProgress: (String) -> Unit): DictionaryInfo {
        ZipFile(zip).use { file ->
            val indexEntry = file.getEntry("index.json")
                ?: throw IllegalArgumentException("Not a Yomitan dictionary (no index.json)")
            val indexJson = file.getInputStream(indexEntry).reader().readText()
            val index = Yomitan.parseIndex(indexJson)
            val db = writableDatabase
            require(DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM dictionaries WHERE title = ?", arrayOf(index.title)) == 0L) {
                "\"${index.title}\" is already imported"
            }
            val id = db.insertOrThrow(
                "dictionaries", null,
                ContentValues().apply {
                    put("title", index.title)
                    put("revision", index.revision)
                    put("frequency_mode", index.frequencyMode)
                    put("position", nextPosition(db))
                    put("summary", YomitanCollection.summaryFromIndex(indexJson))
                    file.getEntry("styles.css")?.let { put("styles", file.getInputStream(it).reader().readText()) }
                },
            )
            try {
                val banks = file.entries().asSequence()
                    .mapNotNull { entry -> BANK.matchEntire(entry.name)?.let { entry to it } }
                    .sortedWith(compareBy({ it.second.groupValues[1] }, { it.second.groupValues[2].toInt() }))
                    .toList()
                // Images first, so the definitions can get their sizes as Yomitan stores them.
                val sizes = HashMap<String, Pair<Int, Int>>()
                for (entry in file.entries().asSequence().filter { !it.isDirectory && DictionaryImages.mediaType(it.name) != null }.toList()) {
                    val bytes = file.getInputStream(entry).use { it.readBytes() }
                    sizes[entry.name] = addMedia(db, id, entry.name, bytes)
                }
                if (sizes.isNotEmpty()) onProgress("${index.title}: ${sizes.size} images")
                var terms = 0
                var meta = 0
                var kanji = 0
                var frequencies = 0
                for ((n, bank) in banks.withIndex()) {
                    val (entry, match) = bank
                    onProgress("${index.title}: ${n + 1} / ${banks.size}")
                    val json = file.getInputStream(entry).reader().readText()
                    when (match.groupValues[1]) {
                        "term_bank" -> terms += insertTerms(
                            db, id,
                            Yomitan.parseTermBank(json, index.format).let { rows ->
                                if (sizes.isEmpty()) rows else rows.map { it.copy(glossary = DictionaryImages.prepare(it.glossary, sizes::get)) }
                            },
                        )
                        "term_meta_bank" -> Yomitan.parseTermMetaBank(json).let { rows ->
                            meta += insertMeta(db, id, rows, "term_meta")
                            frequencies += rows.count { it.mode == "freq" }
                        }
                        "tag_bank" -> insertTags(db, id, Yomitan.parseTagBank(json))
                        "kanji_meta_bank" -> Yomitan.parseTermMetaBank(json).let { rows ->
                            meta += insertMeta(db, id, rows, "kanji_meta")
                            frequencies += rows.count { it.mode == "freq" }
                        }
                        "kanji_bank" -> kanji += insertKanji(db, id, Yomitan.parseKanjiBank(json, index.format))
                    }
                }
                db.update(
                    "dictionaries",
                    ContentValues().apply {
                        put("terms", terms)
                        put("meta", meta)
                        put("kanji", kanji)
                        put("frequencies", frequencies)
                        put("images", sizes.size)
                    },
                    "id = ?", arrayOf(id.toString()),
                )
                return DictionaryInfo(id, index.title, index.revision, terms, meta, kanji, frequencies, index.frequencyMode)
            } catch (e: Exception) {
                delete(id)
                throw e
            }
        }
    }

    /** What importing a collection did: the dictionaries it added, and those it left out because they were already there. */
    class CollectionImport(val added: List<String>, val skipped: List<String>)

    /**
     * Writes every dictionary, in their order, as a Yomitan dictionary collection
     * ([YomitanCollection]), which Yomitan can import too.
     */
    fun exportCollection(out: OutputStream, onProgress: (String) -> Unit) {
        val db = readableDatabase
        val dictionaries = dictionaries()
        fun count(table: String) = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM $table", null).toInt()
        fun byDict(sql: String): Map<Long, Int> = db.rawQuery(sql, null).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) } }
        fun byDictMode(table: String): Map<Long, Map<String, Int>> = db.rawQuery("SELECT dict, mode, COUNT(*) FROM $table GROUP BY dict, mode", null).use { c ->
            val out = HashMap<Long, HashMap<String, Int>>()
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
            out
        }
        val terms = byDict("SELECT dict, COUNT(*) FROM terms GROUP BY dict")
        val kanji = byDict("SELECT dict, COUNT(*) FROM kanji GROUP BY dict")
        val tags = byDict("SELECT dict, COUNT(*) FROM tags GROUP BY dict")
        val termMeta = byDictMode("term_meta")
        val kanjiMeta = byDictMode("kanji_meta")
        val stored = db.rawQuery("SELECT id, summary, styles FROM dictionaries", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1) to c.getString(2)) }
        }
        val total = listOf("terms", "term_meta", "kanji", "kanji_meta", "tags", "media").sumOf(::count)
        var written = 0
        fun progress() {
            if (++written % 10_000 == 0) onProgress("$written / $total")
        }
        YomitanCollection.Writer(out.bufferedWriter()).use { writer ->
            writer.begin(
                mapOf(
                    "dictionaries" to dictionaries.size, "kanji" to count("kanji"), "kanjiMeta" to count("kanji_meta"), "media" to count("media"),
                    "tagMeta" to count("tags"), "termMeta" to count("term_meta"), "terms" to count("terms"),
                ),
            )
            for (table in YomitanCollection.TABLES.keys) writer.table(table) {
                when (table) {
                    "dictionaries" -> for (info in dictionaries) {
                        val counts = YomitanCollection.Counts(
                            terms[info.id] ?: 0, termMeta[info.id].orEmpty(), kanji[info.id] ?: 0, kanjiMeta[info.id].orEmpty(), tags[info.id] ?: 0,
                        )
                        val (summary, styles) = stored[info.id] ?: (null to null)
                        writer.dictionary(YomitanCollection.summary(summary, info, styles, counts, System.currentTimeMillis()))
                    }
                    "terms" -> rows(
                        "SELECT t.expression, t.reading, t.definition_tags, t.rules, t.score, t.glossary, t.sequence, t.term_tags, d.title " +
                            "FROM terms t JOIN dictionaries d ON d.id = t.dict",
                    ) { c ->
                        writer.term(
                            TermEntry(
                                c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4),
                                c.getString(5), c.getLong(6), c.getString(7).orEmpty(), c.getString(8),
                            ),
                        )
                        progress()
                    }
                    "termMeta" -> rows("SELECT m.expression, m.mode, m.data, d.title FROM term_meta m JOIN dictionaries d ON d.id = m.dict") { c ->
                        writer.meta(TermMeta(c.getString(0), c.getString(1), c.getString(2), c.getString(3)), "expression")
                        progress()
                    }
                    "kanjiMeta" -> rows("SELECT m.character, m.mode, m.data, d.title FROM kanji_meta m JOIN dictionaries d ON d.id = m.dict") { c ->
                        writer.meta(TermMeta(c.getString(0), c.getString(1), c.getString(2), c.getString(3)), "character")
                        progress()
                    }
                    "kanji" -> rows("SELECT k.character, k.onyomi, k.kunyomi, k.tags, k.meanings, d.title FROM kanji k JOIN dictionaries d ON d.id = k.dict") { c ->
                        writer.kanji(
                            KanjiEntry(
                                c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                                c.getString(4).orEmpty().split('\n').filter { it.isNotEmpty() }, c.getString(5),
                            ),
                        )
                        progress()
                    }
                    "media" -> rows("SELECT m.path, m.media_type, m.width, m.height, m.file, m.dict, d.title FROM media m JOIN dictionaries d ON d.id = m.dict") { c ->
                        val file = File(File(mediaDir, c.getLong(5).toString()), c.getString(4).orEmpty())
                        if (file.isFile) writer.media(c.getString(6), DictionaryMedia(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)) { file.readBytes() }, file.readBytes())
                        progress()
                    }
                    "tagMeta" -> rows("SELECT t.name, t.category, t.ord, t.notes, t.score, d.title FROM tags t JOIN dictionaries d ON d.id = t.dict") { c ->
                        writer.tag(TagInfo(c.getString(0), c.getString(1).orEmpty(), c.getInt(2), c.getString(3).orEmpty(), c.getInt(4), c.getString(5)))
                        progress()
                    }
                }
            }
            writer.end()
        }
    }

    private inline fun rows(sql: String, each: (android.database.Cursor) -> Unit) {
        readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) each(c) }
    }

    /**
     * Adds the dictionaries of a Yomitan dictionary collection (Yomitan's export, or Hiroi's),
     * after the ones already here; those already imported (by title) are left as they are. A
     * failed import leaves nothing behind.
     */
    fun importCollection(input: InputStream, onProgress: (String) -> Unit): CollectionImport {
        val db = writableDatabase
        val existing = dictionaries().mapTo(HashSet()) { it.title }
        val ids = LinkedHashMap<String, Long>()
        val skipped = LinkedHashSet<String>()
        /** The dictionary [title] is imported into; null if it was already there. */
        fun dict(title: String): Long? {
            if (title in existing) {
                skipped += title
                return null
            }
            return ids.getOrPut(title) {
                db.insertOrThrow("dictionaries", null, ContentValues().apply {
                    put("title", title)
                    put("position", nextPosition(db))
                })
            }
        }
        fun <T> insert(rows: List<T>, title: (T) -> String, add: (Long, List<T>) -> Unit) {
            for ((name, group) in rows.groupBy(title)) dict(name)?.let { add(it, group) }
        }
        try {
            YomitanCollection.read(input.bufferedReader(), object : YomitanCollection.Sink {
                override fun dictionary(summary: org.json.JSONObject) {
                    val id = dict(summary.optString("title")) ?: return
                    db.update("dictionaries", ContentValues().apply {
                        put("revision", summary.optString("revision"))
                        put("frequency_mode", summary.optString("frequencyMode").takeIf { it in Yomitan.FREQUENCY_MODES })
                        put("styles", summary.optString("styles").takeIf { it.isNotEmpty() })
                        put("summary", summary.toString())
                    }, "id = ?", arrayOf(id.toString()))
                }
                override fun terms(rows: List<TermEntry>) = insert(rows, { it.dictionary }) { id, group -> insertTerms(db, id, group) }
                override fun termMeta(rows: List<TermMeta>) = insert(rows, { it.dictionary }) { id, group -> insertMeta(db, id, group, "term_meta") }
                override fun kanji(rows: List<KanjiEntry>) = insert(rows, { it.dictionary }) { id, group -> insertKanji(db, id, group) }
                override fun kanjiMeta(rows: List<TermMeta>) = insert(rows, { it.dictionary }) { id, group -> insertMeta(db, id, group, "kanji_meta") }
                override fun tags(rows: List<TagInfo>) = insert(rows, { it.dictionary }) { id, group -> insertTags(db, id, group) }
                override fun media(rows: List<YomitanCollection.MediaRow>) = insert(rows, { it.dictionary }) { id, group ->
                    for (m in group) addMedia(db, id, m.path, m.content, m.mediaType.ifEmpty { null }, (m.width to m.height).takeIf { m.width > 0 && m.height > 0 })
                }
            }) { read -> onProgress("$read") }
            for (id in ids.values) updateCounts(db, id)
        } catch (e: Exception) {
            ids.values.forEach(::delete)
            throw e
        }
        return CollectionImport(ids.keys.toList(), skipped.toList())
    }

    /** The counts shown in the dictionary list, from the rows themselves. */
    private fun updateCounts(db: SQLiteDatabase, id: Long) {
        fun count(sql: String) = DatabaseUtils.longForQuery(db, sql, arrayOf(id.toString()))
        db.update("dictionaries", ContentValues().apply {
            put("terms", count("SELECT COUNT(*) FROM terms WHERE dict = ?"))
            put("meta", count("SELECT COUNT(*) FROM term_meta WHERE dict = ?") + count("SELECT COUNT(*) FROM kanji_meta WHERE dict = ?"))
            put("kanji", count("SELECT COUNT(*) FROM kanji WHERE dict = ?"))
            put("images", count("SELECT COUNT(*) FROM media WHERE dict = ?"))
            put("frequencies", count("SELECT COUNT(*) FROM term_meta WHERE dict = ? AND mode = 'freq'") + count("SELECT COUNT(*) FROM kanji_meta WHERE dict = ? AND mode = 'freq'"))
        }, "id = ?", arrayOf(id.toString()))
    }

    private fun nextPosition(db: SQLiteDatabase): Long =
        DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(position), 0) + 1 FROM dictionaries", null)

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
        inTransaction(writableDatabase) {
            for ((i, dict) in order.withIndex()) {
                writableDatabase.update("dictionaries", ContentValues().apply { put("position", i + 1) }, "id = ?", arrayOf(dict.toString()))
            }
        }
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
        inTransaction(writableDatabase) {
            for ((i, dict) in order.withIndex()) {
                writableDatabase.update("dictionaries", ContentValues().apply { put("position", i + 1) }, "id = ?", arrayOf(dict.toString()))
            }
            for ((info, enabled) in listed) setEnabled(info.id, enabled)
        }
        return states.map { it.title }.filter { it !in byTitle }
    }

    /** Dictionary [id]'s index.json as stored (with its update links), if it was stored. */
    fun summary(id: Long): String? =
        readableDatabase.rawQuery("SELECT summary FROM dictionaries WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
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
        db.update("dictionaries", ContentValues().apply { put("title", "$title (updating)") }, "id = ?", arrayOf(oldId.toString()))
        val info = try {
            import(zip, onProgress)
        } catch (e: Exception) {
            db.update("dictionaries", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(oldId.toString()))
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
        delete(oldId)
        return dictionaries().first { it.id == info.id }
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("terms", "term_meta", "kanji", "kanji_meta", "tags", "media")) db.delete(table, "dict = ?", arrayOf(id.toString()))
            File(mediaDir, id.toString()).deleteRecursively()
            db.delete("dictionaries", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertTerms(db: SQLiteDatabase, dict: Long, rows: List<TermEntry>): Int = inTransaction(db) {
        val insert = db.compileStatement(
            "INSERT INTO terms (dict, expression, reading, definition_tags, rules, score, glossary, sequence, term_tags) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        for (row in rows) {
            insert.bindLong(1, dict)
            insert.bindString(2, row.expression)
            insert.bindString(3, row.reading)
            insert.bindString(4, row.definitionTags)
            insert.bindString(5, row.rules)
            insert.bindLong(6, row.score.toLong())
            insert.bindString(7, row.glossary)
            insert.bindLong(8, row.sequence)
            insert.bindString(9, row.termTags)
            insert.executeInsert()
        }
        rows.size
    }

    /** Into [table]: term_meta, or kanji_meta (whose column is "character"). */
    private fun insertMeta(db: SQLiteDatabase, dict: Long, rows: List<TermMeta>, table: String): Int = inTransaction(db) {
        val key = if (table == "kanji_meta") "character" else "expression"
        val insert = db.compileStatement("INSERT INTO $table (dict, $key, mode, data) VALUES (?, ?, ?, ?)")
        for (row in rows) {
            insert.bindLong(1, dict)
            insert.bindString(2, row.expression)
            insert.bindString(3, row.mode)
            insert.bindString(4, row.data)
            insert.executeInsert()
        }
        rows.size
    }

    private fun insertTags(db: SQLiteDatabase, dict: Long, rows: List<TagInfo>): Int = inTransaction(db) {
        val insert = db.compileStatement("INSERT INTO tags (dict, name, category, ord, notes, score) VALUES (?, ?, ?, ?, ?, ?)")
        for (row in rows) {
            insert.bindLong(1, dict)
            insert.bindString(2, row.name)
            insert.bindString(3, row.category)
            insert.bindLong(4, row.order.toLong())
            insert.bindString(5, row.notes)
            insert.bindLong(6, row.score.toLong())
            insert.executeInsert()
        }
        rows.size
    }

    private fun insertKanji(db: SQLiteDatabase, dict: Long, rows: List<KanjiEntry>): Int = inTransaction(db) {
        val insert = db.compileStatement(
            "INSERT INTO kanji (dict, character, onyomi, kunyomi, tags, meanings) VALUES (?, ?, ?, ?, ?, ?)",
        )
        for (row in rows) {
            insert.bindLong(1, dict)
            insert.bindString(2, row.character)
            insert.bindString(3, row.onyomi)
            insert.bindString(4, row.kunyomi)
            insert.bindString(5, row.tags)
            insert.bindString(6, row.meanings.joinToString("\n"))
            insert.executeInsert()
        }
        rows.size
    }

    private inline fun <T> inTransaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun findTerms(forms: Collection<String>): List<TermEntry> {
        val out = mutableListOf<TermEntry>()
        for (chunk in forms.chunked(MAX_ARGS / 2)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT t.expression, t.reading, t.definition_tags, t.rules, t.score, t.glossary, t.sequence, t.term_tags, " +
                    "d.title, d.position FROM terms t JOIN dictionaries d ON d.id = t.dict " +
                    "WHERE (t.expression IN ($placeholders) OR t.reading IN ($placeholders)) AND d.enabled = 1",
                (chunk + chunk).toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    out += TermEntry(
                        expression = c.getString(0), reading = c.getString(1), definitionTags = c.getString(2),
                        rules = c.getString(3), score = c.getInt(4), glossary = c.getString(5), sequence = c.getLong(6),
                        termTags = c.getString(7), dictionary = c.getString(8), priority = c.getInt(9),
                    )
                }
            }
        }
        return out
    }

    /** Whether the index on sequence numbers is there (see [findTermsBySequence]) */
    @Volatile
    private var sequenceIndex = false

    /**
     * Its index is made on first use rather than when the database opens: on a big dictionary
     * that takes a while, and only grouping related terms needs it.
     */
    override fun findTermsBySequence(dictionary: String, sequences: Collection<Long>): List<TermEntry> {
        if (sequences.isEmpty()) return emptyList()
        if (!sequenceIndex) {
            writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS terms_sequence ON terms (dict, sequence)")
            sequenceIndex = true
        }
        val out = mutableListOf<TermEntry>()
        for (chunk in sequences.chunked(MAX_ARGS - 1)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT t.expression, t.reading, t.definition_tags, t.rules, t.score, t.glossary, t.sequence, t.term_tags, " +
                    "d.title, d.position FROM terms t JOIN dictionaries d ON d.id = t.dict " +
                    "WHERE d.title = ? AND t.dict = d.id AND t.sequence IN ($placeholders)",
                (listOf(dictionary) + chunk.map { it.toString() }).toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    out += TermEntry(
                        expression = c.getString(0), reading = c.getString(1), definitionTags = c.getString(2),
                        rules = c.getString(3), score = c.getInt(4), glossary = c.getString(5), sequence = c.getLong(6),
                        termTags = c.getString(7), dictionary = c.getString(8), priority = c.getInt(9),
                    )
                }
            }
        }
        return out
    }

    override fun findTermMeta(expressions: Collection<String>): List<TermMeta> {
        val out = mutableListOf<TermMeta>()
        for (chunk in expressions.chunked(MAX_ARGS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT m.expression, m.mode, m.data, d.title, d.position FROM term_meta m JOIN dictionaries d ON d.id = m.dict " +
                    "WHERE m.expression IN ($placeholders) AND d.enabled = 1",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    out += TermMeta(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4))
                }
            }
        }
        return out
    }

    override fun findTags(dictionary: String, names: Collection<String>): List<TagInfo> {
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT t.name, t.category, t.ord, t.notes, t.score FROM tags t JOIN dictionaries d ON d.id = t.dict " +
                "WHERE d.title = ? AND t.name IN ($placeholders)",
            (listOf(dictionary) + names).toTypedArray(),
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                TagInfo(c.getString(0), c.getString(1).orEmpty(), c.getInt(2), c.getString(3).orEmpty(), c.getInt(4), dictionary)
            }
        }
    }

    /**
     * Stores image [path] of dictionary [dict] and returns its size: from the image itself, or for
     * an SVG from its attributes (100 × 100 when it has none, Yomitan's default).
     */
    private fun addMedia(db: SQLiteDatabase, dict: Long, path: String, bytes: ByteArray, mediaType: String? = null, knownSize: Pair<Int, Int>? = null): Pair<Int, Int> {
        val type = mediaType ?: DictionaryImages.mediaType(path) ?: "application/octet-stream"
        val size = knownSize ?: if (type == "image/svg+xml") {
            DictionaryImages.svgSize(String(bytes)) ?: (100 to 100)
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth > 0) options.outWidth to options.outHeight else 100 to 100
        }
        val dir = File(mediaDir, dict.toString()).apply { mkdirs() }
        val row = db.insertOrThrow("media", null, ContentValues().apply {
            put("dict", dict)
            put("path", path)
            put("media_type", type)
            put("width", size.first)
            put("height", size.second)
        })
        val name = "$row.${path.substringAfterLast('.', "bin").take(5)}"
        File(dir, name).writeBytes(bytes)
        db.update("media", ContentValues().apply { put("file", name) }, "rowid = ?", arrayOf(row.toString()))
        return size
    }

    override fun findMedia(dictionary: String, path: String): DictionaryMedia? =
        readableDatabase.rawQuery(
            "SELECT m.path, m.media_type, m.width, m.height, m.file, m.dict FROM media m JOIN dictionaries d ON d.id = m.dict " +
                "WHERE d.title = ? AND m.path = ? LIMIT 1",
            arrayOf(dictionary, path),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val file = File(File(mediaDir, c.getLong(5).toString()), c.getString(4))
            DictionaryMedia(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)) { file.readBytes() }
        }

    override fun findKanjiMeta(character: String): List<TermMeta> =
        readableDatabase.rawQuery(
            "SELECT m.character, m.mode, m.data, d.title, d.position FROM kanji_meta m JOIN dictionaries d ON d.id = m.dict " +
                "WHERE m.character = ? AND d.enabled = 1 ORDER BY d.position",
            arrayOf(character),
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                TermMeta(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4))
            }
        }

    override fun findKanji(character: String): List<KanjiEntry> =
        readableDatabase.rawQuery(
            "SELECT k.character, k.onyomi, k.kunyomi, k.tags, k.meanings, d.title FROM kanji k " +
                "JOIN dictionaries d ON d.id = k.dict WHERE k.character = ? AND d.enabled = 1 ORDER BY d.position",
            arrayOf(character),
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                KanjiEntry(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4).split('\n').filter { it.isNotEmpty() }, c.getString(5),
                )
            }
        }

    companion object {
        private val BANK = Regex("(term_bank|term_meta_bank|kanji_bank|kanji_meta_bank|tag_bank)_(\\d+)\\.json")

        // Yomitan's words for telling ranks from occurrence counts (sort-frequency-dictionary-controller.js)
        private val MORE_COMMON = listOf("来る", "言う", "出る", "入る", "方", "男", "女", "今", "何", "時")
        private val LESS_COMMON = listOf("行なう", "論じる", "過す", "行方", "人口", "猫", "犬", "滝", "理", "暁")
        private const val MAX_ARGS = 900

        @Volatile
        private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also { instance = it }
            }
    }
}
