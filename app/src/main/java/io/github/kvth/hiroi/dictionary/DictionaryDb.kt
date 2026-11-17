package io.github.kvth.hiroi.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
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
)

/** Imported Yomitan dictionaries in SQLite. Priority is import order: the first imported comes first. */
class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "dictionaries.db", null, 4), DictionaryStore {

    init {
        setWriteAheadLoggingEnabled(true) // lookups keep working during an import
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dictionaries (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT, " +
                "terms INTEGER DEFAULT 0, meta INTEGER DEFAULT 0, kanji INTEGER DEFAULT 0, styles TEXT, " +
                "frequency_mode TEXT, frequencies INTEGER DEFAULT 0)",
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
    }

    /** Each dictionary's styles.css by title, for showing its definitions as Yomitan does. */
    fun styles(): Map<String, String> =
        readableDatabase.rawQuery("SELECT title, styles FROM dictionaries WHERE styles IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun dictionaries(): List<DictionaryInfo> =
        readableDatabase.rawQuery(
            "SELECT id, title, revision, terms, meta, kanji, frequencies, frequency_mode FROM dictionaries ORDER BY id", null,
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                DictionaryInfo(
                    c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5),
                    c.getInt(6), c.getString(7),
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

    /** Imports a Yomitan dictionary zip; [onProgress] gets short status lines. A failed import leaves nothing behind. */
    fun import(zip: File, onProgress: (String) -> Unit): DictionaryInfo {
        ZipFile(zip).use { file ->
            val indexEntry = file.getEntry("index.json")
                ?: throw IllegalArgumentException("Not a Yomitan dictionary (no index.json)")
            val index = Yomitan.parseIndex(file.getInputStream(indexEntry).reader().readText())
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
                    file.getEntry("styles.css")?.let { put("styles", file.getInputStream(it).reader().readText()) }
                },
            )
            try {
                val banks = file.entries().asSequence()
                    .mapNotNull { entry -> BANK.matchEntire(entry.name)?.let { entry to it } }
                    .sortedWith(compareBy({ it.second.groupValues[1] }, { it.second.groupValues[2].toInt() }))
                    .toList()
                var terms = 0
                var meta = 0
                var kanji = 0
                var frequencies = 0
                for ((n, bank) in banks.withIndex()) {
                    val (entry, match) = bank
                    onProgress("${index.title}: ${n + 1} / ${banks.size}")
                    val json = file.getInputStream(entry).reader().readText()
                    when (match.groupValues[1]) {
                        "term_bank" -> terms += insertTerms(db, id, Yomitan.parseTermBank(json, index.format))
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

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("terms", "term_meta", "kanji", "kanji_meta", "tags")) db.delete(table, "dict = ?", arrayOf(id.toString()))
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
                    "d.title, d.id FROM terms t JOIN dictionaries d ON d.id = t.dict " +
                    "WHERE t.expression IN ($placeholders) OR t.reading IN ($placeholders)",
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

    override fun findTermMeta(expressions: Collection<String>): List<TermMeta> {
        val out = mutableListOf<TermMeta>()
        for (chunk in expressions.chunked(MAX_ARGS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT m.expression, m.mode, m.data, d.title, d.id FROM term_meta m JOIN dictionaries d ON d.id = m.dict " +
                    "WHERE m.expression IN ($placeholders)",
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

    override fun findKanjiMeta(character: String): List<TermMeta> =
        readableDatabase.rawQuery(
            "SELECT m.character, m.mode, m.data, d.title, d.id FROM kanji_meta m JOIN dictionaries d ON d.id = m.dict " +
                "WHERE m.character = ? ORDER BY d.id",
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
                "JOIN dictionaries d ON d.id = k.dict WHERE k.character = ? ORDER BY d.id",
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
