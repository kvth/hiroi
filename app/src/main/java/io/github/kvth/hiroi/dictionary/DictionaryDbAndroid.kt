package io.github.kvth.hiroi.dictionary

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

@Volatile
private var instance: DictionaryDb? = null

/** The phone's dictionaries: their files in Hiroi's files/dictionaries, their list in files/dictionaries.json */
fun DictionaryDb.Companion.get(context: Context): DictionaryDb = instance ?: synchronized(DictionaryDb) {
    instance ?: run {
        val app = context.applicationContext
        DictionaryDb(File(app.filesDir, "dictionaries"), File(app.filesDir, "dictionaries.json"), ::rasterSize)
    }.also { instance = it }
}

/** A PNG's, JPEG's… width and height, from its header */
private fun rasterSize(bytes: ByteArray): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return if (options.outWidth > 0) options.outWidth to options.outHeight else null
}
