package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer

/**
 * The words of a dictionary's definitions, for finding its terms by meaning: "eat" finds
 * 食べる in an English dictionary, "essen" in a German one. Languages aren't told apart: every
 * word of the definitions is lowercased and stripped of accents (für → fur, Straße → strasse),
 * the same for what's searched for.
 */
object Meanings {
    /** Structured content's lists of glosses: Jitendex's and JMdict's "glossary", Wiktionary's "glosses" */
    private val GLOSS_LISTS = setOf("glossary", "glosses")

    /** What's around the glosses in structured content, which isn't searched: examples, notes, forms, links… */
    private val ASIDES = listOf("example", "note", "attribution", "backlink", "forms", "ref", "info", "Etymology", "Grammar", "preamble", "synonym")

    /** Longer words are links and such */
    private const val LONGEST_WORD = 40

    /**
     * The glosses of [glossary] (a term bank row's JSON array), in their order: in structured
     * content the items of its gloss lists if it has any, else each line of its text; each line of
     * a text definition.
     */
    fun glosses(glossary: String): List<String> {
        val items = try {
            JSONArray(glossary)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<String>()
        for (i in 0 until items.length()) {
            when (val item = items.get(i)) {
                is String -> lines(item, out)
                is JSONObject -> when (item.optString("type")) {
                    "text" -> lines(item.optString("text"), out)
                    "structured-content" -> {
                        val before = out.size
                        listed(item.opt("content"), out)
                        if (out.size == before) lines(StringBuilder().also { text(item.opt("content"), it) }.toString(), out)
                    }
                }
                // [term, [rules]]: an inflection of another term, nothing to search
            }
        }
        return out
    }

    private fun lines(text: String, out: MutableList<String>) {
        for (line in text.split('\n')) if (line.isNotBlank()) out += line.trim()
    }

    private fun aside(node: JSONObject): Boolean {
        val content = node.optJSONObject("data")?.optString("content").orEmpty()
        return ASIDES.any { it in content }
    }

    /** Adds the items of the gloss lists under [node], each one gloss */
    private fun listed(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) listed(node.get(i), out)
            is JSONObject -> {
                if (node.optJSONObject("data")?.optString("content") in GLOSS_LISTS) {
                    val items = node.opt("content")
                    for (item in if (items is JSONArray) List(items.length()) { items.get(it) } else listOf(items)) {
                        val gloss = StringBuilder().also { text(item, it) }.toString().replace('\n', ' ').trim()
                        if (gloss.isNotEmpty()) out += gloss
                    }
                } else if (!aside(node)) {
                    listed(node.opt("content"), out)
                }
            }
        }
    }

    /** The text under [node], without ruby readings, images and asides; block elements on lines of their own */
    private fun text(node: Any?, out: StringBuilder) {
        when (node) {
            is String -> out.append(node)
            is JSONArray -> for (i in 0 until node.length()) text(node.get(i), out)
            is JSONObject -> {
                val tag = node.optString("tag")
                if (tag == "rt" || tag == "rp" || tag == "img" || aside(node)) return
                if (tag == "br") out.append('\n')
                val block = tag in BLOCKS
                if (block) out.append('\n')
                text(node.opt("content"), out)
                if (block) out.append('\n') else if (tag == "td" || tag == "th") out.append(' ')
            }
        }
    }

    private val BLOCKS = setOf("div", "p", "ul", "ol", "li", "table", "tr", "details", "summary")

    /**
     * The words of [text] as they're searched: lowercase, without accents (ß as ss), runs of
     * Latin letters and digits; anything else (Japanese, punctuation) separates them. ASCII is
     * taken as it is and Japanese skipped without looking further: definitions are long and many.
     */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun end() {
            if (word.isNotEmpty() && word.length <= LONGEST_WORD) out += word.toString()
            word.setLength(0)
        }
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> word.append(c)
                c in 'A'..'Z' -> word.append(c + ('a' - 'A'))
                c < '\u0080' -> end()
                // CJK, kana and the rest up to the full-width forms, surrogates (𠮟): never Latin
                c >= '\u2E80' && c < '\uFF00' -> end()
                else -> if (!fold(c, word)) end()
            }
        }
        end()
        return out
    }

    /** Each character's [folded] form, once worked out ("" if it's no part of a word), by code */
    private val FOLDED = arrayOfNulls<String>(0x10000)

    /** Adds what's left of [c] without its accents, lowercase, to [word]; false if it's no Latin letter or digit */
    private fun fold(c: Char, word: StringBuilder): Boolean {
        val folded = FOLDED[c.code] ?: folded(c).also { FOLDED[c.code] = it }
        word.append(folded)
        return folded.isNotEmpty()
    }

    /** [c] without its accents, lowercase (ß as ss); "" if it's no Latin letter or digit */
    private fun folded(c: Char): String {
        val out = StringBuilder()
        for (d in Normalizer.normalize(c.toString(), Normalizer.Form.NFKD)) {
            val lower = d.lowercaseChar()
            when {
                Character.getType(d) == Character.NON_SPACING_MARK.toInt() -> {}
                lower == 'ß' -> out.append("ss")
                lower in 'a'..'z' || lower in '0'..'9' -> out.append(lower)
                lower.isLetter() && Character.UnicodeScript.of(lower.code) == Character.UnicodeScript.LATIN -> out.append(lower)
                else -> return out.toString()
            }
        }
        return out.toString()
    }

    /**
     * Whether a dictionary's definitions are in a language of Latin letters, as its index.json
     * says ([targetLanguage]) or, if it doesn't, as at least half of the letters of the glosses of
     * [sample] (some of its glossaries) are: English, German… not Japanese.
     */
    fun inLatinLetters(targetLanguage: String?, sample: List<String>): Boolean {
        if (!targetLanguage.isNullOrEmpty()) return targetLanguage.lowercase().substringBefore('-') !in CJK
        var latin = 0L
        var other = 0L
        for (glossary in sample) {
            for (gloss in glosses(glossary)) {
                for (c in gloss) {
                    if (!c.isLetter()) continue
                    if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) latin++ else other++
                }
            }
        }
        return latin > 0 && latin >= other
    }

    private val CJK = setOf("ja", "jpn", "zh", "zho", "chi", "ko", "kor")
}
