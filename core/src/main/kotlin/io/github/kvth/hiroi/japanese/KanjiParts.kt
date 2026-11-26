package io.github.kvth.hiroi.japanese

/**
 * What kanji are built from, as Ideographic Description Sequences (働 is ⿰亻動: 亻 beside 動), and
 * which kanji are built with each: kanji-parts.txt (a resource next to this class), made by scripts/make-kanji-parts.py from
 * cjkvi-ids, based on the CHISE IDS Database (GPL-2.0-or-later). [text] is that file.
 */
class KanjiParts(text: String) {
    /** Each kanji's description and its JIS level (1, 2, 3; 0 if it's only a part of others) */
    private val descriptions = HashMap<String, String>()
    private val levels = HashMap<String, Int>()

    /** The kanji built with each part, directly (働 with 動, not with 力) */
    private val users = HashMap<String, MutableList<String>>()

    init {
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size < 2) continue
            descriptions[fields[0]] = fields[1]
            levels[fields[0]] = fields.getOrNull(2)?.toIntOrNull() ?: 0
        }
        for ((kanji, description) in descriptions) {
            if (levels[kanji] == 0) continue
            for (part in characters(description).distinct()) users.getOrPut(part) { ArrayList() } += kanji
        }
        // The commonest kanji first, as JIS has them (its first level), then by code
        for (list in users.values) list.sortWith(compareBy<String>({ levels[it] }, { it.codePointAt(0) }))
    }

    /** Whether there's anything to say about [kanji]: its parts, or kanji built with it */
    fun has(kanji: String): Boolean = parts(kanji).isNotEmpty() || usedIn(kanji).isNotEmpty()

    /** The parts [kanji] is built from, in order (林: 木, 木); none for a kanji that has no smaller parts */
    fun parts(kanji: String): List<Part> {
        val description = descriptions[kanji] ?: return emptyList()
        val parts = characters(description)
        return parts.mapIndexed { i, part -> Part(part, if (isPlaceholder(part)) null else fullForm(part, i == 0) ?: part) }
    }

    /**
     * The kanji built with [kanji] or one of its forms (人: 休 with 亻 too), those Japanese uses,
     * the commonest first.
     */
    fun usedIn(kanji: String): List<String> {
        val forms = listOf(kanji) + FORMS.filterValues { kanji in it.split('/') }.keys
        return forms.flatMap { users[it].orEmpty() }.distinct().sortedWith(compareBy({ levels[it] }, { it.codePointAt(0) }))
    }

    /** [kanji]'s parts as a line: "亻(人) + 動"; null if it has none */
    fun line(kanji: String): String? = parts(kanji).takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.label }

    /**
     * A part as written ([text]: 亻), and the kanji to look it up as ([kanji]: 人, the one it's a
     * form of); null for a part without a character of its own.
     */
    data class Part(val text: String, val kanji: String?) {
        /** How it's shown: 亻(人), 動, or … for one without a character */
        val label: String get() = when (kanji) {
            null -> "…"
            text -> text
            else -> "$text($kanji)"
        }
    }

    companion object {
        @Volatile
        private var loaded: KanjiParts? = null

        /** The app's kanji parts, read once (on a background thread: it takes a moment) */
        fun get(): KanjiParts = loaded ?: synchronized(this) {
            loaded ?: KanjiParts(KanjiParts::class.java.getResourceAsStream("kanji-parts.txt")!!.bufferedReader().use { it.readText() }).also { loaded = it }
        }

        /** The parts of a description: its characters but the layout ones (⿰ ⿱ …) */
        private fun characters(description: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < description.length) {
                val c = description.codePointAt(i)
                if (c !in 0x2FF0..0x2FFF && c != 0x31EF) out += String(Character.toChars(c))
                i += Character.charCount(c)
            }
            return out
        }

        /** ①–⑳: parts that have no character of their own */
        private fun isPlaceholder(part: String) = part.codePointAt(0) in 0x2460..0x2473

        /**
         * The kanji a part is a form of, as the radicals are named (亻: 人, 氵: 水); [first]:
         * whether it comes first, for 阝 (阜 on the left, 邑 on the right).
         */
        private fun fullForm(part: String, first: Boolean): String? =
            FORMS[part]?.split('/')?.let { if (it.size > 1 && !first) it[1] else it[0] }

        private val FORMS = mapOf(
            "亻" to "人", "⺅" to "人", "𠆢" to "人", "氵" to "水", "⺡" to "水", "扌" to "手", "龵" to "手", "忄" to "心", "⺖" to "心", "㣺" to "心",
            "艹" to "艸", "⺾" to "艸", "⺿" to "艸", "辶" to "辵", "⻌" to "辵", "⻍" to "辵", "刂" to "刀", "⺈" to "刀", "礻" to "示", "⺭" to "示",
            "衤" to "衣", "⻂" to "衣", "犭" to "犬", "飠" to "食", "⻞" to "食", "釒" to "金", "糹" to "糸", "訁" to "言", "灬" to "火", "爫" to "爪",
            "⺤" to "爪", "罒" to "网", "⺲" to "网", "㓁" to "网", "攵" to "攴", "⺌" to "小", "⺍" to "小", "⺮" to "竹", "⺶" to "羊", "⺷" to "羊",
            "⻊" to "足", "𧾷" to "足", "耂" to "老", "牜" to "牛", "⺧" to "牛", "⺩" to "玉", "丬" to "爿", "⺺" to "聿", "⺻" to "聿", "阝" to "阜/邑",
            "⻖" to "阜", "⻏" to "邑", "⺼" to "肉", "⺝" to "月", "⺄" to "乙", "⺆" to "冂", "⻗" to "雨", "⺗" to "心", "⺢" to "水", "⻀" to "足",
        )
    }
}
