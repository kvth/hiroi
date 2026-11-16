package io.github.kvth.hiroi

import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.Html
import io.github.kvth.hiroi.anki.Markers
import io.github.kvth.hiroi.anki.StructuredHtml
import io.github.kvth.hiroi.dictionary.KanjiEntry
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.TermEntry
import io.github.kvth.hiroi.dictionary.TermGroup
import io.github.kvth.hiroi.dictionary.Yomitan
import org.json.JSONArray

/** Whether the looked-up words have notes in the chosen Anki deck; [error] if Anki couldn't be asked. */
class AnkiStatus(val words: Set<String>, val error: String? = null)

/**
 * A lookup result as HTML, laid out like Yomitan's popup: the headword with furigana, tags for
 * inflection, Anki, frequency and pitch, then the numbered definitions with their dictionary,
 * styled by the dictionary's own styles.css. "＋ Anki" buttons call Hiroi.addCard(index).
 */
class ResultsHtml(private val colors: Colors) {
    /** CSS colours from the app theme, so the results match the panel. */
    data class Colors(val text: String, val secondary: String, val accent: String)

    fun document(result: LookupResult, anki: AnkiStatus?, styles: Map<String, String>, cards: Boolean): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        append("<style>").append(css())
        // Each dictionary's styles only apply inside its own definitions (CSS nesting, as Jitendex itself uses).
        for (dictionary in result.terms.flatMap { it.entries }.map { it.dictionary }.distinct()) {
            val sheet = styles[dictionary] ?: continue
            append(".glossary[data-dictionary=\"").append(cssString(dictionary)).append("\"] {\n").append(sheet).append("\n}\n")
        }
        append("</style><script>")
        append("function setCard(i, label, enabled) { var b = document.getElementById('card' + i); if (b) { b.textContent = label; b.disabled = !enabled; } }")
        append("function inAnki(i, label) { var t = document.getElementById('anki' + i); if (t) { t.className = 'tag anki'; t.firstChild.textContent = label; } }")
        append("</script></head><body>")
        anki?.error?.let { append("<div class=\"error\">").append(Html.escape(it)).append("</div>") }
        for ((i, term) in result.terms.withIndex()) term(i, term, anki, cards)
        for (kanji in result.kanji) kanji(kanji)
        append("</body></html>")
    }

    private fun StringBuilder.term(index: Int, term: TermGroup, anki: AnkiStatus?, cards: Boolean) {
        append("<div class=\"entry\"><div class=\"header\"><span class=\"headword\" lang=\"ja\">")
        append(Furigana.html(Furigana.distribute(term.expression, term.reading)))
        append("</span>")
        if (cards) append("<button class=\"card\" id=\"card$index\" onclick=\"Hiroi.addCard($index)\">＋ Anki</button>")
        append("</div>")
        if (term.inflections.isNotEmpty()) {
            append("<div class=\"inflections\">« ").append(Html.escape(term.inflections.joinToString(" « "))).append("</div>")
        }

        val tags = StringBuilder()
        if (anki != null && anki.error == null) {
            if (term.expression in anki.words) tags.tag("anki", "✓ in Anki", id = "anki$index") else tags.tag("not-anki", "not in Anki", id = "anki$index")
        }
        for (meta in term.meta) {
            Yomitan.frequency(meta, term.reading)?.let { tags.tag("frequency", meta.dictionary, Html.escape(it.display)) }
        }
        val pitches = Markers.pitches(term)
        if (pitches.isNotEmpty()) {
            val dictionary = term.meta.firstOrNull { it.mode == "pitch" }?.dictionary.orEmpty()
            tags.tag("pitch", dictionary, pitches.joinToString(" ") { Markers.pitchHtml(term.reading, it) })
        }
        if (tags.isNotEmpty()) append("<div class=\"tags\">").append(tags).append("</div>")

        append("<ol class=\"definitions\">")
        for (entry in term.entries) definition(entry)
        append("</ol></div>")
    }

    private fun StringBuilder.definition(entry: TermEntry) {
        append("<li><div class=\"definition-tags\">")
        for (tag in entry.definitionTags.split(' ').filter { it.isNotBlank() }) tag("label", tag)
        tag("dictionary", entry.dictionary)
        append("</div><div class=\"glossary\" lang=\"en\" data-dictionary=\"").append(Html.escape(entry.dictionary)).append("\">")
        val items = JSONArray(entry.glossary).let { array -> (0 until array.length()).map { StructuredHtml.item(array.get(it)) } }
            .filter { it.isNotEmpty() }
        if (items.size == 1) append(items.single()) else items.joinTo(this, "", "<ul>", "</ul>") { "<li>$it</li>" }
        append("</div></li>")
    }

    private fun StringBuilder.kanji(kanji: KanjiEntry) {
        append("<div class=\"entry kanji\"><div class=\"header\"><span class=\"character\" lang=\"ja\">")
        append(Html.escape(kanji.character)).append("</span></div><div class=\"tags\">")
        tag("dictionary", kanji.dictionary)
        append("</div><table>")
        for ((label, readings) in listOf("On" to kanji.onyomi, "Kun" to kanji.kunyomi)) {
            if (readings.isNotBlank()) append("<tr><th>$label</th><td lang=\"ja\">").append(Html.escape(readings.replace(" ", "、"))).append("</td></tr>")
        }
        append("</table>")
        kanji.meanings.joinTo(this, "", "<ol class=\"definitions\">", "</ol>") { "<li>${Html.escape(it)}</li>" }
        append("</div>")
    }

    /** A tag like Yomitan's: a coloured label, optionally followed by a value (frequency, pitch). */
    private fun StringBuilder.tag(kind: String, label: String, valueHtml: String? = null, id: String? = null) {
        append("<span class=\"tag $kind\"")
        if (id != null) append(" id=\"").append(id).append('"')
        append("><span class=\"label\">").append(Html.escape(label)).append("</span>")
        if (valueHtml != null) append("<span class=\"value\">").append(valueHtml).append("</span>")
        append("</span>")
    }

    private fun cssString(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun css() = """
        body { margin: 0; color: ${colors.text}; background: transparent; font-family: sans-serif; font-size: 15px; line-height: 1.45; }
        .error { color: ${colors.secondary}; font-size: 0.85em; margin-bottom: 8px; }
        .entry + .entry { margin-top: 14px; padding-top: 12px; border-top: 1px solid ${colors.secondary}40; }
        .header { display: flex; align-items: flex-end; gap: 8px; }
        .headword { flex: 1; font-size: 2em; line-height: 1.25; }
        .headword rt, .glossary rt { color: ${colors.secondary}; }
        .card { flex: none; margin-bottom: 4px; padding: 4px 10px; border: 1px solid ${colors.accent}; border-radius: 6px;
            background: none; color: ${colors.accent}; font: inherit; font-size: 0.9em; }
        .card:disabled { opacity: 0.6; }
        .inflections { color: ${colors.secondary}; font-size: 0.85em; font-style: italic; }
        .tags, .definition-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
        .tag { display: inline-flex; border-radius: 4px; overflow: hidden; font-size: 0.75em; line-height: 1.7; white-space: nowrap; }
        .tag > span { padding: 0 6px; }
        .tag .label { background: #8a8a91; color: white; font-weight: bold; }
        .tag .value { background: ${colors.secondary}26; color: ${colors.text}; }
        .tag.dictionary .label { background: #9057ad; }
        .tag.frequency .label { background: #489148; }
        .tag.pitch .label { background: #6640be; }
        .tag.anki .label { background: ${colors.accent}; }
        .tag.not-anki { border: 1px solid ${colors.secondary}80; }
        .tag.not-anki .label { background: none; color: ${colors.secondary}; font-weight: normal; }
        ol.definitions { margin: 6px 0 0; padding-left: 1.5em; }
        ol.definitions > li + li { margin-top: 8px; }
        .glossary { margin-top: 3px; }
        .glossary ul, .glossary ol { margin: 2px 0; padding-left: 1.3em; }
        .kanji .character { font-size: 3em; line-height: 1.1; }
        .kanji table { margin-top: 6px; border-collapse: collapse; }
        .kanji th { padding-right: 10px; color: ${colors.secondary}; font-weight: normal; text-align: left; }
    """.trimIndent()
}
