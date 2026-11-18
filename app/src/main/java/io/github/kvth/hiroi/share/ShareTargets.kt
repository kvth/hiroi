package io.github.kvth.hiroi.share

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.R
import io.github.kvth.hiroi.translation.TargetLanguage
import io.github.kvth.hiroi.translation.TranslationSettings
import org.json.JSONArray

/** The share targets: their list (in the "share" settings), sending text to one, and a dialog to edit them. */
object ShareTargets {
    const val PREFS = "share"

    fun load(context: Context): List<ShareTarget> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("targets", null)
            ?.let { runCatching { JSONArray(it).let { a -> List(a.length()) { i -> ShareTarget.fromJson(a.getJSONObject(i)) } } }.getOrNull() }
            ?: ShareTarget.DEFAULTS

    fun save(context: Context, targets: List<ShareTarget>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("targets", JSONArray(targets.map { it.toJson() }).toString()).apply()
    }

    /** Sends [text] to [target]: opens its link, or hands it to its app. */
    fun send(activity: Activity, target: ShareTarget, text: String) {
        val code = TranslationSettings(activity).target
        val language = TargetLanguage.of(code).name
        val intent = when (target.type) {
            "app" -> appIntent(target.action, target.message(text, language)).setComponent(ComponentName.unflattenFromString(target.url))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(target.link(text, code, language)))
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.share_target_missing, target.name), Toast.LENGTH_LONG).show()
        } catch (e: RuntimeException) {
            Toast.makeText(activity, activity.getString(R.string.share_target_missing, target.name), Toast.LENGTH_LONG).show()
        }
    }

    /** Android's share sheet for [text]. */
    fun shareSheet(activity: Activity, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, null))
    }

    private fun appIntent(action: String, text: String): Intent = when (action) {
        Intent.ACTION_PROCESS_TEXT -> Intent(action).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        else -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    }

    /** Edits the targets in a dialog; [onChanged] after each change. */
    fun edit(activity: Activity, onChanged: () -> Unit = {}) {
        val dp = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        fun small(text: String, enabled: Boolean = true, onClick: () -> Unit) = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            minWidth = 0
            minimumWidth = 0
            isEnabled = enabled
            setOnClickListener { onClick() }
        }
        lateinit var render: () -> Unit
        fun change(targets: List<ShareTarget>) {
            save(activity, targets)
            onChanged()
            render()
        }
        render = {
            list.removeAllViews()
            val targets = load(activity)
            if (targets.isEmpty()) list.addView(TextView(activity).apply { setText(R.string.share_no_targets) })
            targets.forEachIndexed { i, target ->
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    TextView(activity).apply {
                        text = "${i + 1}. ${target.name}"
                        setOnClickListener { ask(activity, target) { changed -> change(targets.toMutableList().apply { set(i, changed) }) } }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(small("▲", i > 0) { change(targets.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                row.addView(small("▼", i < targets.lastIndex) { change(targets.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                row.addView(small("✕") { change(targets.toMutableList().apply { removeAt(i) }) })
                list.addView(row)
            }
            list.addView(small(activity.getString(R.string.share_add_target)) { add(activity) { change(load(activity) + it) } })
        }
        render()
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_targets_title)
            .setMessage(R.string.share_targets_help)
            .setView(list)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** Picks a preset, an app, or a new URL. */
    private fun add(activity: Activity, onAdded: (ShareTarget) -> Unit) {
        val presets = ShareTarget.PRESETS
        val labels = presets.map { it.name } + activity.getString(R.string.share_add_app) + activity.getString(R.string.share_add_url)
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_add_target)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    in presets.indices -> onAdded(presets[which])
                    presets.size -> pickApp(activity) { ask(activity, it, onAdded) }
                    else -> ask(activity, ShareTarget("url", "", "https://example.com/?q={text}"), onAdded)
                }
            }
            .show()
    }

    /** Apps that take shared text or selected text ("process text"), to send to one directly. */
    private fun pickApp(activity: Activity, onPicked: (ShareTarget) -> Unit) {
        val apps = LinkedHashMap<ComponentName, Pair<String, String>>()
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            for (info in query(activity, appIntent(action, ""))) {
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                if (component.packageName == activity.packageName || component in apps) continue
                val label = info.loadLabel(activity.packageManager).toString()
                val app = info.activityInfo.applicationInfo.loadLabel(activity.packageManager).toString()
                apps[component] = action to if (label == app) app else "$app · $label"
            }
        }
        val entries = apps.entries.sortedBy { it.value.second.lowercase() }
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_add_app)
            .setItems(entries.map { it.value.second }.toTypedArray()) { _, which ->
                val (component, choice) = entries[which]
                onPicked(ShareTarget("app", choice.second, component.flattenToString(), ShareTarget.TEXT, choice.first))
            }
            .show()
    }

    /** Asks for a target's name, its URL (not for apps) and what to send. */
    private fun ask(activity: Activity, current: ShareTarget, onDone: (ShareTarget) -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, 0, pad, 0)
        }
        fun field(hint: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(activity).apply {
            setHint(hint)
            inputType = type
            setText(value)
        }
        val name = field(R.string.share_name_hint, current.name)
        val url = field(R.string.share_url_hint, current.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val template = field(R.string.share_template_hint, current.template, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { maxLines = 5 }
        form.addView(name)
        if (current.type == "url") form.addView(url)
        form.addView(template)
        AlertDialog.Builder(activity)
            .setTitle(current.name.ifEmpty { activity.getString(R.string.share_add_url) })
            .setMessage(if (current.type == "url") R.string.share_url_help else R.string.share_app_help)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = current.copy(
                    name = name.text.toString().trim().ifEmpty { current.name.ifEmpty { url.text.toString().trim() } },
                    url = if (current.type == "url") url.text.toString().trim() else current.url,
                    template = template.text.toString().ifBlank { ShareTarget.TEXT },
                )
                if (target.name.isNotEmpty() && target.url.isNotEmpty()) onDone(target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun query(activity: Activity, intent: Intent) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        activity.packageManager.queryIntentActivities(intent, 0)
    }
}
