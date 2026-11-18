package io.github.kvth.hiroi.translation

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kvth.hiroi.R

/** The translation services list: names, and a dialog to order, add, change and remove them. */
object TranslationServices {
    /** The service's name; [withDetail] adds its server or URL (never its key). */
    fun label(context: Context, service: TranslationService, withDetail: Boolean = true): String {
        val name = context.getString(
            when (service.type) {
                "mlkit" -> R.string.translation_mlkit
                "deepl" -> R.string.translation_deepl
                "libretranslate" -> R.string.translation_libretranslate
                "custom" -> R.string.translation_custom
                else -> R.string.translation_app
            },
        )
        return if (withDetail && service.url.isNotEmpty()) "$name: ${service.url}" else name
    }

    /** Edits [settings]' services in a dialog; [onChanged] after each change. */
    fun edit(context: Context, settings: TranslationSettings, onChanged: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        fun small(text: String, enabled: Boolean = true, onClick: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            minWidth = 0
            minimumWidth = 0
            isEnabled = enabled
            setOnClickListener { onClick() }
        }
        lateinit var render: () -> Unit
        fun change(services: List<TranslationService>) {
            settings.services = services
            onChanged()
            render()
        }
        render = {
            list.removeAllViews()
            val services = settings.services
            if (services.isEmpty()) list.addView(TextView(context).apply { setText(R.string.translation_no_services) })
            services.forEachIndexed { i, service ->
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    TextView(context).apply {
                        text = "${i + 1}. ${label(context, service)}"
                        // Services with a key or address can be changed
                        if (service.type in EDITABLE) setOnClickListener { ask(context, service.type, service) { changed -> change(services.toMutableList().apply { set(i, changed) }) } }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(small("▲", i > 0) { change(services.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                row.addView(small("▼", i < services.lastIndex) { change(services.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                row.addView(small("✕") { change(services.toMutableList().apply { removeAt(i) }) })
                list.addView(row)
            }
            list.addView(small(context.getString(R.string.translation_add_service)) { add(context) { change(settings.services + it) } })
        }
        render()
        AlertDialog.Builder(context)
            .setTitle(R.string.translation_services_title)
            .setMessage(R.string.translation_services_help)
            .setView(list)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private val EDITABLE = setOf("deepl", "libretranslate", "custom")

    private fun add(context: Context, onAdded: (TranslationService) -> Unit) {
        val types = TranslationService.TYPES
        AlertDialog.Builder(context)
            .setTitle(R.string.translation_add_service)
            .setItems(types.map { label(context, TranslationService(it)) }.toTypedArray()) { _, which ->
                val type = types[which]
                if (type in EDITABLE) ask(context, type, null, onAdded) else onAdded(TranslationService(type))
            }
            .show()
    }

    /** Asks for what a [type] of service needs: DeepL its key, LibreTranslate its server (and key), a custom one its URL. */
    private fun ask(context: Context, type: String, current: TranslationService?, onDone: (TranslationService) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val url = EditText(context).apply {
            setHint(if (type == "custom") R.string.translation_custom_hint else R.string.translation_libretranslate_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(current?.url ?: if (type == "libretranslate") "https://libretranslate.com" else "")
        }
        val key = EditText(context).apply {
            setHint(if (type == "deepl") R.string.translation_deepl_key_hint else R.string.translation_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(current?.key.orEmpty())
        }
        if (type != "deepl") form.addView(url)
        if (type != "custom") form.addView(key)
        AlertDialog.Builder(context)
            .setTitle(label(context, TranslationService(type)))
            .setMessage(
                when (type) {
                    "deepl" -> R.string.translation_deepl_help
                    "libretranslate" -> R.string.translation_libretranslate_help
                    else -> R.string.translation_custom_help
                },
            )
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val service = TranslationService(type, if (type == "deepl") "" else url.text.toString().trim(), if (type == "custom") "" else key.text.toString().trim())
                val complete = when (type) {
                    "deepl" -> service.key.isNotEmpty()
                    else -> service.url.isNotEmpty()
                }
                if (complete) onDone(service)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
