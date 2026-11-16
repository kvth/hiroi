package io.github.kvth.hiroi.anki

import org.json.JSONArray
import org.json.JSONObject

/**
 * How Hiroi makes Anki cards, modelled on a Yomitan card format: which deck and note type, a
 * template with markers for each field, tags, and what to do about duplicates, with Yomitan's
 * options: [checkForDuplicates]; [duplicateBehavior] "prevent" (don't add), "overwrite" (update the
 * existing note, each field by its [overwriteModes] entry) or "new" (add another); [duplicateScope]
 * "collection", "deck" or "deck-root" (the top deck and its subdecks); [checkAllModels] to count
 * notes of other note types too. A duplicate is a note with the same first field, as in Anki.
 */
data class CardFormat(
    val deck: String = "",
    val model: String = "",
    val fields: Map<String, String> = emptyMap(),
    val tags: List<String> = listOf("hiroi"),
    val checkForDuplicates: Boolean = true,
    val duplicateBehavior: String = "new",
    val duplicateScope: String = "collection",
    val checkAllModels: Boolean = false,
    val overwriteModes: Map<String, String> = emptyMap(),
) {
    val complete get() = deck.isNotEmpty() && model.isNotEmpty() && fields.values.any { it.isNotBlank() }

    val preventDuplicates get() = checkForDuplicates && duplicateBehavior == "prevent"

    /** How overwriting a duplicate treats [field]; Yomitan's default keeps the old value where the new one is empty. */
    fun overwriteMode(field: String) = overwriteModes[field]?.takeIf { it in OVERWRITE_MODES } ?: "coalesce"

    /** Markers in the templates that Hiroi can't fill (custom handlebars templates, graphs, ...). */
    val unsupported get() = fields.values.flatMap { Markers.used(it) }.filter { it !in Markers.supported }.distinct()

    fun toJson(): String = JSONObject()
        .put("deck", deck)
        .put("model", model)
        .put("fields", JSONObject().also { json -> fields.forEach { (name, template) -> json.put(name, template) } })
        .put("fieldOrder", JSONArray(fields.keys.toList()))
        .put("tags", JSONArray(tags))
        .put("checkForDuplicates", checkForDuplicates)
        .put("duplicateBehavior", duplicateBehavior)
        .put("duplicateScope", duplicateScope)
        .put("checkAllModels", checkAllModels)
        .put("overwriteModes", JSONObject(overwriteModes as Map<*, *>))
        .toString()

    companion object {
        fun fromJson(json: String): CardFormat {
            val o = JSONObject(json)
            val fields = o.getJSONObject("fields")
            val order = o.optJSONArray("fieldOrder")?.let { a -> List(a.length()) { a.getString(it) } } ?: fields.keys().asSequence().toList()
            return CardFormat(
                deck = o.optString("deck"),
                model = o.optString("model"),
                fields = order.filter { fields.has(it) }.associateWithTo(LinkedHashMap()) { fields.getString(it) },
                tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                checkForDuplicates = o.optBoolean("checkForDuplicates", true),
                // Formats saved before Hiroi had Yomitan's options only knew "prevent" or not.
                duplicateBehavior = o.optString("duplicateBehavior").takeIf { it in DUPLICATE_BEHAVIORS }
                    ?: if (o.optBoolean("preventDuplicates")) "prevent" else "new",
                duplicateScope = o.optString("duplicateScope").takeIf { it in DUPLICATE_SCOPES } ?: "collection",
                checkAllModels = o.optBoolean("checkAllModels"),
                overwriteModes = o.optJSONObject("overwriteModes")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
            )
        }

        val DUPLICATE_BEHAVIORS = listOf("new", "prevent", "overwrite")
        val DUPLICATE_SCOPES = listOf("collection", "deck", "deck-root")

        /**
         * Yomitan's overwrite modes, applied to each field when a duplicate is overwritten: the new
         * value always, the new value unless empty, the old value unless empty, old + new, new + old,
         * or the old value.
         */
        val OVERWRITE_MODES = listOf("overwrite", "coalesce", "coalesce-new", "append", "prepend", "skip")

        /** [field]'s value after overwriting [old] with [new] in [mode]. */
        fun overwrite(mode: String, old: String, new: String): String = when (mode) {
            "overwrite" -> new
            "coalesce-new" -> old.ifEmpty { new }
            "append" -> old + new
            "prepend" -> new + old
            "skip" -> old
            else -> new.ifEmpty { old } // coalesce
        }

        /** Templates for a note type's fields, guessed from their names like Yomitan suggests them. */
        fun guessTemplates(fieldNames: List<String>): Map<String, String> = fieldNames.associateWithTo(LinkedHashMap()) { name ->
            val n = name.lowercase().replace(Regex("[^a-z]"), "")
            when {
                "sentence" in n && "audio" in n -> ""
                "sentence" in n && "furigana" in n -> "{sentence-furigana}"
                "sentence" in n -> "{sentence}"
                "audio" in n -> "{audio}"
                "furigana" in n -> "{furigana-plain}"
                "reading" in n -> "{reading}"
                "pitch" in n && "categor" in n -> "{pitch-accent-categories}"
                "pitch" in n -> "{pitch-accent-positions}"
                "freq" in n -> "{frequency-harmonic-rank}"
                "picture" in n || "image" in n || "screenshot" in n -> "{screenshot}"
                n in setOf("expression", "word", "vocab", "vocabulary", "term", "front", "key") -> "{expression}"
                "glossary" in n || "meaning" in n || "definition" in n || n == "back" -> "{glossary}"
                else -> ""
            }
        }
    }
}

/** Reads the Anki card formats out of a Yomitan settings export (Settings > Backup > Export settings). */
object YomitanSettings {
    /** One entry per profile and term card format that has a note type set, named "profile: format". */
    fun cardFormats(json: String): List<Pair<String, CardFormat>> {
        val options = JSONObject(json).let { it.optJSONObject("options") ?: it }
        val profiles = options.optJSONArray("profiles") ?: return emptyList()
        val out = mutableListOf<Pair<String, CardFormat>>()
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            val anki = profile.optJSONObject("options")?.optJSONObject("anki") ?: continue
            val tags = anki.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val formats = anki.optJSONArray("cardFormats") ?: legacyFormats(anki)
            for (j in 0 until formats.length()) {
                val format = formats.getJSONObject(j)
                if (format.optString("type", "term") != "term" || format.optString("model").isEmpty()) continue
                val fields = format.optJSONObject("fields") ?: continue
                val names = fields.keys().asSequence().toList()
                out += "${profile.optString("name")}: ${format.optString("name", "Expression")}" to CardFormat(
                    deck = format.optString("deck"),
                    model = format.optString("model"),
                    fields = names.associateWithTo(LinkedHashMap()) { name ->
                        when (val field = fields.get(name)) {
                            is JSONObject -> field.optString("value")
                            else -> field.toString()
                        }
                    },
                    tags = tags,
                    checkForDuplicates = anki.optBoolean("checkForDuplicates", true),
                    duplicateBehavior = anki.optString("duplicateBehavior").takeIf { it in CardFormat.DUPLICATE_BEHAVIORS } ?: "new",
                    duplicateScope = anki.optString("duplicateScope").takeIf { it in CardFormat.DUPLICATE_SCOPES } ?: "collection",
                    checkAllModels = anki.optBoolean("duplicateScopeCheckAllModels"),
                    overwriteModes = names.mapNotNull { name ->
                        (fields.get(name) as? JSONObject)?.optString("overwriteMode")?.takeIf { it in CardFormat.OVERWRITE_MODES }?.let { name to it }
                    }.toMap(),
                )
            }
        }
        return out
    }

    /** Older Yomitan versions had a single "terms" format instead of cardFormats. */
    private fun legacyFormats(anki: JSONObject): JSONArray =
        JSONArray().also { array -> anki.optJSONObject("terms")?.let { array.put(it.put("type", "term").put("name", "Expression")) } }
}
