package io.github.kvth.hiroi.dictionary

import android.content.Context
import io.github.kvth.hiroi.settings.preferences

fun DictionarySettings(context: Context) = DictionarySettings(context.preferences("dictionaries"))
