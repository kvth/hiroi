package io.github.kvth.hiroi.translation

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import io.github.kvth.hiroi.R

/**
 * Translates Japanese with the services set up, in their order, until one has a translation.
 * Translations are kept for a while, so tapping a sentence again (or a card made from it) asks
 * no service again. Blocks; call it off the main thread.
 */
class Translations(private val context: Context, private val online: OnlineTranslation = OnlineTranslation()) {
    private val settings = TranslationSettings(context)

    class Translated(val text: String, val service: TranslationService)

    /**
     * [text] in [target] from the first of [services] that has a translation ("app" doesn't
     * count); [onDownload] is called before ML Kit downloads its models. Throws
     * [TranslationException] with each service's reason when none has one.
     */
    fun translate(
        text: String,
        services: List<TranslationService> = settings.services,
        target: String = settings.target,
        onDownload: () -> Unit = {},
    ): Translated {
        val errors = mutableListOf<String>()
        for (service in services.filter { it.inPlace }) {
            val key = listOf(service.type, service.url, target, text).joinToString("\u0000")
            synchronized(cache) { cache[key] }?.let { return Translated(it, service) }
            try {
                val translation = when (service.type) {
                    "mlkit" -> {
                        if (!OnDeviceTranslation.isReady(target)) onDownload()
                        OnDeviceTranslation.translate(text, target)
                    }
                    else -> online.translate(service, text, target)
                }
                synchronized(cache) { cache[key] = translation }
                return Translated(translation, service)
            } catch (e: Exception) {
                Log.w(TAG, "${service.type} couldn't translate", e)
                errors += "${TranslationServices.label(context, service, withDetail = false)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw TranslationException(errors.joinToString("\n").ifEmpty { context.getString(R.string.translation_no_services) })
    }

    companion object {
        private const val TAG = "HiroiTranslation"
        private const val CACHE_SIZE = 200

        /** Translations by service, language and text, the least recently used dropped first */
        private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
        }

        /**
         * Sends [text] to the translation app chosen before, or asks which one when [choose] is
         * set or none is (apps that take Android's "translate" intent or translate selected text).
         */
        fun sendToApp(activity: Activity, text: String, choose: Boolean = false) {
            val settings = TranslationSettings(activity)
            val saved = settings.app
            if (!choose && saved != null) {
                try {
                    activity.startActivity(intent(saved.first, text).setComponent(saved.second))
                    return
                } catch (e: ActivityNotFoundException) {
                    settings.app = null
                }
            }
            val apps = LinkedHashMap<ComponentName, Pair<String, CharSequence>>()
            for (action in listOf(Intent.ACTION_TRANSLATE, Intent.ACTION_PROCESS_TEXT)) {
                for (info in query(activity, intent(action, text))) {
                    val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                    if (component.packageName == activity.packageName || component in apps) continue
                    val label = info.loadLabel(activity.packageManager).toString()
                    val app = info.activityInfo.applicationInfo.loadLabel(activity.packageManager).toString()
                    apps[component] = action to if (label == app) app else "$label · $app"
                }
            }
            if (apps.isEmpty()) {
                Toast.makeText(activity, R.string.translation_no_app, Toast.LENGTH_LONG).show()
                return
            }
            val entries = apps.entries.toList()
            AlertDialog.Builder(activity)
                .setTitle(R.string.translation_choose_app)
                .setItems(entries.map { it.value.second }.toTypedArray()) { _, which ->
                    val (component, choice) = entries[which]
                    settings.app = choice.first to component
                    activity.startActivity(intent(choice.first, text).setComponent(component))
                }
                .show()
        }

        private fun intent(action: String, text: String): Intent = when (action) {
            Intent.ACTION_TRANSLATE -> Intent(action).putExtra(Intent.EXTRA_TEXT, text)
            else -> Intent(action).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        private fun query(activity: Activity, intent: Intent) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.queryIntentActivities(intent, 0)
        }
    }
}
