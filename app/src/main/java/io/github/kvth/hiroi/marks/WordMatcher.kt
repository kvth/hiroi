package io.github.kvth.hiroi.marks

import io.github.kvth.hiroi.japanese.Deinflector
import io.github.kvth.hiroi.japanese.Japanese

/** Words of Anki notes, as AnkiMark (ankimark/japanese.js) finds and matches them. */
object NoteWords {
    private val ENTITIES = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    private fun decodeEntities(s: String) = Regex("&(#x[0-9a-f]+|#\\d+|[a-z]+);", RegexOption.IGNORE_CASE).replace(s) { m ->
        val e = m.groupValues[1]
        if (e.startsWith("#")) {
            val code = if (e[1] == 'x' || e[1] == 'X') e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        } else {
            ENTITIES[e.lowercase()] ?: m.value
        }
    }

    /** An Anki field value (HTML, furigana, cloze, ...) as a list of plain Japanese words. */
    fun extractWords(html: String): List<String> {
        var s = html
        s = s.replace(Regex("\\[sound:[^\\]]*\\]"), " ")
        s = s.replace(Regex("\\{\\{c\\d+::(.*?)(?:::.*?)?\\}\\}"), "$1")
        s = s.replace(Regex("<(rt|rp)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<br\\s*/?>|</(div|p|li|tr|h\\d)>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = decodeEntities(s)
        // Anki furigana syntax: " 日本[にほん] 語[ご]" -> "日本語"
        s = s.replace(Regex(" ?([^\\s\\[\\]]*)\\[[぀-ヿ・ー]*\\]"), "$1")
        // Optional parts like 勉強（する） or 〜的
        s = s.replace(Regex("[（(][^）)]*[）)]"), " ")
        s = s.replace(Regex("[〜～~]"), "")
        return s.split(Regex("[\\s、,，;；/／|｜。]+"))
            .map { it.dropWhile { c -> !Japanese.isJapanese(c) }.dropLastWhile { c -> !Japanese.isJapanese(c) } }
            .filter { it.isNotEmpty() && it.length <= 20 && it.all(Japanese::isJapanese) }
    }
}

/**
 * Finds the words of [entries] in text: the longest known word at each position, as written or
 * (with [Options.conjugations]) inflected, with Yomitan's deinflection rules. A port of
 * AnkiMark's Matcher; the value of each entry comes back as [Match.entry].
 */
class WordMatcher<T>(entries: Map<String, T>, private val options: Options = Options()) {

    data class Options(val kanaMinLen: Int = 2, val conjugations: Boolean = true, val kanaVariants: Boolean = true)

    /** [word] is the note's spelling; [variant]: found in the other kana (ごみ for ゴミ). */
    data class Match<T>(val start: Int, val end: Int, val word: String, val entry: T, val inflected: Boolean, val variant: Boolean)

    private val exact = HashMap<String, T>()
    private val variantOf = HashMap<String, String>() // other-kana spelling -> word as in the notes
    private val stems = HashSet<String>()
    private val stemPrefixes = HashSet<String>()
    private val deinflected = HashMap<String, List<String>>()

    init {
        val kanaWords = mutableListOf<String>()
        for ((word, entry) in entries) {
            val kana = Japanese.isKanaOnly(word)
            if (kana && word.length < options.kanaMinLen) continue
            add(word, entry)
            if (kana) kanaWords += word
        }
        // ゴミ also matches ごみ and the other way round, unless a note has that spelling itself.
        if (options.kanaVariants) {
            for (word in kanaWords) {
                for (v in listOf(Japanese.toHiragana(word), Japanese.toKatakana(word))) {
                    if (v in exact) continue
                    add(v, exact.getValue(word))
                    variantOf[v] = word
                }
            }
        }
    }

    val size get() = exact.size - variantOf.size

    private fun add(word: String, entry: T) {
        exact[word] = entry
        val stem = wordStem(word)
        stems += stem
        for (i in 1..stem.length) stemPrefixes += stem.substring(0, i)
    }

    private fun result(start: Int, end: Int, word: String, inflected: Boolean): Match<T> {
        val original = variantOf[word]
        return Match(start, end, original ?: word, exact.getValue(word), inflected, original != null)
    }

    /** The longest known word starting at [i], or null. */
    fun matchAt(text: String, i: Int): Match<T>? {
        var longestStem = 0
        for (j in i + 1..text.length) {
            val prefix = text.substring(i, j)
            if (prefix !in stemPrefixes) break
            if (prefix in stems) longestStem = j - i
        }
        if (longestStem == 0) return null
        // Inflection endings are hiragana: a match can only run on through them.
        var end = i + longestStem
        val limit = minOf(text.length, end + MAX_INFLECTION)
        while (end < limit && text[end] in 'ぁ'..'ゟ') end++
        for (j in end downTo i + 1) {
            val candidate = text.substring(i, j)
            if (candidate in exact) return result(i, j, candidate, false)
            if (options.conjugations && j - i > longestStem) {
                val word = deinflect(candidate).firstOrNull { it in exact && inflects(it) }
                if (word != null) return result(i, j, word, true)
            }
        }
        return null
    }

    /** Non-overlapping matches, longest first, scanning left to right. */
    fun find(text: String): List<Match<T>> {
        val matches = mutableListOf<Match<T>>()
        if (exact.isEmpty()) return matches
        var i = 0
        while (i < text.length) {
            val m = if (Japanese.isJapanese(text[i])) matchAt(text, i) else null
            if (m != null) {
                matches += m
                i = m.end
            } else {
                i++
            }
        }
        return matches
    }

    /** Every dictionary form [text] could be an inflection of (not [text] itself). */
    private fun deinflect(text: String): List<String> = synchronized(deinflected) {
        deinflected.getOrPut(text) {
            if (deinflected.size > 20000) deinflected.clear()
            Deinflector.deinflect(text)
                .filter { it.conditions and dictionaryForms != 0 }
                .map { it.text }
                .distinct()
                .filter { it != text }
        }
    }

    companion object {
        /** Longest inflected tail a match may carry past its stem (食べさせられなかった). */
        private const val MAX_INFLECTION = 10

        private val dictionaryForms by lazy { Deinflector.transformer.dictionaryFormFlags }

        /**
         * Inflection only changes a word's hiragana tail, so a match always starts with the word's
         * stem: 食べる -> 食, 勉強する -> 勉強, 高い -> 高. All-kana words keep all but their last two characters.
         */
        fun wordStem(word: String): String =
            word.trimEnd { it in 'ぁ'..'ゟ' }.ifEmpty { word.take(maxOf(1, word.length - 2)) }

        /**
         * Short kana words are mostly nouns and particles (いつ, くつ), so only the common kana verbs
         * (ある, いる, なる, する, いう, ...) are matched inflected.
         */
        private fun inflects(word: String) = !(Japanese.isKanaOnly(word) && word.length <= 2 && !(word.endsWith("る") || word.endsWith("う")))
    }
}
