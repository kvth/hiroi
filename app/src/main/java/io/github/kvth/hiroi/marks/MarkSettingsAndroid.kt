package io.github.kvth.hiroi.marks

import android.content.Context
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.settings.preferences

fun MarkSettings(context: Context) = MarkSettings(context.preferences("marks"), AnkiSettings(context))
