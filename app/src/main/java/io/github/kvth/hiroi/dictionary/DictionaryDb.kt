package io.github.kvth.hiroi.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.zip.ZipFile

data class DictionaryInfo(val id: Long, val title: String, val revision: String, val terms: Int, val meta: Int, val kanji: Int)

/** Imported Yomitan dictionaries in SQLite. Priority is import order: the first imported comes first. */
class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "dictionaries.db", null, 2), DictionaryStore {

    init {
        setWriteAheadLoggingEnabled(true) // lookups keep working during an import
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dictionaries (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, revision TEXT, " +
                "terms INTEGER DEFAULT 0, meta INTEGER DEFAULT 0, kanji INTEGER DEFAULT 0, styles TEXT)",
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: the dictionary's styles.css, which Yomitan applies to its definitions
        if (oldVersion < 2) db.execSQL("ALTER TABLE dictionaries ADD COLUMN styles TEXT")
    }

    /** Each dictionary's styles.css by title, for showing its definitions as Yomitan does. */
    fun styles(): Map<String, String> =
        readableDatabase.rawQuery("SELECT title, styles FROM dictionaries WHERE styles IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun dictionaries(): List<DictionaryInfo> =
        readableDatabase.rawQuery("SELECT id, title, revision, terms, meta, kanji FROM dictionaries ORDER BY id", null).use { c ->
            List(c.count) {
                c.moveToNext()
                DictionaryInfo(c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5))
            }
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
                for ((n, bank) in banks.withIndex()) {
                    val (entry, match) = bank
                    onProgress("${index.title}: ${n + 1} / ${banks.size}")
                    val json = file.getInputStream(entry).reader().readText()
                    when (match.groupValues[1]) {
                        "term_bank" -> terms += insertTerms(db, id, Yomitan.parseTermBank(json, index.format))
                        "term_meta_bank" -> meta += insertMeta(db, id, Yomitan.parseTermMetaBank(json))
                        "kanji_bank" -> kanji += insertKanji(db, id, Yomitan.parseKanjiBank(json, index.format))
                    }
                }
                db.update(
                    "dictionaries",
                    ContentValues().apply {
                        put("terms", terms)
                        put("meta", meta)
                        put("kanji", kanji)
                    },
                    "id = ?", arrayOf(id.toString()),
                )
                return DictionaryInfo(id, index.title, index.revision, terms, meta, kanji)
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
            for (table in listOf("terms", "term_meta", "kanji")) db.delete(table, "dict = ?", arrayOf(id.toString()))
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

    private fun insertMeta(db: SQLiteDatabase, dict: Long, rows: List<TermMeta>): Int = inTransaction(db) {
        val insert = db.compileStatement("INSERT INTO term_meta (dict, expression, mode, data) VALUES (?, ?, ?, ?)")
        for (row in rows) {
            insert.bindLong(1, dict)
            insert.bindString(2, row.expression)
            insert.bindString(3, row.mode)
            insert.bindString(4, row.data)
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
        private val BANK = Regex("(term_bank|term_meta_bank|kanji_bank)_(\\d+)\\.json")
        private const val MAX_ARGS = 900

        @Volatile
        private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also { instance = it }
            }
    }
}
