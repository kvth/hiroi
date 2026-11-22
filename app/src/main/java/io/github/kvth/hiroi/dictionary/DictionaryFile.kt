package io.github.kvth.hiroi.dictionary

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * What a dictionary file says about its dictionary: its index.json ([index], verbatim, for its
 * update links), its styles.css, and how many rows of each kind it has.
 */
data class DictionaryFacts(
    val title: String,
    val revision: String,
    val frequencyMode: String?,
    val index: String?,
    val styles: String?,
    val terms: Int = 0,
    val meta: Int = 0,
    val kanji: Int = 0,
    val frequencies: Int = 0,
    val images: Int = 0,
)

/** Those of [forms] no longer than [longest] code points; [forms] itself if they all are. */
internal fun fitting(forms: Collection<String>, longest: Int): Collection<String> {
    fun fits(form: String) = form.length <= longest || form.codePointCount(0, form.length) <= longest
    return if (forms.all(::fits)) forms else forms.filter(::fits)
}

/**
 * One dictionary in its own SQLite file, as JL keeps them: deleting it is deleting the file, and
 * a collection is the files themselves ([DictionaryDb.exportCollection]).
 *
 * Lookups go through term_keys, one row per expression and reading of a term (a covering index
 * of its own), so finding the terms for a list of forms is one probe per form. The glossaries,
 * the big part of a row, are a table of their own: lookups read the terms' other columns, filter
 * them, and read the glossaries only of the results that are shown ([glossaries]).
 *
 * Made by [Writer]; [FORMAT] (user_version) changes when the tables do.
 */
class DictionaryFile private constructor(val db: SQLiteDatabase) : Closeable {

    /**
     * The longest expression or reading of a term, and of a term meta row, in code points, as
     * JL keeps it per dictionary: lookups leave out the forms longer than that, which can't match.
     */
    private val longest: Pair<Int, Int> by lazy {
        val info = db.rawQuery("SELECT key, value FROM info WHERE key IN ('longest_term_key', 'longest_meta_key')", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1).toIntOrNull()) }
        }
        (info["longest_term_key"] ?: Int.MAX_VALUE) to (info["longest_meta_key"] ?: Int.MAX_VALUE)
    }

    fun facts(): DictionaryFacts {
        val info = db.rawQuery("SELECT key, value FROM info", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        fun count(key: String) = info[key]?.toIntOrNull() ?: 0
        return DictionaryFacts(
            title = info["title"] ?: throw IllegalArgumentException("Not a Hiroi dictionary file (no title)"),
            revision = info["revision"].orEmpty(),
            frequencyMode = info["frequency_mode"],
            index = info["index"],
            styles = info["styles"],
            terms = count("terms"),
            meta = count("meta"),
            kanji = count("kanji"),
            frequencies = count("frequencies"),
            images = count("images"),
        )
    }

    /**
     * The terms whose expression or reading is one of [forms], without their glossaries;
     * [dictionary] and [priority] go into each.
     */
    fun findTerms(forms: Collection<String>, dictionary: String, priority: Int): List<TermEntry> {
        val out = ArrayList<TermEntry>()
        for (chunk in fitting(forms, longest.first).chunked(MAX_ARGS)) {
            out += terms("WHERE id IN (SELECT term FROM term_keys WHERE key IN (${placeholders(chunk.size)}))", chunk.toTypedArray(), dictionary, priority)
        }
        return out
    }

    fun findTermsBySequence(sequences: Collection<Long>, dictionary: String, priority: Int): List<TermEntry> {
        val out = ArrayList<TermEntry>()
        for (chunk in sequences.chunked(MAX_ARGS)) {
            out += terms("WHERE sequence IN (${placeholders(chunk.size)})", chunk.map { it.toString() }.toTypedArray(), dictionary, priority)
        }
        return out
    }

    /**
     * Up to [limit] terms with an expression or reading that starts with [affix] (ends with it
     * if [suffix]), each with the length of its shortest such form, those first.
     */
    fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int, dictionary: String, priority: Int): List<Pair<TermEntry, Int>> {
        val table = if (suffix) "term_keys_reverse" else "term_keys"
        val from = if (suffix) reversed(affix) else affix
        // Everything starting with [from] sorts from it to before it followed by the last code point.
        val until = from + "􏿿"
        return db.rawQuery(
            "SELECT $TERM_COLUMNS, m.n FROM terms JOIN " +
                "(SELECT term, min(length(key)) AS n FROM $table WHERE key >= ? AND key < ? GROUP BY term ORDER BY n LIMIT $limit) m " +
                "ON m.term = terms.id ORDER BY m.n",
            arrayOf(from, until),
        ).use { c -> List(c.count) { c.moveToNext(); term(c, dictionary, priority) to c.getInt(8) } }
    }

    private fun terms(where: String, args: Array<String>, dictionary: String, priority: Int): List<TermEntry> =
        db.rawQuery("SELECT $TERM_COLUMNS FROM terms $where", args).use { c ->
            List(c.count) { c.moveToNext(); term(c, dictionary, priority) }
        }

    private fun term(c: Cursor, dictionary: String, priority: Int) = TermEntry(
        expression = c.getString(1), reading = c.getString(2), definitionTags = c.getString(3), rules = c.getString(4),
        score = c.getInt(5), glossary = "", sequence = c.getLong(6), termTags = c.getString(7),
        dictionary = dictionary, priority = priority, id = c.getLong(0),
    )

    /** The glossaries of the terms [ids], by id. */
    fun glossaries(ids: Collection<Long>): Map<Long, String> {
        val out = HashMap<Long, String>(ids.size * 2)
        for (chunk in ids.chunked(MAX_ARGS)) {
            db.rawQuery("SELECT id, glossary FROM glossaries WHERE id IN (${chunk.joinToString(",")})", null).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
            }
        }
        return out
    }

    fun findTermMeta(expressions: Collection<String>, dictionary: String, priority: Int): List<TermMeta> {
        val out = ArrayList<TermMeta>()
        for (chunk in fitting(expressions, longest.second).chunked(MAX_ARGS)) {
            db.rawQuery("SELECT expression, mode, data FROM term_meta WHERE expression IN (${placeholders(chunk.size)})", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out += TermMeta(c.getString(0), c.getString(1), c.getString(2), dictionary, priority)
            }
        }
        return out
    }

    fun findKanji(character: String, dictionary: String): List<KanjiEntry> =
        db.rawQuery("SELECT character, onyomi, kunyomi, tags, meanings, stats FROM kanji WHERE character = ?", arrayOf(character)).use { c ->
            List(c.count) {
                c.moveToNext()
                KanjiEntry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4).split('\n').filter { it.isNotEmpty() }, dictionary, stats = c.getString(5))
            }
        }

    fun findKanjiMeta(character: String, dictionary: String, priority: Int): List<TermMeta> =
        db.rawQuery("SELECT character, mode, data FROM kanji_meta WHERE character = ?", arrayOf(character)).use { c ->
            List(c.count) { c.moveToNext(); TermMeta(c.getString(0), c.getString(1), c.getString(2), dictionary, priority) }
        }

    fun findTags(names: Collection<String>, dictionary: String): List<TagInfo> {
        if (names.isEmpty()) return emptyList()
        return db.rawQuery("SELECT name, category, ord, notes, score FROM tags WHERE name IN (${placeholders(names.size)})", names.toTypedArray()).use { c ->
            List(c.count) { c.moveToNext(); TagInfo(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4), dictionary) }
        }
    }

    /**
     * Image [path]; its bytes are read when asked for, through a file descriptor, since a cursor
     * can't hold a row over 2 MB.
     */
    fun findMedia(path: String): DictionaryMedia? =
        db.rawQuery("SELECT rowid, media_type, width, height FROM media WHERE path = ? LIMIT 1", arrayOf(path)).use { c ->
            if (!c.moveToFirst()) return null
            val row = c.getLong(0)
            DictionaryMedia(path, c.getString(1), c.getInt(2), c.getInt(3)) { mediaContent(row) }
        }

    private fun mediaContent(row: Long): ByteArray =
        db.compileStatement("SELECT content FROM media WHERE rowid = $row").use { statement ->
            statement.simpleQueryForBlobFileDescriptor()?.let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } } ?: ByteArray(0)
        }

    /** Whether suffix search is ready in this dictionary ([buildReverseKeys]) */
    fun hasReverseKeys(): Boolean =
        DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'term_keys_reverse'", null) > 0

    /**
     * Makes term_keys_reverse, the keys spelled backwards, for suffix search: on its first use
     * rather than on every import, so that it only takes space for those who search by suffix.
     */
    fun buildReverseKeys() {
        if (hasReverseKeys()) return
        // Gathered, then stored sorted: much faster than inserting them in random order.
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS reversed_keys (key TEXT NOT NULL, term INTEGER NOT NULL)")
        try {
            inTransaction(db) {
                db.execSQL("DELETE FROM reversed_keys")
                db.compileStatement("INSERT INTO reversed_keys (key, term) VALUES (?, ?)").use { insert ->
                    // In pages: a cursor over all of them would run the query again for every window it fills.
                    var after = arrayOf("", "0")
                    while (true) {
                        val last = db.rawQuery("SELECT key, term FROM term_keys WHERE (key, term) > (?, ?) ORDER BY key, term LIMIT $PAGE", after).use { c ->
                            var last: Array<String>? = null
                            while (c.moveToNext()) {
                                insert.bindString(1, reversed(c.getString(0)))
                                insert.bindLong(2, c.getLong(1))
                                insert.executeInsert()
                                if (c.isLast) last = arrayOf(c.getString(0), c.getLong(1).toString())
                            }
                            last
                        } ?: break
                        after = last
                    }
                }
                db.execSQL("CREATE TABLE term_keys_reverse (key TEXT NOT NULL, term INTEGER NOT NULL, PRIMARY KEY (key, term)) WITHOUT ROWID")
                db.execSQL("INSERT OR IGNORE INTO term_keys_reverse (key, term) SELECT key, term FROM reversed_keys ORDER BY key, term")
            }
        } finally {
            db.execSQL("DROP TABLE IF EXISTS reversed_keys")
        }
    }

    override fun close() = db.close()

    /**
     * Writes a new dictionary file at [file] as fast as SQLite allows: no journal (a failed import
     * deletes the file), no syncing, the indexes made at the end ([finish]), and the term keys
     * gathered in a temporary table and stored sorted, which packs their pages full.
     */
    class Writer(private val file: File) : Closeable {
        private val db: SQLiteDatabase
        private var nextTerm = 1L
        private var terms = 0
        private var meta = 0
        private var kanji = 0
        private var frequencies = 0
        private var images = 0
        private var finished = false

        private val insertTerm: SQLiteStatement
        private val insertGlossary: SQLiteStatement
        private val insertKey: SQLiteStatement

        init {
            file.delete()
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                for (pragma in listOf("journal_mode = OFF", "synchronous = OFF", "locking_mode = EXCLUSIVE", "cache_size = -32768", "temp_store = MEMORY")) {
                    db.rawQuery("PRAGMA $pragma", null).use { it.moveToFirst() }
                }
                for (sql in SCHEMA) db.execSQL(sql)
                db.execSQL("CREATE TEMP TABLE new_keys (key TEXT NOT NULL, term INTEGER NOT NULL)")
                insertTerm = db.compileStatement("INSERT INTO terms (id, expression, reading, definition_tags, rules, score, sequence, term_tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                insertGlossary = db.compileStatement("INSERT INTO glossaries (id, glossary) VALUES (?, ?)")
                insertKey = db.compileStatement("INSERT INTO new_keys (key, term) VALUES (?, ?)")
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }

        fun terms(rows: List<TermEntry>) = inTransaction(db) {
            for (row in rows) {
                val id = nextTerm++
                insertTerm.bindLong(1, id)
                insertTerm.bindString(2, row.expression)
                insertTerm.bindString(3, row.reading)
                insertTerm.bindString(4, row.definitionTags)
                insertTerm.bindString(5, row.rules)
                insertTerm.bindLong(6, row.score.toLong())
                insertTerm.bindLong(7, row.sequence)
                insertTerm.bindString(8, row.termTags)
                insertTerm.executeInsert()
                insertGlossary.bindLong(1, id)
                insertGlossary.bindString(2, row.glossary)
                insertGlossary.executeInsert()
                key(row.expression, id)
                if (row.reading != row.expression) key(row.reading, id)
            }
            terms += rows.size
        }

        private fun key(key: String, term: Long) {
            insertKey.bindString(1, key)
            insertKey.bindLong(2, term)
            insertKey.executeInsert()
        }

        /** Term meta rows, or kanji meta rows if [ofKanji] ([TermMeta.expression] is the character). */
        fun meta(rows: List<TermMeta>, ofKanji: Boolean) = inTransaction(db) {
            val insert = db.compileStatement("INSERT INTO ${if (ofKanji) "kanji_meta (character" else "term_meta (expression"}, mode, data) VALUES (?, ?, ?)")
            for (row in rows) {
                insert.bindString(1, row.expression)
                insert.bindString(2, row.mode)
                insert.bindString(3, row.data)
                insert.executeInsert()
                if (row.mode == "freq") frequencies++
            }
            insert.close()
            meta += rows.size
        }

        fun kanji(rows: List<KanjiEntry>) = inTransaction(db) {
            val insert = db.compileStatement("INSERT INTO kanji (character, onyomi, kunyomi, tags, meanings, stats) VALUES (?, ?, ?, ?, ?, ?)")
            for (row in rows) {
                insert.bindString(1, row.character)
                insert.bindString(2, row.onyomi)
                insert.bindString(3, row.kunyomi)
                insert.bindString(4, row.tags)
                insert.bindString(5, row.meanings.joinToString("\n"))
                insert.bindString(6, row.stats)
                insert.executeInsert()
            }
            insert.close()
            kanji += rows.size
        }

        fun tags(rows: List<TagInfo>) = inTransaction(db) {
            val insert = db.compileStatement("INSERT INTO tags (name, category, ord, notes, score) VALUES (?, ?, ?, ?, ?)")
            for (row in rows) {
                insert.bindString(1, row.name)
                insert.bindString(2, row.category)
                insert.bindLong(3, row.order.toLong())
                insert.bindString(4, row.notes)
                insert.bindLong(5, row.score.toLong())
                insert.executeInsert()
            }
            insert.close()
        }

        /**
         * Stores image [path] and returns its size: [knownSize] if given, else from the image
         * itself, or for an SVG from its attributes (100 × 100 when it has none, Yomitan's default).
         */
        fun media(path: String, bytes: ByteArray, mediaType: String? = null, knownSize: Pair<Int, Int>? = null): Pair<Int, Int> {
            val type = mediaType ?: DictionaryImages.mediaType(path) ?: "application/octet-stream"
            val size = knownSize ?: if (type == "image/svg+xml") {
                DictionaryImages.svgSize(String(bytes)) ?: (100 to 100)
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth > 0) options.outWidth to options.outHeight else 100 to 100
            }
            db.compileStatement("INSERT INTO media (path, media_type, width, height, content) VALUES (?, ?, ?, ?, ?)").use { insert ->
                insert.bindString(1, path)
                insert.bindString(2, type)
                insert.bindLong(3, size.first.toLong())
                insert.bindLong(4, size.second.toLong())
                insert.bindBlob(5, bytes)
                insert.executeInsert()
            }
            images++
            return size
        }

        /**
         * Stores the term keys sorted, makes the indexes and what the file says about the
         * dictionary ([facts], with the counts of what was written), and closes the file.
         * Returns the facts as stored. [reverseKeys]: make suffix search's keys too.
         */
        fun finish(facts: DictionaryFacts, reverseKeys: Boolean): DictionaryFacts {
            val stored = facts.copy(terms = terms, meta = meta, kanji = kanji, frequencies = frequencies, images = images)
            insertTerm.close()
            insertGlossary.close()
            insertKey.close()
            inTransaction(db) {
                db.execSQL("INSERT OR IGNORE INTO term_keys (key, term) SELECT key, term FROM new_keys ORDER BY key, term")
                db.execSQL("DROP TABLE new_keys")
                for (sql in INDEXES) db.execSQL(sql)
                val insert = db.compileStatement("INSERT INTO info (key, value) VALUES (?, ?)")
                for ((key, value) in info(stored) + longestKeys(db)) {
                    insert.bindString(1, key)
                    insert.bindString(2, value)
                    insert.executeInsert()
                }
                insert.close()
            }
            DictionaryFile(db).also { if (reverseKeys && terms > 0) it.buildReverseKeys() }
            db.version = FORMAT
            db.close()
            finished = true
            return stored
        }

        /** Closes the file; unless [finish]ed, deletes it. */
        override fun close() {
            if (finished) return
            if (db.isOpen) db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    companion object {
        /** The version of the tables, the file's user_version */
        const val FORMAT = 2

        private const val MAX_ARGS = 900

        /** Rows read at a time when reading a whole table */
        private const val PAGE = 5000
        private const val TERM_COLUMNS = "id, expression, reading, definition_tags, rules, score, sequence, term_tags"

        private val SCHEMA = listOf(
            "CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID",
            "CREATE TABLE terms (id INTEGER PRIMARY KEY, expression TEXT NOT NULL, reading TEXT NOT NULL, definition_tags TEXT NOT NULL, " +
                "rules TEXT NOT NULL, score INTEGER NOT NULL, sequence INTEGER NOT NULL, term_tags TEXT NOT NULL)",
            "CREATE TABLE glossaries (id INTEGER PRIMARY KEY, glossary TEXT NOT NULL)",
            "CREATE TABLE term_keys (key TEXT NOT NULL, term INTEGER NOT NULL, PRIMARY KEY (key, term)) WITHOUT ROWID",
            "CREATE TABLE term_meta (expression TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)",
            "CREATE TABLE kanji (character TEXT NOT NULL, onyomi TEXT NOT NULL, kunyomi TEXT NOT NULL, tags TEXT NOT NULL, meanings TEXT NOT NULL, stats TEXT NOT NULL)",
            "CREATE TABLE kanji_meta (character TEXT NOT NULL, mode TEXT NOT NULL, data TEXT NOT NULL)",
            "CREATE TABLE tags (name TEXT NOT NULL, category TEXT NOT NULL, ord INTEGER NOT NULL, notes TEXT NOT NULL, score INTEGER NOT NULL)",
            "CREATE TABLE media (path TEXT NOT NULL, media_type TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, content BLOB NOT NULL)",
        )

        /** Made once the rows are in, which is much faster than keeping them up to date row by row */
        private val INDEXES = listOf(
            "CREATE INDEX terms_sequence ON terms (sequence)",
            "CREATE INDEX term_meta_expression ON term_meta (expression)",
            "CREATE INDEX kanji_character ON kanji (character)",
            "CREATE INDEX kanji_meta_character ON kanji_meta (character)",
            "CREATE INDEX tags_name ON tags (name)",
            "CREATE INDEX media_path ON media (path)",
        )

        /** What [longest] reads, from the keys and term meta rows in [db]. */
        private fun longestKeys(db: SQLiteDatabase): Map<String, String> = mapOf(
            "longest_term_key" to DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(length(key)), 0) FROM term_keys", null).toString(),
            "longest_meta_key" to DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(length(expression)), 0) FROM term_meta", null).toString(),
        )

        /**
         * Brings a file of an older [FORMAT] up to date. 1 → 2: kanji stats (none, in files of
         * before: the dictionary needs importing again to get them) and [longest].
         */
        private fun upgrade(db: SQLiteDatabase) {
            if (db.version >= FORMAT) return
            inTransaction(db) {
                if (db.version < 2) {
                    db.execSQL("ALTER TABLE kanji ADD COLUMN stats TEXT NOT NULL DEFAULT '{}'")
                    for ((key, value) in longestKeys(db)) db.execSQL("INSERT OR REPLACE INTO info (key, value) VALUES (?, ?)", arrayOf(key, value))
                }
                db.version = FORMAT
            }
        }

        private fun info(facts: DictionaryFacts): Map<String, String> = buildMap {
            put("title", facts.title)
            put("revision", facts.revision)
            facts.frequencyMode?.let { put("frequency_mode", it) }
            facts.index?.let { put("index", it) }
            facts.styles?.let { put("styles", it) }
            put("terms", facts.terms.toString())
            put("meta", facts.meta.toString())
            put("kanji", facts.kanji.toString())
            put("frequencies", facts.frequencies.toString())
            put("images", facts.images.toString())
        }

        /**
         * Opens the dictionary file [file] for lookups. Throws if it isn't one, or one of a
         * [FORMAT] this Hiroi doesn't know.
         */
        fun open(file: File): DictionaryFile {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                when {
                    db.version > FORMAT -> throw IllegalArgumentException("${file.name} is from a newer Hiroi (format ${db.version})")
                    db.version < 1 -> throw IllegalArgumentException("${file.name} is not a Hiroi dictionary file")
                }
                upgrade(db)
            } catch (e: Exception) {
                db.close()
                throw e
            }
            return DictionaryFile(db)
        }

        /** [text] backwards, by code point (StringBuilder keeps surrogate pairs together). */
        fun reversed(text: String): String = StringBuilder(text).reverse().toString()

        private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

        internal inline fun <T> inTransaction(db: SQLiteDatabase, block: () -> T): T {
            db.beginTransaction()
            try {
                return block().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }
    }
}
