package io.github.kvth.hiroi.audio

import android.content.Context
import io.github.kvth.hiroi.settings.preferences

fun AudioSettings(context: Context) = AudioSettings(context.preferences("audio"))

/** Word audio, with the local audio database where Hiroi keeps it ([AndroidLocalAudio]) */
fun AudioSettings.wordAudio(context: Context): WordAudio = wordAudio { AndroidLocalAudio.open(context) }
