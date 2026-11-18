package io.github.kvth.hiroi.share

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.R

/**
 * The panel's Share menu: which text (the word, its sentence, the box, the region, the
 * selection) and where it goes: the clipboard, Android's share sheet, or a share target.
 */
object ShareMenu {
    /** A text the menu offers, e.g. "Sentence" and the sentence */
    class Scope(val label: String, val text: String)

    fun show(activity: Activity, scopes: List<Scope>, chosen: Int) {
        val offered = scopes.filter { it.text.isNotBlank() }.distinctBy { it.text }
        if (offered.isEmpty()) return
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        var scope = offered[scopes.getOrNull(chosen)?.let { c -> offered.indexOfFirst { it.text == c.text } }?.takeIf { it >= 0 } ?: 0]

        val preview = TextView(activity).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textSize = 16f
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            text = scope.text
        }
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            offered.forEachIndexed { i, s ->
                addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = s.label
                    isChecked = s === scope
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            scope = offered[i]
                            preview.text = scope.text
                        }
                    }
                })
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(group) })
            addView(preview)
        }
        val primary = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).let { attrs ->
            try { attrs.getColor(0, preview.currentTextColor) } finally { attrs.recycle() }
        }
        lateinit var dialog: AlertDialog
        fun action(label: String, onClick: (String) -> Unit) {
            content.addView(TextView(activity).apply {
                text = label
                textSize = 17f
                setTextColor(primary)
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                val ripple = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    dialog.dismiss()
                    onClick(scope.text)
                }
            })
        }
        action(activity.getString(R.string.copy)) { copy(activity, it) }
        action(activity.getString(R.string.share_sheet)) { ShareTargets.shareSheet(activity, it) }
        for (target in ShareTargets.load(activity)) action(activity.getString(R.string.share_to, target.name)) { ShareTargets.send(activity, target, it) }
        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setNeutralButton(R.string.share_edit_targets) { _, _ -> ShareTargets.edit(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    fun copy(activity: Activity, text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.app_name), text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
