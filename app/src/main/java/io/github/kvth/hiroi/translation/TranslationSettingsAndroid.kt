package io.github.kvth.hiroi.translation

import android.content.ComponentName
import android.content.Context
import io.github.kvth.hiroi.settings.preferences

fun TranslationSettings(context: Context) = TranslationSettings(context.preferences(TranslationSettings.PREFS))

/** The translation app chosen for "app": the intent action it takes, and its activity */
var TranslationSettings.app: Pair<String, ComponentName>?
    get() = appComponent?.let { (action, component) -> ComponentName.unflattenFromString(component)?.let { action to it } }
    set(value) {
        appComponent = value?.let { (action, component) -> action to component.flattenToString() }
    }
