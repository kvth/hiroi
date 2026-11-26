package io.github.kvth.hiroi.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import java.io.File

@Volatile
private var instance: DictionaryDb? = null

/** The phone's dictionaries: their files in Hiroi's files/dictionaries, their list in files/dictionaries.json */
fun DictionaryDb.Companion.get(context: Context): DictionaryDb = instance ?: synchronized(DictionaryDb) {
    instance ?: run {
        val app = context.applicationContext
        val list = File(app.filesDir, "dictionaries.json")
        moveListOutOfSqlite(app, list)
        DictionaryDb(File(app.filesDir, "dictionaries"), list, ::rasterSize)
    }.also { instance = it }
}

/** A PNG's, JPEG's… width and height, from its header */
private fun rasterSize(bytes: ByteArray): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return if (options.outWidth > 0) options.outWidth to options.outHeight else null
}

/**
 * Hiroi kept the dictionary list in SQLite (dictionaries.db) before: this writes it to [list],
 * once, and deletes the database. Its versions before 10 had the dictionaries themselves in
 * SQLite; those weren't carried over then either, and need importing again.
 */
private fun moveListOutOfSqlite(context: Context, list: File) {
    val database = context.getDatabasePath("dictionaries.db")
    if (!database.isFile) return
    if (!list.exists()) {
        val dictionaries = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (db.version < 10) return@use emptyList()
            // 11 added forms
            val forms = if (db.version >= 11) "forms" else "NULL"
            db.rawQuery(
                "SELECT id, title, revision, terms, meta, kanji, frequencies, frequency_mode, enabled, images, $forms, file, styles, summary " +
                    "FROM dictionaries ORDER BY position, id",
                null,
            ).use { c ->
                List(c.count) {
                    c.moveToNext()
                    val info = DictionaryInfo(
                        c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getInt(5),
                        c.getInt(6), c.getString(7), c.getInt(8) != 0, c.getInt(9), if (c.isNull(10)) null else c.getInt(10),
                    )
                    DictionaryDb.Listed(info, c.getString(11), c.getString(12), c.getString(13))
                }
            }
        }
        DictionaryDb.writeList(list, dictionaries)
        File(context.filesDir, "dictionary-media").deleteRecursively()
    }
    context.deleteDatabase("dictionaries.db")
}
