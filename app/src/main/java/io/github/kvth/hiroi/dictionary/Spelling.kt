package io.github.kvth.hiroi.dictionary

/** Spellings near a mistyped one, for searches that find nothing as typed. */
object Spelling {
    private val LETTERS = ('a'..'z').toList()

    /**
     * Every spelling one edit from [word] in lowercase letters: a letter left out, one more
     * put in, one changed, or two next to each other swapped (hosue → house). [word] itself isn't.
     */
    fun edits(word: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in word.indices) {
            out += word.removeRange(i, i + 1)
            if (i + 1 < word.length && word[i] != word[i + 1]) {
                out += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
            }
            for (c in LETTERS) if (c != word[i]) out += word.substring(0, i) + c + word.substring(i + 1)
        }
        for (i in 0..word.length) for (c in LETTERS) out += word.substring(0, i) + c + word.substring(i)
        out -= word
        return out
    }
}
