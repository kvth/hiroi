package io.github.kvth.hiroi.translation

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.kvth.hiroi.R
import java.util.concurrent.Executors

/**
 * A line under the text being read that translates it: "文A Translate" until tapped (or right
 * away, with automatic translation), then the translation. What it translates comes from
 * [source] at that moment (null hides the strip). Long-press: other services, a translation
 * app, copying the translation.
 */
class TranslationStrip(private val activity: Activity, private val source: () -> String?) {
    private val dp = activity.resources.displayMetrics.density
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val primary = themeColor(android.R.attr.textColorPrimary)
    private val secondary = themeColor(android.R.attr.textColorSecondary)
    private var generation = 0

    /** The text the strip shows a translation (or its progress) for; null while it offers one. */
    private var shownFor: String? = null
    private var translation: String? = null
    private val autoTranslate = Runnable { source()?.let { translate(it, auto = true) } }

    val view: TextView = TextView(activity).apply {
        textSize = 16f
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        setOnClickListener { source()?.let { text -> if (text != shownFor || translation == null) translate(text, auto = false) } }
        setOnLongClickListener { menu(); true }
        visibility = View.GONE
    }

    /**
     * The text to translate may have changed: offers to translate it, or translates it after a
     * moment with automatic translation (a lookup that follows a tap may change it again). A
     * translation of the same text stays.
     */
    fun refresh() {
        main.removeCallbacks(autoTranslate)
        val text = source()
        if (text.isNullOrBlank()) {
            generation++
            shownFor = null
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        if (text == shownFor) return
        generation++
        shownFor = null
        translation = null
        show(activity.getString(R.string.translation_offer), secondary)
        if (TranslationSettings(activity).auto) main.postDelayed(autoTranslate, AUTO_DELAY_MS)
    }

    /** Translates [text] with the services in their order; a tap may send it to the app first in the list. */
    private fun translate(text: String, auto: Boolean, services: List<TranslationService>? = null) {
        main.removeCallbacks(autoTranslate)
        val settings = TranslationSettings(activity)
        val list = services ?: settings.services
        if (!auto && list.firstOrNull()?.type == "app") {
            Translations.sendToApp(activity, text)
            return
        }
        if (list.none { it.inPlace }) {
            if (!auto) show(activity.getString(R.string.translation_no_services), secondary)
            return
        }
        val generation = ++generation
        shownFor = text
        translation = null
        show(activity.getString(R.string.translation_translating), secondary)
        worker.execute {
            val result = runCatching {
                Translations(activity).translate(text, list) {
                    activity.runOnUiThread { if (generation == this.generation) show(activity.getString(R.string.translation_downloading), secondary) }
                }
            }
            activity.runOnUiThread {
                if (generation != this.generation || activity.isDestroyed) return@runOnUiThread
                result.fold(
                    { translated ->
                        translation = translated.text
                        show(translated.text, primary)
                        view.contentDescription = activity.getString(R.string.translation_by, translated.text, TranslationServices.label(activity, translated.service, withDetail = false))
                    },
                    { error ->
                        // Tapping again tries again.
                        translation = null
                        show(activity.getString(R.string.translation_failed, error.message ?: error.javaClass.simpleName), secondary)
                    },
                )
            }
        }
    }

    private fun menu() {
        val text = source() ?: return
        val services = TranslationSettings(activity).services.filter { it.inPlace }.distinct()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        for (service in services) {
            items += activity.getString(R.string.translation_with, TranslationServices.label(activity, service, withDetail = false)) to {
                translate(text, auto = false, services = listOf(service))
            }
        }
        items += activity.getString(R.string.translation_send_to_app) to { Translations.sendToApp(activity, text, choose = true) }
        translation?.let { translated -> items += activity.getString(R.string.translation_copy) to { copy(translated) } }
        AlertDialog.Builder(activity)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun copy(text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), text))
    }

    private fun show(text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        view.contentDescription = null
    }

    fun close() {
        main.removeCallbacks(autoTranslate)
        worker.shutdownNow()
    }

    private fun themeColor(attr: Int): Int {
        val attrs = activity.obtainStyledAttributes(intArrayOf(attr))
        try {
            return attrs.getColor(0, Color.GRAY)
        } finally {
            attrs.recycle()
        }
    }

    private companion object {
        const val AUTO_DELAY_MS = 600L
    }
}
