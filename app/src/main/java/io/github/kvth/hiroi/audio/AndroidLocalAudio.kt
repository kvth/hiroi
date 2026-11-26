package io.github.kvth.hiroi.audio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** The local audio database [file], read with Android's SQLite; read-only */
class AndroidLocalAudio(file: File) : LocalAudioStore {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    init {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        if ("entries" !in tables || "android" !in tables) {
            db.close()
            throw IllegalArgumentException("Not a local audio database: it has no entries and android tables (Generate Android database makes one)")
        }
    }

    override fun recordings(term: String, readings: List<String>): List<LocalRecording> {
        val inReadings = if (readings.isEmpty()) "" else " OR reading IN (${readings.joinToString(",") { "?" }})"
        val sql = "SELECT source, speaker, display, file, reading FROM entries WHERE expression = ? AND (reading IS NULL$inReadings) ORDER BY id"
        return db.rawQuery(sql, arrayOf(term) + readings).use { c ->
            buildList { while (c.moveToNext()) add(LocalRecording(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) }
        }
    }

    override fun data(source: String, file: String): ByteArray? =
        db.rawQuery("SELECT data FROM android WHERE file = ? AND source = ? LIMIT 1", arrayOf(file, source)).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    override fun counts(): Map<String, Int> =
        db.rawQuery("SELECT source, count(*) FROM entries GROUP BY source", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    fun close() = db.close()

    companion object {
        /** Where Hiroi keeps the database: its own files folder, where one can also put it by hand (Android/data/…/files) */
        fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "android.db")

        @Volatile
        private var opened: Pair<String, AndroidLocalAudio>? = null

        /** The database, if there is one: opened once, and again when the file changes */
        fun open(context: Context): AndroidLocalAudio? {
            val file = file(context)
            val key = "${file.length()}:${file.lastModified()}"
            if (!file.isFile) return close().let { null }
            opened?.let { (k, db) -> if (k == key) return db }
            synchronized(this) {
                opened?.let { (k, db) -> if (k == key) return db }
                close()
                return runCatching { AndroidLocalAudio(file) }.getOrNull()?.also { opened = key to it }
            }
        }

        /** Closes the database, before it's replaced or removed */
        fun close() = synchronized(this) {
            opened?.second?.close()
            opened = null
        }
    }
}
