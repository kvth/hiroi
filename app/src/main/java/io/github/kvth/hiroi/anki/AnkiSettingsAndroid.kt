package io.github.kvth.hiroi.anki

import android.content.Context
import io.github.kvth.hiroi.settings.preferences

/** The Anki settings on the phone, where Hiroi can also reach AnkiDroid directly */
fun AnkiSettings(context: Context): AnkiSettings {
    val app = context.applicationContext
    return AnkiSettings(app.preferences("anki")) { AnkiDroidApi(app) }
}
