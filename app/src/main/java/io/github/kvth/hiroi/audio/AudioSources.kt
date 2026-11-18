package io.github.kvth.hiroi.audio

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kvth.hiroi.R

/** The audio sources list as Yomitan's settings show it: names, and a dialog to order, add and remove them. */
object AudioSources {
    fun label(context: Context, source: AudioSource): String {
        val name = context.getString(
            when (source.type) {
                "jpod101" -> R.string.audio_jpod101
                "jisho" -> R.string.audio_jisho
                "custom" -> R.string.audio_custom
                "custom-json" -> R.string.audio_custom_json
                "text-to-speech" -> R.string.audio_tts
                else -> R.string.audio_tts_reading
            },
        )
        return if (source.url.isNotEmpty()) "$name: ${source.url}" else name
    }

    /** Edits [settings]' sources in a dialog; [onChanged] after each change. */
    fun edit(context: Context, settings: AudioSettings, onChanged: () -> Unit) {
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
        fun change(sources: List<AudioSource>) {
            settings.sources = sources
            onChanged()
            render()
        }
        render = {
            list.removeAllViews()
            val sources = settings.sources
            if (sources.isEmpty()) list.addView(TextView(context).apply { setText(R.string.audio_no_sources) })
            sources.forEachIndexed { i, source ->
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(context).apply { text = "${i + 1}. ${label(context, source)}" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(small("▲", i > 0) { change(sources.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                row.addView(small("▼", i < sources.lastIndex) { change(sources.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                row.addView(small("✕") { change(sources - source) })
                list.addView(row)
            }
            list.addView(small(context.getString(R.string.audio_add_source)) { add(context) { change(settings.sources + it) } })
        }
        render()
        AlertDialog.Builder(context)
            .setTitle(R.string.audio_sources_title)
            .setView(list)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** Picks a source type; custom ones also need their URL, with {term}, {reading} and {language}. */
    private fun add(context: Context, onAdded: (AudioSource) -> Unit) {
        val types = AudioSource.TYPES
        AlertDialog.Builder(context)
            .setTitle(R.string.audio_add_source)
            .setItems(types.map { label(context, AudioSource(it)) }.toTypedArray()) { _, which ->
                val type = types[which]
                if (type != "custom" && type != "custom-json") {
                    onAdded(AudioSource(type))
                    return@setItems
                }
                val url = EditText(context).apply {
                    setHint(if (type == "custom") R.string.audio_custom_hint else R.string.audio_custom_json_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                AlertDialog.Builder(context)
                    .setTitle(label(context, AudioSource(type)))
                    .setMessage(R.string.audio_custom_help)
                    .setView(url)
                    .setPositiveButton(android.R.string.ok) { _, _ -> url.text.toString().trim().takeIf { it.isNotEmpty() }?.let { onAdded(AudioSource(type, it)) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }
}
