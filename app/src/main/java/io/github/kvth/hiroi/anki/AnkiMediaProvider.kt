package io.github.kvth.hiroi.anki

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Lends AnkiDroid the media files [AnkiDroidApi] stores, by content URI: AnkiDroid only takes
 * media that way. Not exported; AnkiDroid gets read access to one file at a time, while it
 * copies it. A tiny FileProvider, as Hiroi doesn't use androidx.
 */
class AnkiMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = file(context!!, uri.lastPathSegment ?: throw FileNotFoundException(uri.toString()))
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** The directory the lent files are in, and the file for [name] (only directly in it). */
        fun dir(context: Context) = File(context.cacheDir, "anki-media")

        fun file(context: Context, name: String) = File(dir(context), File(name).name)

        fun uri(context: Context, name: String): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.ankimedia")
            .appendPath(File(name).name)
            .build()
    }
}
