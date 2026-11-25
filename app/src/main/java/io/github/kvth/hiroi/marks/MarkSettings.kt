package io.github.kvth.hiroi.marks

import android.content.Context
import android.content.SharedPreferences
import io.github.kvth.hiroi.anki.AnkiBackend
import io.github.kvth.hiroi.anki.AnkiSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A rule: an Anki search plus a colour. A word gets the colour of the first enabled rule (top to
 * bottom) that one of its notes matches. [color] is "#rrggbb".
 */
data class MarkRule(val id: String, val name: String, val query: String, val color: String, val enabled: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("query", query).put("color", color).put("enabled", enabled)

    val argb get() = parseColor(color)

    companion object {
        fun newId() = "r" + (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

        fun isColor(c: String) = Regex("^#[0-9a-fA-F]{6}$").matches(c)

        fun parseColor(c: String): Int = if (isColor(c)) (0xFF000000 or c.drop(1).toLong(16)).toInt() else 0xFF888888.toInt()
    }
}

/**
 * Marking the words you have in Anki, like AnkiMark (github: ankimark) does on web pages, with
 * the same options. The AnkiConnect address and API key are [AnkiSettings]'.
 */
class MarkSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("marks", Context.MODE_PRIVATE)
    private val anki = AnkiSettings(context)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** Package names of the apps marking is off for. */
    var excludedApps: Set<String>
        get() = prefs.getStringSet("excluded_apps", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("excluded_apps", value).apply()

    /** Usually the deck; until it's set, the deck chosen for lookups. */
    var baseQuery: String
        get() = prefs.getString("base_query", null) ?: anki.deck.takeIf { it.isNotEmpty() }?.let(AnkiBackend::deckQuery).orEmpty()
        set(value) = prefs.edit().putString("base_query", value).apply()

    var rules: List<MarkRule>
        get() = prefs.getString("rules", null)?.let { runCatching { rulesFromJson(JSONArray(it)) }.getOrNull() } ?: DEFAULT_RULES
        set(value) = prefs.edit().putString("rules", JSONArray(value.map { it.toJson() }).toString()).apply()

    /** Fields holding the word; none means each note's first field. Until set, the lookup's word field. */
    var fields: List<String>
        get() = prefs.getString("fields", null)?.let { JSONArray(it).strings() } ?: listOfNotNull(anki.field.takeIf { it.isNotEmpty() })
        set(value) = prefs.edit().putString("fields", JSONArray(value).toString()).apply()

    /** JavaScript function body: note -> extra words (see FieldScript); [FORMS_SCRIPT] until set, "" for none. */
    var fieldScript: String
        get() = prefs.getString("field_script", null) ?: FORMS_SCRIPT
        set(value) = prefs.edit().putString("field_script", value).apply()

    var style: String
        get() = prefs.getString("style", null)?.takeIf { it in STYLES } ?: "background"
        set(value) = prefs.edit().putString("style", value).apply()

    /** Background strength in percent */
    var opacity: Int
        get() = prefs.getInt("opacity", 35)
        set(value) = prefs.edit().putInt("opacity", value.coerceIn(10, 80)).apply()

    var conjugations: Boolean
        get() = prefs.getBoolean("conjugations", true)
        set(value) = prefs.edit().putBoolean("conjugations", value).apply()

    /** ゴミ also matches ごみ and vice versa */
    var kanaVariants: Boolean
        get() = prefs.getBoolean("kana_variants", true)
        set(value) = prefs.edit().putBoolean("kana_variants", value).apply()

    var kanaMinLen: Int
        get() = prefs.getInt("kana_min_len", 2)
        set(value) = prefs.edit().putInt("kana_min_len", value.coerceIn(1, 10)).apply()

    /** The overlay syncs when it opens and the last sync is older than this; 0 = only on demand. */
    var syncMinutes: Int
        get() = prefs.getInt("sync_minutes", 5)
        set(value) = prefs.edit().putInt("sync_minutes", value).apply()

    /** Notes per notesInfo request */
    var notesBatch: Int
        get() = prefs.getInt("notes_batch", 100)
        set(value) = prefs.edit().putInt("notes_batch", value.coerceIn(10, 1000)).apply()

    val matcherOptions get() = WordMatcher.Options(kanaMinLen, conjugations, kanaVariants)

    fun isOffFor(app: String?) = !enabled || (app != null && app in excludedApps)

    fun setOffFor(app: String, off: Boolean) {
        excludedApps = if (off) excludedApps + app else excludedApps - app
    }

    /** What a sync depends on; when this changes, the word list is stale. */
    fun syncSignature(): String = syncSignature(baseQuery, rules, fields, fieldScript)

    /** The settings as a file AnkiMark can import too (it ignores excludedApps). The address and key stay out. */
    fun export(version: String): JSONObject = JSONObject()
        .put("hiroi", version)
        .put("baseQuery", baseQuery)
        .put("rules", JSONArray(rules.map { it.toJson() }))
        .put("fields", JSONArray(fields))
        .put("fieldScript", fieldScript)
        .put("style", style)
        .put("opacity", opacity)
        .put("conjugations", conjugations)
        .put("kanaVariants", kanaVariants)
        .put("kanaMinLen", kanaMinLen)
        .put("enabled", enabled)
        .put("excludedApps", JSONArray(excludedApps.sorted()))
        .put("syncMinutes", syncMinutes)

    /** Applies [parsed] (from [parseImport]): replaces the settings found in the file, leaves the rest. */
    fun apply(parsed: Imported) {
        parsed.baseQuery?.let { baseQuery = it }
        parsed.rules?.let { rules = it }
        parsed.fields?.let { fields = it }
        parsed.fieldScript?.let { fieldScript = it }
        parsed.style?.let { style = it }
        parsed.opacity?.let { opacity = it }
        parsed.conjugations?.let { conjugations = it }
        parsed.kanaVariants?.let { kanaVariants = it }
        parsed.kanaMinLen?.let { kanaMinLen = it }
        parsed.enabled?.let { enabled = it }
        parsed.excludedApps?.let { excludedApps = it }
        parsed.syncMinutes?.let { syncMinutes = it }
    }

    data class Imported(
        val baseQuery: String? = null,
        val rules: List<MarkRule>? = null,
        val fields: List<String>? = null,
        val fieldScript: String? = null,
        val style: String? = null,
        val opacity: Int? = null,
        val conjugations: Boolean? = null,
        val kanaVariants: Boolean? = null,
        val kanaMinLen: Int? = null,
        val enabled: Boolean? = null,
        val excludedApps: Set<String>? = null,
        val syncMinutes: Int? = null,
    )

    companion object {
        val STYLES = listOf("background", "underline")
        val SYNC_MINUTES = listOf(1, 5, 15, 60, 0)

        val DEFAULT_RULES = listOf(
            MarkRule("leech", "Leech", "prop:lapses>=8", "#e5484d", enabled = false),
            MarkRule("mature", "Mature", "prop:ivl>=21", "#28c85a"),
            MarkRule("learning", "Learning", "-is:new", "#ffa600"),
            MarkRule("new", "New", "is:new", "#408cff"),
        )

        // Yomitan puts every dictionary's entry into one Glossary field; some list the word's
        // other spellings as "forms". (AnkiMark's example.) The default field script.
        const val FORMS_SCRIPT = """// Yomitan's Glossary field: add the spellings the dictionaries list as "forms".
const html = note.fields.Glossary;
if (!html) return [];
const doc = new DOMParser().parseFromString(html, "text/html");
const forms = [];
// Jitendex: <li data-sc-content="forms"> holding a list (or a table) of spellings
for (const el of doc.querySelectorAll('[data-sc-content="forms"] li, [data-sc-content="forms"] th')) {
  forms.push(el.textContent);
}
// JMdict: <li><i>(forms)</i> 刺（★） | 棘（★） | トゲ</li>
// (the cleaning drops the parentheses and splits at "|")
for (const label of doc.querySelectorAll("li > i")) {
  if (label.textContent === "(forms)") forms.push(label.parentElement.textContent);
}
return forms;
"""

        /** One-tap rules for the settings screen, as in AnkiMark. */
        val EXAMPLES = listOf(
            MarkRule("", "Leech", "prop:lapses>=8", "#e5484d"),
            MarkRule("", "Due today", "is:due", "#b45cff"),
            MarkRule("", "Forgot this week", "rated:7:1", "#ff6b3d"),
            MarkRule("", "Suspended", "is:suspended", "#9a9aa3"),
            MarkRule("", "Low recall (FSRS)", "prop:r<0.8", "#e5484d"),
            MarkRule("", "Tagged", "tag:important", "#00b3b3"),
            MarkRule("", "Everything else", "", "#9a9aa3"),
        )

        /** Full Anki search for one rule: base filter AND rule search; null if both are empty. */
        fun ruleQuery(base: String, query: String): String? {
            val parts = listOf(base, query).map { it.trim() }.filter { it.isNotEmpty() }
            return if (parts.isEmpty()) null else parts.joinToString(" ") { "($it)" }
        }

        /** Search matching every note any rule can match. */
        fun unionQuery(base: String, rules: List<MarkRule>): String? {
            val queries = rules.mapNotNull { ruleQuery(base, it.query) }
            return if (queries.isEmpty()) null else queries.joinToString(" OR ") { "($it)" }
        }

        fun syncSignature(base: String, rules: List<MarkRule>, fields: List<String>, fieldScript: String): String {
            val out = JSONArray()
                .put(base.trim())
                .put(JSONArray(rules.map { JSONArray().put(it.id).put(it.query.trim()) }))
                .put(JSONArray(fields.sorted()))
            if (fieldScript.isNotBlank()) out.put(fieldScript.trim())
            return out.toString()
        }

        private fun rulesFromJson(array: JSONArray) = List(array.length()) { i ->
            val r = array.getJSONObject(i)
            MarkRule(r.getString("id"), r.optString("name"), r.optString("query"), r.optString("color", "#888888"), r.optBoolean("enabled", true))
        }

        /**
         * Checks a settings file (Hiroi's or AnkiMark's export) and returns only valid settings;
         * throws if it doesn't look like one at all.
         */
        fun parseImport(text: String): Imported {
            val data = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a settings file (no JSON object).") }
            fun str(key: String) = data.opt(key) as? String
            fun strList(key: String) = (data.opt(key) as? JSONArray)?.takeIf { a -> (0 until a.length()).all { a.opt(it) is String } }?.strings()
            fun bool(key: String) = data.opt(key) as? Boolean
            fun int(key: String, range: IntRange) = (data.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { Math.round(it).toInt().coerceIn(range) }

            val rules = data.opt("rules")?.let { value ->
                require(value is JSONArray) { "\"rules\" must be a list." }
                val ids = HashSet<String>()
                List(value.length()) { i ->
                    val r = value.opt(i) as? JSONObject
                    val query = r?.opt("query") as? String ?: throw IllegalArgumentException("Rule ${i + 1} needs a \"query\" text.")
                    val id = (r.opt("id") as? String)?.takeIf { Regex("^[\\w-]+$").matches(it) && it !in ids } ?: MarkRule.newId()
                    ids += id
                    val color = r.opt("color") as? String
                    MarkRule(
                        id = id,
                        name = (r.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: "Rule ${i + 1}",
                        query = query,
                        color = if (color != null && MarkRule.isColor(color)) color.lowercase() else "#888888",
                        enabled = r.opt("enabled") != false,
                    )
                }
            }
            val out = Imported(
                baseQuery = str("baseQuery"),
                rules = rules,
                fields = strList("fields"),
                fieldScript = str("fieldScript"),
                style = str("style")?.takeIf { it in STYLES },
                opacity = int("opacity", 10..80),
                conjugations = bool("conjugations"),
                kanaVariants = bool("kanaVariants"),
                kanaMinLen = int("kanaMinLen", 1..10),
                enabled = bool("enabled"),
                excludedApps = strList("excludedApps")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
                syncMinutes = (data.opt("syncMinutes") as? Number)?.toInt()?.takeIf { it in SYNC_MINUTES },
            )
            require(out != Imported()) { "No settings found in this file." }
            return out
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }
    }
}
