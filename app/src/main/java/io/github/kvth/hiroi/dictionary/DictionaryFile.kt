package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What a dictionary file says about its dictionary: its index.json ([index], verbatim, for its
 * update links), its styles.css, and how many rows of each kind it has.
 */
data class DictionaryFacts(
    val title: String,
    val revision: String,
    val frequencyMode: String?,
    val index: String?,
    val styles: String?,
    val terms: Int = 0,
    val meta: Int = 0,
    val kanji: Int = 0,
    val frequencies: Int = 0,
    val images: Int = 0,
    /** How many terms are forms of other words ([Yomitan.FormOf]); null if the file is older than this count */
    val forms: Int? = null,
    /** How many of [meta] are kanji meta rows, and how many tags it explains; null if the file is older than these counts */
    val kanjiMeta: Int? = null,
    val tags: Int? = null,
)

/** Those of [forms] no longer than [longest] code points; [forms] itself if they all are. */
internal fun fitting(forms: Collection<String>, longest: Int): Collection<String> {
    fun fits(form: String) = form.length <= longest || form.codePointCount(0, form.length) <= longest
    return if (forms.all(::fits)) forms else forms.filter(::fits)
}

/**
 * One dictionary in a file of Hiroi's own format, written once ([Writer]) and only read after:
 * deleting a dictionary is deleting its file, and a collection is the files ([writePortable]).
 *
 * A header lists the sections; each section only refers to places inside itself or inside
 * the data section, so sections can be copied from one file into another as they are.
 *
 * The data section comes first: the glossaries, in deflated blocks of about [BLOCK_SIZE] with a
 * dictionary of their typical text ([trainDictionary]) that every block starts from, which packs
 * them about as well as zipping a whole bank does, while a lookup only inflates the blocks of the
 * results it shows; and the images, as they are.
 *
 * The other sections are mapped into memory and searched in place. Sorted tables ([SortedTable])
 * hold UTF-8 keys (in code point order) with their values, each key written as how much
 * it shares with the one before and the rest, with a restart every [INTERVAL] keys that binary
 * search goes by; a term key's value is its terms. A Bloom filter of the term keys (and one of the
 * term meta keys) lets lookups skip a dictionary that hasn't got a form without searching it.
 * A term's record starts with its sequence and where its glossary is, then its expression and
 * reading, then its tags and rules as numbers in a table of strings. The terms are sorted by
 * sequence, so finding a sequence's needs no index; the glossaries are in the order the terms
 * came, so where a term's glossary is says that order too, which lookups give their results in,
 * as Yomitan does. Frequencies and pitch accents are kept as their numbers and texts
 * ([MetaCodec]), each checked to read back as it was; anything else as its JSON.
 *
 * Writing keeps little in memory: the term records go to a file beside as they come, the keys
 * and the rest through [ExternalSorter], which sorts what doesn't fit in runs on disk.
 *
 * A dictionary whose definitions are in English, German or another language of Latin letters
 * ([Meanings.inLatinLetters]) has its definitions' words as a sorted table too, each with its
 * terms and how well the word fits each ([findTermsByMeaning]): "eat" finds 食べる.
 *
 * Suffix search's keys, made on its first use, are a file of their own beside it
 * ([buildReverseKeys]), so this one never changes. For export, [writePortable] leaves out what
 * [restore] can make again from the term records: the keys and their filter.
 */
class DictionaryFile private constructor(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private val sections: Map<Int, LongArray> = try {
        readHeader(channel, file.name).also { require(PORTABLE !in it) { "${file.name} is an export; restore it first" } }
    } catch (e: Exception) {
        raf.close()
        throw e
    }

    private fun map(id: Int): ByteBuffer? = sections[id]?.let { (offset, length) -> map(channel, offset, length) }

    private fun read(id: Int): ByteArray? = sections[id]?.let { (offset, length) -> readBytes(channel, offset, length.toInt()) }

    private val info = JSONObject(String(read(INFO) ?: throw IllegalArgumentException("${file.name} has no info"), Charsets.UTF_8))
    private val strings: Array<String> = map(STRINGS)?.let(::readStrings) ?: emptyArray()
    private val keys = map(KEYS)?.let(::SortedTable)
    private val keyFilter = map(KEY_FILTER)?.let(::BloomFilter)
    private val terms = map(TERMS)
    private val termCount = terms?.getInt(0) ?: 0
    private val termMeta = map(TERM_META)?.let(::SortedTable)
    private val metaFilter = map(META_FILTER)?.let(::BloomFilter)
    private val kanji = map(KANJI)?.let(::SortedTable)
    private val kanjiMeta = map(KANJI_META)?.let(::SortedTable)
    private val media = map(MEDIA)?.let(::SortedTable)
    private val meanings = map(MEANINGS)?.let(::SortedTable)
    private val glossaryDictionary = read(GLOSSARY_DICTIONARY)

    /** Where the data section starts in the file, and the section itself if it can be mapped (under 2 GB) */
    private val dataStart = sections[DATA]?.get(0) ?: 0L
    private val data: ByteBuffer? = sections[DATA]?.takeIf { it[1] <= Int.MAX_VALUE }?.let { (offset, length) -> map(channel, offset, length) }

    /** Each glossary block's offset in the data section, and where the last ends */
    private val blockOffsets: LongArray = map(GLOSSARY_BLOCKS)?.let { b -> LongArray(b.getInt(0) + 1) { b.getLong(4 + 8 * it) } } ?: LongArray(1)

    private val tags: Map<String, List<TagInfo>> by lazy {
        val bytes = read(TAGS) ?: return@lazy emptyMap()
        val r = Reader(ByteBuffer.wrap(bytes), 0)
        val out = HashMap<String, MutableList<TagInfo>>()
        repeat(r.varint()) {
            val tag = TagInfo(r.string(), r.string(), r.zigzag().toInt(), r.string(), r.zigzag().toInt())
            out.getOrPut(tag.name) { mutableListOf() } += tag
        }
        out
    }

    private val longestTermKey = info.optInt("longest_term_key", Int.MAX_VALUE)
    private val longestMetaKey = info.optInt("longest_meta_key", Int.MAX_VALUE)

    /** What the file says about the dictionary; counts that older files lack, and can be had from them, are counted. */
    fun facts(): DictionaryFacts = facts(info).let { f ->
        f.copy(kanjiMeta = f.kanjiMeta ?: kanjiMetaRows(), tags = f.tags ?: tags.values.sumOf { it.size })
    }

    /** The kanji meta rows, each key's group saying how many it has */
    private fun kanjiMetaRows(): Int {
        val cursor = kanjiMeta?.cursor() ?: return 0
        var rows = 0
        while (cursor.next()) rows += cursor.value().varint()
        return rows
    }

    /** Whether [filter] says [key] may be there, or there's no filter */
    private fun mayHave(filter: BloomFilter?, key: ByteArray): Boolean = filter == null || filter.mightContain(key)

    // ---- Terms ---------------------------------------------------------------------------------

    private fun record(id: Int): Reader {
        val t = terms!!
        return Reader(t, 4 + 4 * (termCount + 1) + t.getInt(4 + 4 * id))
    }

    /** Term [id]'s record; [dictionary] and [priority] go into it. Its glossary is left out. */
    private fun term(id: Int, dictionary: String, priority: Int): TermEntry {
        val r = record(id)
        val sequence = r.zigzag()
        r.varint() // glossary block
        r.varint() // and place in it
        val expression = r.string()
        return TermEntry(
            expression = expression,
            reading = r.string().ifEmpty { expression },
            definitionTags = strings[r.varint()],
            rules = strings[r.varint()],
            score = r.zigzag().toInt(),
            glossary = "",
            sequence = sequence,
            termTags = strings[r.varint()],
            dictionary = dictionary,
            priority = priority,
            id = id.toLong(),
        )
    }

    private fun sequence(id: Int): Long = record(id).zigzag()

    /** Term [id]'s glossary block and its place in it, which sort as the terms came */
    private fun glossaryPlace(id: Int): Long {
        val r = record(id)
        r.zigzag()
        return (r.varint().toLong() shl 32) or r.varint().toLong()
    }

    /** The first [n] of [ids] (no two the same) in the order the terms came */
    private fun inOrderTheyCame(ids: IntArray, n: Int): List<Int> {
        val places = LongArray(n) { glossaryPlace(ids[it]) }
        return sortedIndices(n) { a, b -> places[a].compareTo(places[b]) }.map { ids[it] }
    }

    fun findTerms(forms: Collection<String>, dictionary: String, priority: Int): List<TermEntry> {
        val cursor = keys?.cursor() ?: return emptyList()
        var ids = IntArray(32)
        var n = 0
        for (form in fitting(forms, longestTermKey)) {
            val key = form.toByteArray(Charsets.UTF_8)
            if (!mayHave(keyFilter, key)) continue
            if (!cursor.find(key)) continue
            val r = cursor.value()
            var id = 0
            repeat(r.varint()) {
                id += r.varint()
                if (n == ids.size) ids = ids.copyOf(n * 2)
                ids[n++] = id
            }
        }
        ids.sort(0, n)
        var unique = 0
        for (i in 0 until n) if (i == 0 || ids[i] != ids[i - 1]) ids[unique++] = ids[i]
        return inOrderTheyCame(ids, unique).map { term(it, dictionary, priority) }
    }

    fun findTermsBySequence(sequences: Collection<Long>, dictionary: String, priority: Int): List<TermEntry> {
        val out = ArrayList<TermEntry>()
        var lo = 0
        for (sequence in sequences.toSortedSet()) {
            // The first with [sequence] or more: the terms are sorted by sequence
            var hi = termCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sequence(mid) < sequence) lo = mid + 1 else hi = mid
            }
            while (lo < termCount && sequence(lo) == sequence) {
                out += term(lo, dictionary, priority)
                lo++
            }
        }
        return out
    }

    fun findTermsByAffix(affix: String, suffix: Boolean, limit: Int, dictionary: String, priority: Int): List<Pair<TermEntry, Int>> {
        val keys = keys ?: return emptyList()
        val table = if (suffix) reverseKeys() ?: return emptyList() else keys
        val from = (if (suffix) reversed(affix) else affix).toByteArray(Charsets.UTF_8)
        // Each matching key's terms as (length, term), so that sorting puts each term's shortest first
        var pairs = LongArray(1024)
        var n = 0
        val c = table.cursor()
        var more = c.seekAtLeast(from)
        while (more && c.startsWith(from)) {
            val length = c.codePoints().toLong() shl 32
            val r = c.value()
            var id = 0
            repeat(r.varint()) {
                id += r.varint()
                if (n == pairs.size) pairs = pairs.copyOf(n * 2)
                pairs[n++] = length or id.toLong()
            }
            more = c.next()
        }
        pairs.sort(0, n)
        // Each term once, with its shortest; then the shortest first, in the order they came
        val seen = java.util.BitSet(termCount)
        val ids = IntArray(n)
        val lengths = IntArray(n)
        var unique = 0
        for (p in 0 until n) {
            val id = (pairs[p] and 0xffffffffL).toInt()
            if (seen.get(id)) continue
            seen.set(id)
            ids[unique] = id
            lengths[unique++] = (pairs[p] ushr 32).toInt()
        }
        // Only those as short as the last that makes the cut can be in it
        if (unique > limit) {
            var end = limit
            while (end < unique && lengths[end] == lengths[limit - 1]) end++
            unique = end
        }
        val places = LongArray(unique) { glossaryPlace(ids[it]) }
        val order = sortedIndices(unique) { a, b -> if (lengths[a] != lengths[b]) lengths[a] - lengths[b] else places[a].compareTo(places[b]) }
        return order.take(limit).map { term(ids[it], dictionary, priority) to lengths[it] }
    }

    /**
     * Up to [limit] keys that start with [affix] (end with it, if [suffix]: only once
     * [buildReverseKeys] has made their table) and that [accepts], in their order.
     */
    fun findKeys(affix: String, suffix: Boolean, limit: Int, accepts: (String) -> Boolean): List<String> {
        val keys = keys ?: return emptyList()
        val table = if (suffix) reverseKeys() ?: return emptyList() else keys
        val from = (if (suffix) reversed(affix) else affix).toByteArray(Charsets.UTF_8)
        val out = ArrayList<String>()
        val c = table.cursor()
        var more = c.seekAtLeast(from)
        while (more && c.startsWith(from) && out.size < limit) {
            val key = String(c.key, 0, c.keyLength, Charsets.UTF_8).let { if (suffix) reversed(it) else it }
            if (accepts(key)) out += key
            more = c.next()
        }
        return out
    }

    // ---- Glossaries ----------------------------------------------------------------------------

    /**
     * A glossary block inflated as far as needed: its bytes (as long as the whole block), where
     * each glossary starts (and the last ends), and how much of it is inflated.
     */
    private class Block(val bytes: ByteArray, val starts: IntArray, val inflated: Int) {
        fun has(entry: Int) = starts[entry + 1] <= inflated
    }

    /** Inflated glossary blocks, the most recently used last */
    private val blockCache = object : LinkedHashMap<Int, Block>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Block>) = size > BLOCK_CACHE
    }

    /** Reused for every block: making one and setting its dictionary costs more than inflating a block */
    private val inflater = Inflater(true)
    private var compressed = ByteArray(BLOCK_SIZE)
    private val header = ByteArray(HEADER_INFLATE)

    /** Block [index], inflated at least as far as its glossary [entry]; the caller holds [blockCache]'s lock */
    private fun block(index: Int, entry: Int): Block {
        blockCache[index]?.let { if (it.has(entry)) return it }
        val offset = blockOffsets[index]
        val length = (blockOffsets[index + 1] - offset).toInt()
        if (compressed.size < length) compressed = ByteArray(length)
        val d = data
        if (d != null) d.duplicate().apply { position(offset.toInt()) }.get(compressed, 0, length) else readFully(channel, ByteBuffer.wrap(compressed, 0, length), dataStart + offset)
        inflater.reset()
        glossaryDictionary?.let(inflater::setDictionary)
        inflater.setInput(compressed, 0, length)

        fun inflate(into: ByteArray, from: Int, until: Int): Int {
            var n = from
            while (n < until && !inflater.finished()) {
                val got = inflater.inflate(into, n, until - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Broken glossary block $index")
                n += got
            }
            return n
        }
        // The glossaries' lengths come first: from them, how long the block is
        var n = inflate(header, 0, header.size)
        val r = Reader(ByteBuffer.wrap(header, 0, n), 0)
        val count = r.varint()
        val starts = IntArray(count + 1)
        val lengths = IntArray(count) { r.varint() }
        check(r.position <= n) { "Glossary block $index has a long header" }
        starts[0] = r.position
        for (k in 0 until count) starts[k + 1] = starts[k] + lengths[k]
        val bytes = ByteArray(starts[count])
        System.arraycopy(header, 0, bytes, 0, minOf(n, bytes.size))
        // Then only as far as the glossary asked for
        n = inflate(bytes, minOf(n, bytes.size), starts[entry + 1])
        return Block(bytes, starts, n).also { blockCache[index] = it }
    }

    fun glossaries(ids: Collection<Long>): Map<Long, String> {
        val out = HashMap<Long, String>(ids.size * 2)
        synchronized(blockCache) {
            check(!closed) { "${file.name} is closed" }
            for (id in ids) {
                val place = glossaryPlace(id.toInt())
                val k = (place and 0xffffffffL).toInt()
                val block = block((place ushr 32).toInt(), k)
                out[id] = String(block.bytes, block.starts[k], block.starts[k + 1] - block.starts[k], Charsets.UTF_8)
            }
        }
        return out
    }

    // ---- Meta, kanji, tags, images -------------------------------------------------------------

    private fun meta(cursor: SortedTable.Cursor?, filter: BloomFilter?, key: String, dictionary: String, priority: Int, into: MutableList<TermMeta>) {
        if (cursor == null) return
        val bytes = key.toByteArray(Charsets.UTF_8)
        if (!mayHave(filter, bytes)) return
        if (!cursor.find(bytes)) return
        val r = cursor.value()
        repeat(r.varint()) { into += MetaCodec.decode(key, strings[r.varint()], r, dictionary, priority) }
    }

    fun findTermMeta(expressions: Collection<String>, dictionary: String, priority: Int): List<TermMeta> {
        val out = ArrayList<TermMeta>()
        val cursor = termMeta?.cursor()
        for (expression in fitting(expressions, longestMetaKey)) meta(cursor, metaFilter, expression, dictionary, priority, out)
        return out
    }

    fun findKanjiMeta(character: String, dictionary: String, priority: Int): List<TermMeta> =
        ArrayList<TermMeta>().also { meta(kanjiMeta?.cursor(), null, character, dictionary, priority, it) }

    fun findKanji(character: String, dictionary: String): List<KanjiEntry> {
        val cursor = kanji?.cursor() ?: return emptyList()
        if (!cursor.find(character.toByteArray(Charsets.UTF_8))) return emptyList()
        val r = cursor.value()
        return List(r.varint()) {
            KanjiEntry(character, r.string(), r.string(), r.string(), r.string().split('\n').filter { it.isNotEmpty() }, dictionary, stats = r.string())
        }
    }

    fun findTags(names: Collection<String>, dictionary: String): List<TagInfo> =
        names.distinct().flatMap { tags[it].orEmpty() }.map { it.copy(dictionary = dictionary) }

    fun findMedia(path: String): DictionaryMedia? {
        val cursor = media?.cursor() ?: return null
        if (!cursor.find(path.toByteArray(Charsets.UTF_8))) return null
        val r = cursor.value()
        val type = r.string()
        val width = r.varint()
        val height = r.varint()
        val offset = r.varlong()
        val length = r.varint()
        return DictionaryMedia(path, type, width, height) { readBytes(channel, dataStart + offset, length) }
    }

    // ---- Search by meaning ---------------------------------------------------------------------

    /** Whether its terms can be found by the words of their definitions ([findTermsByMeaning]) */
    fun hasMeanings(): Boolean = meanings != null

    /** The language its definitions are in, as its index.json says (targetLanguage), if it does */
    fun targetLanguage(): String? =
        facts(info).index?.let { runCatching { JSONObject(it).optString("targetLanguage") }.getOrNull() }?.takeIf { it.isNotBlank() }

    /** How many terms' definitions have [word] ([Meanings.words]), for telling common spellings from mistyped ones */
    fun meaningWordCount(word: String): Int {
        val cursor = meanings?.cursor() ?: return 0
        return if (cursor.find(word.toByteArray(Charsets.UTF_8))) cursor.value().varint() else 0
    }

    /** Whether a term's expression or reading is [form] */
    fun hasForm(form: String): Boolean {
        val keys = keys ?: return false
        if (form.codePointCount(0, form.length) > longestTermKey) return false
        val bytes = form.toByteArray(Charsets.UTF_8)
        return mayHave(keyFilter, bytes) && keys.cursor().find(bytes)
    }

    /**
     * Up to [limit] terms whose definitions have all of [words] ([Meanings.words]), the last as
     * the start of a word too if [prefix] and it's long enough; each with how well they fit,
     * lower fitting better: the definition's words' count and place ([Meanings]), what was
     * the start of a word after what was a word. The best first, then in the order they came.
     */
    fun findTermsByMeaning(words: List<String>, prefix: Boolean, limit: Int, dictionary: String, priority: Int): List<Pair<TermEntry, Int>> {
        val table = meanings ?: return emptyList()
        if (words.isEmpty()) return emptyList()
        val c = table.cursor()
        // The terms with every word so far, by number, each as well as its worst fitting word fits
        var ids = IntArray(0)
        var fits = IntArray(0)
        var n = 0
        for ((i, word) in words.withIndex()) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            // This word's terms as (term, fit), each its best fit
            var pairs = LongArray(64)
            var m = 0
            fun collect(extra: Int) {
                val r = c.value()
                var id = 0
                repeat(r.varint()) {
                    id += r.varint()
                    if (m == pairs.size) pairs = pairs.copyOf(m * 2)
                    pairs[m++] = (id.toLong() shl 16) or (r.byte() + extra).toLong()
                }
            }
            if (prefix && i == words.lastIndex && bytes.size >= SHORTEST_PREFIX) {
                var more = c.seekAtLeast(bytes)
                var keys = 0
                while (more && c.startsWith(bytes) && keys++ < MOST_PREFIXED) {
                    collect(if (c.keyLength == bytes.size) 0 else PREFIXED)
                    more = c.next()
                }
                pairs.sort(0, m)
            } else if (c.find(bytes)) {
                collect(0) // in their order already, each once
            }
            var k = 0
            for (p in 0 until m) if (k == 0 || pairs[k - 1] ushr 16 != pairs[p] ushr 16) pairs[k++] = pairs[p]
            if (i == 0) {
                ids = IntArray(k) { (pairs[it] ushr 16).toInt() }
                fits = IntArray(k) { (pairs[it] and 0xffff).toInt() }
                n = k
            } else {
                // Both sorted by term: the terms of both
                var a = 0
                var b = 0
                var both = 0
                while (a < n && b < k) {
                    val term = (pairs[b] ushr 16).toInt()
                    when {
                        ids[a] < term -> a++
                        ids[a] > term -> b++
                        else -> {
                            ids[both] = term
                            fits[both++] = maxOf(fits[a], (pairs[b] and 0xffff).toInt())
                            a++
                            b++
                        }
                    }
                }
                n = both
            }
            if (n == 0) return emptyList()
        }
        // The best fitting: those fitting as well as the [limit]th or better, then in the order they came
        val counts = IntArray(FITS)
        for (j in 0 until n) counts[minOf(fits[j], FITS - 1)]++
        var worst = FITS - 1
        var taken = 0
        for (f in 0 until FITS) {
            taken += counts[f]
            if (taken >= limit) {
                worst = f
                break
            }
        }
        val kept = (0 until n).filter { fits[it] <= worst }
        val places = LongArray(kept.size) { glossaryPlace(ids[kept[it]]) }
        val order = sortedIndices(kept.size) { a, b ->
            val fa = fits[kept[a]]
            val fb = fits[kept[b]]
            if (fa != fb) fa - fb else places[a].compareTo(places[b])
        }
        return order.take(limit).map { term(ids[kept[it]], dictionary, priority) to fits[kept[it]] }
    }

    // ---- Suffix search -------------------------------------------------------------------------

    private val reverseFile = File(file.path + ".rev")

    @Volatile
    private var reverse: Pair<RandomAccessFile, SortedTable>? = null

    private fun reverseKeys(): SortedTable? = reverse?.second ?: synchronized(this) {
        reverse?.second ?: if (reverseFile.exists()) {
            val f = RandomAccessFile(reverseFile, "r")
            val (offset, length) = readHeader(f.channel, reverseFile.name).getValue(REVERSE_KEYS)
            val table = SortedTable(map(f.channel, offset, length))
            reverse = f to table
            table
        } else {
            null
        }
    }

    fun hasReverseKeys(): Boolean = reverseFile.exists()

    /** Writes [reverseFile]: every key spelled backwards, sorted, with its terms. */
    fun buildReverseKeys(): Unit = synchronized(this) {
        if (hasReverseKeys()) return
        val keys = keys ?: return
        val writing = File(reverseFile.path + ".writing")
        ExternalSorter(File(reverseFile.path + ".sort")).use { sorter ->
            val c = keys.cursor()
            var more = c.seek(0)
            while (more) {
                val reversed = reversed(String(c.key, 0, c.keyLength, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                val terms = c.valueBytes()
                sorter.add(reversed, 0, reversed.size, c.index.toLong(), terms, 0, terms.size)
                more = c.next()
            }
            FileBuilder(writing).use { b ->
                TableWriter(File(writing.path + ".table")).use { table ->
                    sorter.forEachSorted { e -> table.add(e.key, 0, e.keyLength, e.value, 0, e.valueLength) }
                    table.finish(b, REVERSE_KEYS)
                }
                b.finish()
            }
        }
        if (!writing.renameTo(reverseFile)) throw IOException("Couldn't move ${reverseFile.name} into place")
    }

    // ---- Export --------------------------------------------------------------------------------

    /** How long [writePortable] writes */
    fun portableSize(): Long = HEADER_SIZE + sections.filterKeys { it != KEYS && it != KEY_FILTER }.values.sumOf { it[1] }

    /** Writes this dictionary for export to [out]: the file without what [restore] can make again (the keys and their filter) */
    fun writePortable(out: OutputStream) {
        val kept = sections.filterKeys { it != KEYS && it != KEY_FILTER }.entries.sortedBy { it.value[0] }
        val header = Bytes(HEADER_SIZE).bytes(MAGIC).int(FORMAT).int(kept.size + 1)
        var at = HEADER_SIZE.toLong()
        for ((id, range) in kept) {
            header.int(id).long(at).long(range[1])
            at += range[1]
        }
        header.int(PORTABLE).long(at).long(0)
        out.write(header.buffer, 0, header.size)
        out.write(ByteArray(HEADER_SIZE - header.size))
        val buffer = ByteBuffer.allocate(1 shl 16)
        for ((_, range) in kept) copy(channel, range[0], range[1], buffer) { out.write(buffer.array(), 0, it) }
    }

    /** Once closed (its dictionary deleted), lookups still running get an IllegalStateException */
    private var closed = false

    override fun close() {
        synchronized(blockCache) {
            closed = true
            inflater.end()
        }
        reverse?.first?.close()
        raf.close()
    }

    /**
     * A section of sorted keys, each with a value. Its count, the restart interval, the number of
     * restarts and where each starts; then the entries: how many bytes the key shares with the one
     * before (none at a restart), the rest of it, and the value, each after its length.
     */
    private class SortedTable(private val buffer: ByteBuffer) {
        val count = buffer.getInt(0)
        private val interval = buffer.getInt(4)
        private val restarts = buffer.getInt(8)
        private val entries = 12 + 4 * restarts

        private fun restart(r: Int) = entries + buffer.getInt(12 + 4 * r)

        /** The first key of restart [r] against [key], as unsigned bytes */
        private fun compareRestart(r: Int, key: ByteArray): Int {
            val reader = Reader(buffer, restart(r))
            reader.varint() // shares nothing
            val length = reader.varint()
            val start = reader.position
            for (k in 0 until minOf(length, key.size)) {
                val d = (buffer.get(start + k).toInt() and 0xff) - (key[k].toInt() and 0xff)
                if (d != 0) return d
            }
            return length - key.size
        }

        /** The last restart whose key is [key] or before it, or -1 */
        private fun restartFor(key: ByteArray): Int {
            var lo = -1
            var hi = restarts - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (compareRestart(mid, key) <= 0) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun cursor() = Cursor()

        /** Goes through the entries; [key] holds the current one's key, of [keyLength]. */
        inner class Cursor {
            var index = -1
                private set
            var key = ByteArray(64)
                private set
            var keyLength = 0
                private set
            private var position = 0
            private var valuePosition = 0

            /** To the start of restart [r], before its first entry */
            private fun toRestart(r: Int) {
                index = r * interval - 1
                position = restart(r)
            }

            fun next(): Boolean {
                if (index + 1 >= count) {
                    index = count
                    return false
                }
                index++
                if (index % interval == 0) position = restart(index / interval)
                val r = Reader(buffer, position)
                val shared = r.varint()
                val rest = r.varint()
                keyLength = shared + rest
                if (key.size < keyLength) key = key.copyOf(maxOf(keyLength, key.size * 2))
                for (k in 0 until rest) key[shared + k] = buffer.get(r.position + k)
                r.position += rest
                val valueLength = r.varint()
                valuePosition = r.position
                position = valuePosition + valueLength
                return true
            }

            /** To entry [i] */
            fun seek(i: Int): Boolean {
                if (i !in 0 until count) return false
                toRestart(i / interval)
                while (index < i) next()
                return true
            }

            private fun compare(other: ByteArray): Int {
                for (k in 0 until minOf(keyLength, other.size)) {
                    val d = (key[k].toInt() and 0xff) - (other[k].toInt() and 0xff)
                    if (d != 0) return d
                }
                return keyLength - other.size
            }

            /** To [target]'s entry, if there is one */
            fun find(target: ByteArray): Boolean {
                val r = restartFor(target)
                if (r < 0) return false
                toRestart(r)
                val end = minOf(count, (r + 1) * interval)
                while (index + 1 < end) {
                    next()
                    val c = compare(target)
                    if (c == 0) return true
                    if (c > 0) return false
                }
                return false
            }

            /** To the first entry at or after [target]; false if there's none */
            fun seekAtLeast(target: ByteArray): Boolean {
                toRestart(maxOf(0, restartFor(target)))
                while (next()) if (compare(target) >= 0) return true
                return false
            }

            fun startsWith(prefix: ByteArray): Boolean {
                if (keyLength < prefix.size) return false
                for (k in prefix.indices) if (key[k] != prefix[k]) return false
                return true
            }

            /** The key's length in code points */
            fun codePoints(): Int {
                var n = 0
                for (k in 0 until keyLength) if (key[k].toInt() and 0xc0 != 0x80) n++
                return n
            }

            fun value() = Reader(buffer, valuePosition)

            fun valueBytes() = ByteArray(position - valuePosition) { buffer.get(valuePosition + it) }
        }
    }

    /** A Bloom filter section: how many hashes, how many bits, then the bits */
    private class BloomFilter(private val buffer: ByteBuffer) {
        private val hashes = buffer.getInt(0)
        private val bits = buffer.getLong(4)

        fun mightContain(key: ByteArray): Boolean {
            val h = hash(key, 0, key.size)
            for (i in 0 until hashes) {
                val bit = bloomBit(h, i, bits)
                if (buffer.getLong(12 + 8 * (bit ushr 6).toInt()) and (1L shl (bit and 63).toInt()) == 0L) return false
            }
            return true
        }
    }

    /** Reads varints and strings at [position] of [buffer], without moving the buffer's own position. */
    private class Reader(private val buffer: ByteBuffer, var position: Int) {
        fun byte(): Int = buffer.get(position++).toInt() and 0xff

        fun varint(): Int = varlong().toInt()

        fun varlong(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buffer.get(position++).toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun zigzag(): Long = varlong().let { (it ushr 1) xor -(it and 1) }

        fun string(): String {
            val length = varint()
            val bytes = ByteArray(length)
            buffer.duplicate().apply { position(this@Reader.position) }.get(bytes)
            position += length
            return String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * Term meta values kept as what they are where Yomitan's shapes allow it: a frequency as a
     * number, or a value and the text to show, either of them for a reading; a pitch accent as
     * its reading and positions, with its nasal and devoiced morae. Each row is checked to read
     * back as the same JSON value; one that doesn't, or of another shape, is kept as its JSON.
     */
    private object MetaCodec {
        private const val RAW = 0
        private const val NUMBER = 1
        private const val VALUE_AND_DISPLAY = 2
        private const val PITCH = 3

        /** A frequency inside {reading, frequency} */
        private const val FOR_READING = 0x10

        /** The reading is the expression, so it isn't written */
        private const val READING_IS_EXPRESSION = 0x20

        /** [data]'s value as stored: typed if it reads back the same, else its JSON */
        fun encode(expression: String, mode: String, data: String): ByteArray {
            val typed = try {
                typed(expression, mode, JSONArray(data))
            } catch (e: JSONException) {
                null
            }
            if (typed != null) {
                val back = decode(expression, mode, Reader(ByteBuffer.wrap(typed), 0), "", 0).data
                if (same(JSONArray(back), JSONArray(data))) return typed
            }
            return Bytes().byte(RAW).string(data).toByteArray()
        }

        private fun int(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }

        private fun ints(value: Any?): List<Int>? =
            (value as? JSONArray)?.let { a -> List(a.length()) { int(a.get(it)) ?: return null } }

        private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

        private fun Bytes.reading(tag: Int, reading: String, expression: String): Bytes =
            if (reading == expression) byte(tag or READING_IS_EXPRESSION) else byte(tag).string(reading)

        private fun typed(expression: String, mode: String, array: JSONArray): ByteArray? {
            if (array.length() != 1) return null
            var value = array.get(0)
            when (mode) {
                "freq" -> {
                    var reading: String? = null
                    if (value is JSONObject && keys(value) == setOf("reading", "frequency")) {
                        reading = value.get("reading") as? String ?: return null
                        value = value.get("frequency")
                    }
                    val wrapped = if (reading != null) FOR_READING else 0
                    val number = int(value)
                    if (number != null) {
                        val out = if (reading != null) Bytes().reading(NUMBER or wrapped, reading, expression) else Bytes().byte(NUMBER)
                        return out.zigzag(number.toLong()).toByteArray()
                    }
                    if (value is JSONObject && keys(value) == setOf("value", "displayValue")) {
                        val v = int(value.get("value")) ?: return null
                        val display = value.get("displayValue") as? String ?: return null
                        val out = if (reading != null) Bytes().reading(VALUE_AND_DISPLAY or wrapped, reading, expression) else Bytes().byte(VALUE_AND_DISPLAY)
                        return out.zigzag(v.toLong()).string(display).toByteArray()
                    }
                    return null
                }
                "pitch" -> {
                    if (value !is JSONObject || keys(value) != setOf("reading", "pitches")) return null
                    val reading = value.get("reading") as? String ?: return null
                    val pitches = value.get("pitches") as? JSONArray ?: return null
                    val out = Bytes().reading(PITCH, reading, expression).varint(pitches.length())
                    for (i in 0 until pitches.length()) {
                        val pitch = pitches.get(i) as? JSONObject ?: return null
                        if (!setOf("position", "nasal", "devoice").containsAll(keys(pitch))) return null
                        val position = int(pitch.opt("position")) ?: return null
                        val nasal = if (pitch.has("nasal")) ints(pitch.get("nasal")) ?: return null else null
                        val devoice = if (pitch.has("devoice")) ints(pitch.get("devoice")) ?: return null else null
                        out.byte((if (nasal != null) 1 else 0) or (if (devoice != null) 2 else 0)).zigzag(position.toLong())
                        for (list in listOfNotNull(nasal, devoice)) {
                            out.varint(list.size)
                            for (p in list) out.zigzag(p.toLong())
                        }
                    }
                    return out.toByteArray()
                }
            }
            return null
        }

        /** The row at [r]: its JSON, and what it is if it's typed */
        fun decode(expression: String, mode: String, r: Reader, dictionary: String, priority: Int): TermMeta {
            val tag = r.byte()
            if (tag == RAW) return TermMeta(expression, mode, r.string(), dictionary, priority)
            val kind = tag and 0x0f
            val reading = when {
                tag and READING_IS_EXPRESSION != 0 -> expression
                tag and FOR_READING != 0 || kind == PITCH -> r.string()
                else -> null
            }
            val json = StringBuilder("[")
            val parsed: MetaData
            when (kind) {
                NUMBER, VALUE_AND_DISPLAY -> {
                    val value = r.zigzag().toInt()
                    val display = if (kind == VALUE_AND_DISPLAY) r.string() else null
                    val inner = if (display == null) "$value" else "{\"value\":$value,\"displayValue\":${JSONObject.quote(display)}}"
                    if (reading != null) json.append("{\"reading\":").append(JSONObject.quote(reading)).append(",\"frequency\":").append(inner).append('}') else json.append(inner)
                    parsed = MetaData.Frequency(reading, value, display)
                }
                PITCH -> {
                    json.append("{\"reading\":").append(JSONObject.quote(reading)).append(",\"pitches\":[")
                    val positions = ArrayList<Int>()
                    repeat(r.varint()) { i ->
                        val flags = r.byte()
                        val position = r.zigzag().toInt()
                        positions += position
                        if (i > 0) json.append(',')
                        json.append("{\"position\":").append(position)
                        for ((bit, name) in listOf(1 to "nasal", 2 to "devoice")) {
                            if (flags and bit == 0) continue
                            json.append(",\"").append(name).append("\":[")
                            repeat(r.varint()) { k -> json.append(if (k > 0) "," else "").append(r.zigzag()) }
                            json.append(']')
                        }
                        json.append('}')
                    }
                    json.append("]}")
                    parsed = MetaData.Pitch(reading!!, positions)
                }
                else -> throw IOException("Unknown meta row type $tag")
            }
            return TermMeta(expression, mode, json.append(']').toString(), dictionary, priority, parsed)
        }

        /** Whether two JSON values are the same: the same keys and values, numbers of the same kind */
        private fun same(a: Any?, b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> keys(a) == keys(b) && keys(a).all { same(a.get(it), b.get(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
            a is Number && b is Number -> if (int(a) != null || int(b) != null) int(a) == int(b) else a.toString() == b.toString()
            else -> a == b
        }
    }

    // ---- Writing -------------------------------------------------------------------------------

    /** Writes a file of sections: the header's room first, then the sections, then the header. */
    internal class FileBuilder(private val file: File) : Closeable {
        private val out = run {
            file.delete()
            BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        }
        var position = 0L
            private set
        private val sections = ArrayList<LongArray>()
        private var finished = false

        init {
            write(ByteArray(HEADER_SIZE))
        }

        fun write(bytes: ByteArray, length: Int = bytes.size) = write(bytes, 0, length)

        fun write(bytes: ByteArray, start: Int, length: Int) {
            out.write(bytes, start, length)
            position += length
        }

        private val scratch = Bytes(16)

        fun int(v: Int) = scratch(scratch.apply { size = 0 }.int(v))
        fun long(v: Long) = scratch(scratch.apply { size = 0 }.long(v))
        private fun scratch(bytes: Bytes) = write(bytes.buffer, bytes.size)

        /** Section [id] is what [body] writes */
        inline fun section(id: Int, body: () -> Unit) {
            val start = position
            body()
            add(id, start, position - start)
        }

        fun section(id: Int, bytes: ByteArray) = section(id) { write(bytes) }

        /** Section [id] is [length] bytes at [offset], already written */
        fun add(id: Int, offset: Long, length: Long) {
            sections += longArrayOf(id.toLong(), offset, length)
        }

        /** Copies [length] bytes at [offset] of [from] here */
        fun copy(from: FileChannel, offset: Long, length: Long) {
            val buffer = ByteBuffer.allocate(1 shl 16)
            copy(from, offset, length, buffer) { write(buffer.array(), it) }
        }

        fun copy(from: File) = FileInputStream(from).use { input -> copy(input.channel, 0, from.length()) }

        /** A Bloom filter section of the keys whose [hashes] are the first [n] */
        fun bloomFilter(id: Int, hashes: LongArray, n: Int) {
            val bits = maxOf(64L, n.toLong() * BLOOM_BITS)
            val words = LongArray(((bits + 63) / 64).toInt())
            for (k in 0 until n) for (i in 0 until BLOOM_HASHES) {
                val bit = bloomBit(hashes[k], i, bits)
                words[(bit ushr 6).toInt()] = words[(bit ushr 6).toInt()] or (1L shl (bit and 63).toInt())
            }
            section(id) {
                int(BLOOM_HASHES)
                long(bits)
                val chunk = Bytes(8 * 1024 + 8)
                for (w in words) {
                    chunk.long(w)
                    if (chunk.size >= 8 * 1024) {
                        write(chunk.buffer, chunk.size)
                        chunk.size = 0
                    }
                }
                write(chunk.buffer, chunk.size)
            }
        }

        /** Writes the header and closes the file */
        fun finish() {
            out.close()
            check(sections.size <= MAX_SECTIONS)
            RandomAccessFile(file, "rw").use { raf ->
                val header = Bytes().bytes(MAGIC).int(FORMAT).int(sections.size)
                for ((id, offset, length) in sections) header.int(id.toInt()).long(offset).long(length)
                raf.write(header.buffer, 0, header.size)
            }
            finished = true
        }

        /** Closes the file; unless [finish]ed, deletes it. */
        override fun close() {
            if (finished) return
            runCatching { out.close() }
            file.delete()
        }
    }

    /** Writes a [SortedTable] as its entries come, in order: to [temp], then into a [FileBuilder] on [finish]. */
    internal class TableWriter(private val temp: File) : Closeable {
        private val out = BufferedOutputStream(FileOutputStream(temp), 1 shl 16)
        private var size = 0L
        var count = 0
            private set
        private var restarts = IntArray(256)
        private var restartCount = 0
        private var previous = ByteArray(64)
        private var previousLength = 0
        private val scratch = Bytes(16)

        fun add(key: ByteArray, keyStart: Int, keyLength: Int, value: ByteArray, valueStart: Int, valueLength: Int) {
            var shared = 0
            if (count % INTERVAL == 0) {
                check(size <= Int.MAX_VALUE) { "A table over 2 GB" }
                if (restartCount == restarts.size) restarts = restarts.copyOf(restartCount * 2)
                restarts[restartCount++] = size.toInt()
            } else {
                while (shared < minOf(keyLength, previousLength) && key[keyStart + shared] == previous[shared]) shared++
            }
            scratch.size = 0
            scratch.varint(shared).varint(keyLength - shared)
            out.write(scratch.buffer, 0, scratch.size)
            out.write(key, keyStart + shared, keyLength - shared)
            size += scratch.size + keyLength - shared
            scratch.size = 0
            scratch.varint(valueLength)
            out.write(scratch.buffer, 0, scratch.size)
            out.write(value, valueStart, valueLength)
            size += scratch.size + valueLength
            if (previous.size < keyLength) previous = ByteArray(maxOf(keyLength, previous.size * 2))
            System.arraycopy(key, keyStart, previous, 0, keyLength)
            previousLength = keyLength
            count++
        }

        /** Writes the table into [builder] as section [id] */
        fun finish(builder: FileBuilder, id: Int) {
            out.close()
            builder.section(id) {
                builder.int(count)
                builder.int(INTERVAL)
                builder.int(restartCount)
                for (i in 0 until restartCount) builder.int(restarts[i])
                builder.copy(temp)
            }
            temp.delete()
        }

        override fun close() {
            runCatching { out.close() }
            temp.delete()
        }
    }

    /**
     * Sorts entries (a key, a number that orders the same keys, a value) by key, then number: in
     * memory up to [limit] bytes, and beyond that in sorted runs in files named after [base],
     * merged as [forEachSorted] reads them.
     */
    internal class ExternalSorter(private val base: File, private val limit: Int = SORT_MEMORY) : Closeable {
        private val heap = Bytes(1 shl 16)
        private var offsets = IntArray(1024)
        private var count = 0
        private val runs = ArrayList<File>()

        /** An entry as [forEachSorted] gives it; its arrays are reused for the next one. */
        class Entry {
            var key = ByteArray(64)
            var keyLength = 0
            var number = 0L
            var value = ByteArray(64)
            var valueLength = 0
        }

        fun add(key: ByteArray, keyStart: Int, keyLength: Int, number: Long, value: ByteArray? = null, valueStart: Int = 0, valueLength: Int = 0) {
            if (count == offsets.size) offsets = offsets.copyOf(count * 2)
            offsets[count++] = heap.size
            heap.varint(keyLength).bytes(key, keyStart, keyLength).varlong(number).varint(valueLength)
            if (value != null) heap.bytes(value, valueStart, valueLength)
            if (heap.size >= limit || count >= maxOf(1, limit / 16)) spill()
        }

        /** The order of the entries in memory */
        private fun sorted(): IntArray {
            val keyStart = IntArray(count)
            val keyLength = IntArray(count)
            val number = LongArray(count)
            val r = Reader(ByteBuffer.wrap(heap.buffer, 0, heap.size), 0)
            for (i in 0 until count) {
                r.position = offsets[i]
                keyLength[i] = r.varint()
                keyStart[i] = r.position
                r.position += keyLength[i]
                number[i] = r.varlong()
            }
            val b = heap.buffer
            return sortedIndices(count) { x, y ->
                val c = compareBytes(b, keyStart[x], keyLength[x], b, keyStart[y], keyLength[y])
                if (c != 0) c else number[x].compareTo(number[y])
            }
        }

        private fun spill() {
            if (count == 0) return
            val run = File("${base.path}.${runs.size}")
            runs += run
            BufferedOutputStream(FileOutputStream(run), 1 shl 16).use { out ->
                for (i in sorted()) {
                    val start = offsets[i]
                    val end = if (i + 1 < count) offsets[i + 1] else heap.size
                    out.write(heap.buffer, start, end - start)
                }
            }
            heap.size = 0
            count = 0
        }

        /** Calls [each] with every entry, in order */
        fun forEachSorted(each: (Entry) -> Unit) {
            val e = Entry()
            if (runs.isEmpty()) {
                val r = Reader(ByteBuffer.wrap(heap.buffer, 0, heap.size), 0)
                for (i in sorted()) {
                    r.position = offsets[i]
                    e.keyLength = r.varint()
                    if (e.key.size < e.keyLength) e.key = ByteArray(e.keyLength * 2)
                    System.arraycopy(heap.buffer, r.position, e.key, 0, e.keyLength)
                    r.position += e.keyLength
                    e.number = r.varlong()
                    e.valueLength = r.varint()
                    if (e.value.size < e.valueLength) e.value = ByteArray(e.valueLength * 2)
                    System.arraycopy(heap.buffer, r.position, e.value, 0, e.valueLength)
                    each(e)
                }
                return
            }
            spill()
            val readers = runs.map { RunReader(it) }
            try {
                val queue = PriorityQueue<RunReader> { x, y ->
                    val c = compareBytes(x.entry.key, 0, x.entry.keyLength, y.entry.key, 0, y.entry.keyLength)
                    if (c != 0) c else x.entry.number.compareTo(y.entry.number)
                }
                for (r in readers) if (r.next()) queue += r
                while (queue.isNotEmpty()) {
                    val r = queue.poll()!!
                    each(r.entry)
                    if (r.next()) queue += r
                }
            } finally {
                readers.forEach { it.close() }
            }
        }

        private class RunReader(file: File) : Closeable {
            private val input = BufferedInputStream(FileInputStream(file), 1 shl 16)
            val entry = Entry()

            private fun varlong(first: Int = input.read()): Long {
                var b = first
                var result = 0L
                var shift = 0
                while (true) {
                    if (b < 0) throw EOFException()
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    b = input.read()
                }
            }

            private fun readFully(into: ByteArray, length: Int) {
                var n = 0
                while (n < length) {
                    val got = input.read(into, n, length - n)
                    if (got < 0) throw EOFException()
                    n += got
                }
            }

            fun next(): Boolean {
                val first = input.read()
                if (first < 0) return false
                entry.keyLength = varlong(first).toInt()
                if (entry.key.size < entry.keyLength) entry.key = ByteArray(entry.keyLength * 2)
                readFully(entry.key, entry.keyLength)
                entry.number = varlong()
                entry.valueLength = varlong().toInt()
                if (entry.value.size < entry.valueLength) entry.value = ByteArray(entry.valueLength * 2)
                readFully(entry.value, entry.valueLength)
                return true
            }

            override fun close() = input.close()
        }

        override fun close() {
            runs.forEach { it.delete() }
        }
    }

    /**
     * A term bank's terms with their glossaries deflated in blocks, which [Writer.terms] stores:
     * the rest of each row packed (expression, reading, tags, rules, score, sequence, term tags).
     */
    class PreparedTerms internal constructor(
        internal val packed: Bytes,
        internal val count: Int,
        internal val blocks: List<ByteArray>,
        /** How many of the rows each block has, in order */
        internal val blockRows: IntArray,
        /** For a dictionary searched by meaning: each row's definitions' words, each with how well it fits */
        internal val words: Bytes?,
        /** How many of the rows are forms of other words */
        internal val forms: Int = 0,
    )

    /**
     * A meta bank's rows encoded ([MetaCodec]), which [Writer.meta] stores: packed, each its key,
     * mode and value, since a bank can have a million rows.
     */
    class PreparedMeta internal constructor(
        internal val packed: Bytes,
        internal val count: Int,
        internal val frequencies: Int,
        internal val longestKey: Int,
        internal val ofKanji: Boolean,
    )

    /**
     * Writes a new dictionary file at [file]: the glossary blocks and images as they come, the
     * rest through files beside it until [finish], which sorts it and writes the tables.
     * [glossaryDictionary]: what glossary blocks start from ([trainDictionary]), if anything;
     * [blockSize]: about how much glossary text goes into a block; [sortMemory]: how much each
     * sort keeps in memory before it goes on on disk; [meanings]: index the words of the
     * definitions, for searching by meaning ([Meanings.inLatinLetters]).
     */
    class Writer(
        private val file: File,
        private val glossaryDictionary: ByteArray?,
        private val blockSize: Int = BLOCK_SIZE,
        sortMemory: Int = SORT_MEMORY,
        private val meanings: Boolean = false,
    ) : Closeable {
        private val builder = FileBuilder(file)
        private fun temp(name: String) = File(file.path + "." + name)

        private val keys = ExternalSorter(temp("keys"), sortMemory)
        private val termMeta = ExternalSorter(temp("meta"), sortMemory)
        private val kanjiMeta = ExternalSorter(temp("kanji-meta"), sortMemory)
        private val kanji = ExternalSorter(temp("kanji"), sortMemory)
        private val media = ExternalSorter(temp("media"), sortMemory)
        private val meaningWords = ExternalSorter(temp("meanings"), sortMemory)
        private val recordsFile = temp("records")
        private val records = BufferedOutputStream(FileOutputStream(recordsFile), 1 shl 16)
        private var recordsSize = 0L
        private val record = Bytes(256)

        private val stringIds = HashMap<String, Int>()
        private val stringList = ArrayList<String>()

        /** Per term as they come: where its record starts, and its sequence */
        private var recordOffsets = LongArray(1 shl 14)
        private var sequences = LongArray(1 shl 14)
        private var terms = 0

        /** Each glossary block's offset in the data section */
        private var blockOffsets = LongArray(1024)
        private var blocks = 0
        private var blockEnd = 0L

        private var metaRows = 0L
        private var kanjiMetaRows = 0
        private var kanjiRows = 0
        private var forms = 0
        private var longestTermKey = 0
        private var longestMetaKey = 0
        private val tags = ArrayList<TagInfo>()
        private val mediaPaths = HashSet<String>()
        private var frequencies = 0

        /** Where the data section is now, relative to its start */
        private val dataPosition get() = builder.position - HEADER_SIZE

        private fun string(text: String): Int = stringIds.getOrPut(text) { stringList += text; stringList.size - 1 }

        /** Deflates [rows]' glossaries; can run on any thread, while [terms] runs on the writing one. */
        fun prepareTerms(rows: List<TermEntry>): PreparedTerms {
            val blocks = ArrayList<ByteArray>()
            val blockRows = ArrayList<Int>()
            val pending = ArrayList<ByteArray>()
            var size = 0
            val deflater = Deflater(LEVEL, true)
            try {
                fun flush() {
                    if (pending.isEmpty()) return
                    val block = Bytes(size + 5 * pending.size + 5).varint(pending.size)
                    for (g in pending) block.varint(g.size)
                    for (g in pending) block.bytes(g)
                    deflater.reset()
                    glossaryDictionary?.let(deflater::setDictionary)
                    deflater.setInput(block.buffer, 0, block.size)
                    deflater.finish()
                    val compressed = Bytes(block.size / 4 + 64)
                    val chunk = ByteArray(1 shl 14)
                    while (!deflater.finished()) compressed.bytes(chunk, 0, deflater.deflate(chunk))
                    blocks += compressed.toByteArray()
                    blockRows += pending.size
                    pending.clear()
                    size = 0
                }
                for (row in rows) {
                    val glossary = row.glossary.toByteArray(Charsets.UTF_8)
                    pending += glossary
                    size += glossary.size
                    if (size >= blockSize) flush()
                }
                flush()
            } finally {
                deflater.end()
            }
            val packed = Bytes(rows.size * 48 + 64)
            var forms = 0
            for (row in rows) {
                if (Yomitan.hasForms(row.glossary)) forms++
                packed.string(row.expression).string(row.reading).string(row.definitionTags).string(row.rules)
                    .zigzag(row.score.toLong()).zigzag(row.sequence).string(row.termTags)
            }
            return PreparedTerms(packed, rows.size, blocks, blockRows.toIntArray(), if (meanings) meaningWords(rows) else null, forms)
        }

        /**
         * Each row's definitions' words, each with how well it fits the term: how many words its
         * gloss has (fewer fit better) and which gloss it's in, at most 15 of either, the best of
         * the glosses it's in.
         */
        private fun meaningWords(rows: List<TermEntry>): Bytes {
            val out = Bytes(rows.size * 32 + 64)
            val best = HashMap<String, Int>()
            for (row in rows) {
                best.clear()
                for ((i, gloss) in Meanings.glosses(row.glossary).withIndex()) {
                    val words = Meanings.words(gloss)
                    if (words.isEmpty()) continue
                    val fit = (minOf(words.size, 15) shl 4) or minOf(i, 15)
                    for (word in words) best.merge(word, fit) { a, b -> minOf(a, b) }
                }
                out.varint(best.size)
                for ((word, fit) in best) out.string(word).byte(fit)
            }
            return out
        }

        fun terms(rows: List<TermEntry>) = terms(prepareTerms(rows))

        fun terms(prepared: PreparedTerms) {
            forms += prepared.forms
            val firstBlock = blocks
            for (block in prepared.blocks) {
                if (blocks + 1 >= blockOffsets.size) blockOffsets = blockOffsets.copyOf(blockOffsets.size * 2)
                blockOffsets[blocks++] = dataPosition
                builder.write(block)
                blockEnd = dataPosition
            }
            var block = firstBlock - 1
            var inBlock = 0
            var left = 0
            val r = Reader(ByteBuffer.wrap(prepared.packed.buffer, 0, prepared.packed.size), 0)
            val words = prepared.words?.let { Reader(ByteBuffer.wrap(it.buffer, 0, it.size), 0) }
            val fit = ByteArray(1)
            repeat(prepared.count) {
                val row = TermEntry(
                    expression = r.string(), reading = r.string(), definitionTags = r.string(), rules = r.string(),
                    score = r.zigzag().toInt(), glossary = "", sequence = r.zigzag(), termTags = r.string(),
                )
                while (left == 0) {
                    block++
                    inBlock = 0
                    left = prepared.blockRows[block - firstBlock]
                }
                val expression = row.expression.toByteArray(Charsets.UTF_8)
                val reading = if (row.reading == row.expression) EMPTY else row.reading.toByteArray(Charsets.UTF_8)
                record.size = 0
                record.zigzag(row.sequence).varint(block).varint(inBlock)
                    .varint(expression.size).bytes(expression).varint(reading.size).bytes(reading)
                    .varint(string(row.definitionTags)).varint(string(row.rules)).zigzag(row.score.toLong()).varint(string(row.termTags))
                if (terms == recordOffsets.size) {
                    recordOffsets = recordOffsets.copyOf(terms * 2)
                    sequences = sequences.copyOf(terms * 2)
                }
                recordOffsets[terms] = recordsSize
                sequences[terms] = row.sequence
                records.write(record.buffer, 0, record.size)
                recordsSize += record.size
                keys.add(expression, 0, expression.size, terms.toLong())
                if (reading.isNotEmpty()) keys.add(reading, 0, reading.size, terms.toLong())
                if (words != null) {
                    repeat(words.varint()) {
                        val length = words.varint()
                        val start = words.position
                        words.position += length
                        fit[0] = words.byte().toByte()
                        meaningWords.add(prepared.words!!.buffer, start, length, terms.toLong(), fit, 0, 1)
                    }
                }
                longestTermKey = maxOf(longestTermKey, row.expression.codePointCount(0, row.expression.length), row.reading.codePointCount(0, row.reading.length))
                terms++
                inBlock++
                left--
            }
        }

        /** Encodes [rows]; can run on any thread, while [meta] runs on the writing one. */
        fun prepareMeta(rows: List<TermMeta>, ofKanji: Boolean): PreparedMeta {
            val packed = Bytes(rows.size * 24 + 64)
            var frequencies = 0
            var longest = 0
            for (m in rows) {
                val value = MetaCodec.encode(m.expression, m.mode, m.data)
                packed.string(m.expression).string(m.mode).varint(value.size).bytes(value)
                if (m.mode == "freq") frequencies++
                longest = maxOf(longest, m.expression.codePointCount(0, m.expression.length))
            }
            return PreparedMeta(packed, rows.size, frequencies, longest, ofKanji)
        }

        fun meta(rows: List<TermMeta>, ofKanji: Boolean) = meta(prepareMeta(rows, ofKanji))

        fun meta(prepared: PreparedMeta) {
            val sorter = if (prepared.ofKanji) kanjiMeta else termMeta
            val bytes = prepared.packed.buffer
            val r = Reader(ByteBuffer.wrap(bytes, 0, prepared.packed.size), 0)
            repeat(prepared.count) {
                val keyLength = r.varint()
                val keyStart = r.position
                r.position += keyLength
                val mode = r.string()
                val valueLength = r.varint()
                record.size = 0
                record.varint(string(mode)).bytes(bytes, r.position, valueLength)
                r.position += valueLength
                sorter.add(bytes, keyStart, keyLength, metaRows++, record.buffer, 0, record.size)
            }
            if (prepared.ofKanji) kanjiMetaRows += prepared.count else longestMetaKey = maxOf(longestMetaKey, prepared.longestKey)
            frequencies += prepared.frequencies
        }

        fun kanji(rows: List<KanjiEntry>) {
            for (k in rows) {
                val key = k.character.toByteArray(Charsets.UTF_8)
                record.size = 0
                record.string(k.onyomi).string(k.kunyomi).string(k.tags).string(k.meanings.joinToString("\n")).string(k.stats)
                kanji.add(key, 0, key.size, (kanjiRows++).toLong(), record.buffer, 0, record.size)
            }
        }

        fun tags(rows: List<TagInfo>) {
            tags += rows
        }

        /** Stores image [path] of [size], as it is; the first of a path wins. */
        fun media(path: String, bytes: ByteArray, mediaType: String, size: Pair<Int, Int>) {
            if (!mediaPaths.add(path)) return
            val key = path.toByteArray(Charsets.UTF_8)
            val entry = Bytes().string(mediaType).varint(size.first).varint(size.second).varlong(dataPosition).varint(bytes.size)
            media.add(key, 0, key.size, mediaPaths.size.toLong(), entry.buffer, 0, entry.size)
            builder.write(bytes)
        }

        /**
         * Writes [sorter]'s entries as section [id]: each key once, with its rows (how many, then
         * the rows, in the order they came); and the keys' Bloom filter as section [filterId] if any.
         */
        private fun groupedTable(sorter: ExternalSorter, id: Int, filterId: Int?) {
            val group = Bytes(256)
            val value = Bytes(256)
            var rows = 0
            var key = ByteArray(64)
            var keyLength = -1
            var hashes = LongArray(1024)
            var keyCount = 0
            TableWriter(temp("table")).use { table ->
                fun flush() {
                    if (keyLength < 0) return
                    value.size = 0
                    value.varint(rows).bytes(group.buffer, 0, group.size)
                    table.add(key, 0, keyLength, value.buffer, 0, value.size)
                    if (filterId != null) {
                        if (keyCount == hashes.size) hashes = hashes.copyOf(keyCount * 2)
                        hashes[keyCount] = hash(key, 0, keyLength)
                    }
                    keyCount++
                }
                sorter.forEachSorted { e ->
                    if (keyLength < 0 || compareBytes(e.key, 0, e.keyLength, key, 0, keyLength) != 0) {
                        flush()
                        if (key.size < e.keyLength) key = ByteArray(e.keyLength * 2)
                        System.arraycopy(e.key, 0, key, 0, e.keyLength)
                        keyLength = e.keyLength
                        group.size = 0
                        rows = 0
                    }
                    group.bytes(e.value, 0, e.valueLength)
                    rows++
                }
                flush()
                if (table.count > 0) table.finish(builder, id)
            }
            if (filterId != null && keyCount > 0) builder.bloomFilter(filterId, hashes, keyCount)
        }

        /**
         * The [MEANINGS] table: each word of the definitions once, with its terms (numbered as
         * [ids] says): how many, then each one's difference from the one before and how well
         * the word fits it. Returns how many words there are.
         */
        private fun meaningsTable(ids: IntArray): Int {
            var key = ByteArray(64)
            var keyLength = -1
            var terms = LongArray(16) // term << 8 | fit
            var n = 0
            var count = 0
            val postings = Bytes(64)
            TableWriter(temp("table")).use { table ->
                fun flush() {
                    if (keyLength < 0) return
                    terms.sort(0, n)
                    postings.size = 0
                    postings.varint(n)
                    var previous = 0
                    for (i in 0 until n) {
                        val term = (terms[i] ushr 8).toInt()
                        postings.varint(term - previous).byte((terms[i] and 0xff).toInt())
                        previous = term
                    }
                    table.add(key, 0, keyLength, postings.buffer, 0, postings.size)
                    count++
                }
                meaningWords.forEachSorted { e ->
                    if (keyLength < 0 || compareBytes(e.key, 0, e.keyLength, key, 0, keyLength) != 0) {
                        flush()
                        if (key.size < e.keyLength) key = ByteArray(e.keyLength * 2)
                        System.arraycopy(e.key, 0, key, 0, e.keyLength)
                        keyLength = e.keyLength
                        n = 0
                    }
                    if (n == terms.size) terms = terms.copyOf(n * 2)
                    terms[n++] = (ids[e.number.toInt()].toLong() shl 8) or (e.value[0].toLong() and 0xff)
                }
                flush()
                if (table.count > 0) table.finish(builder, MEANINGS)
            }
            return count
        }

        /**
         * Writes the tables and what the file says about the dictionary ([facts], with the
         * counts of what was written), and closes the file. Returns the facts as stored.
         */
        fun finish(facts: DictionaryFacts, reverseKeys: Boolean): DictionaryFacts {
            val stored = facts.copy(
                terms = terms, meta = metaRows.toInt(), kanji = kanjiRows, frequencies = frequencies, images = mediaPaths.size,
                forms = forms, kanjiMeta = kanjiMetaRows, tags = tags.size,
            )
            builder.add(DATA, HEADER_SIZE.toLong(), dataPosition)

            // Glossary blocks' offsets, then where the last ends
            builder.section(GLOSSARY_BLOCKS) {
                builder.int(blocks)
                for (i in 0 until blocks) builder.long(blockOffsets[i])
                builder.long(blockEnd)
            }
            glossaryDictionary?.let { builder.section(GLOSSARY_DICTIONARY, it) }

            // The terms sorted by sequence (in the order they came within one): [byId] has the terms,
            // numbered as they came, in their new order. Their glossaries stay in the order they came.
            records.close()
            val byId = sortedIndices(terms) { a, b -> sequences[a].compareTo(sequences[b]) }
            val ids = IntArray(terms).also { for ((id, t) in byId.withIndex()) it[t] = id }
            keysTable(builder, keys, temp("table"), ids)
            val meaningCount = if (meanings) meaningsTable(ids) else 0

            if (terms > 0) {
                check(recordsSize <= Int.MAX_VALUE) { "Term records over 2 GB" }
                RandomAccessFile(recordsFile, "r").use { raf ->
                    val all = map(raf.channel, 0, recordsSize)
                    fun end(t: Int) = if (t + 1 < terms) recordOffsets[t + 1] else recordsSize
                    builder.section(TERMS) {
                        builder.int(terms)
                        var at = 0L
                        for (t in byId) {
                            builder.int(at.toInt())
                            at += end(t) - recordOffsets[t]
                        }
                        builder.int(at.toInt())
                        val bytes = ByteArray(1 shl 12)
                        for (t in byId) {
                            val length = (end(t) - recordOffsets[t]).toInt()
                            val into = if (length <= bytes.size) bytes else ByteArray(length)
                            all.duplicate().apply { position(recordOffsets[t].toInt()) }.get(into, 0, length)
                            builder.write(into, 0, length)
                        }
                    }
                }
            }
            recordsFile.delete()

            groupedTable(termMeta, TERM_META, META_FILTER)
            groupedTable(kanjiMeta, KANJI_META, null)
            groupedTable(kanji, KANJI, null)
            TableWriter(temp("table")).use { table ->
                // Each path once: the first that came
                var previous: ByteArray? = null
                media.forEachSorted { e ->
                    val key = e.key.copyOf(e.keyLength)
                    if (previous?.contentEquals(key) != true) table.add(key, 0, key.size, e.value, 0, e.valueLength)
                    previous = key
                }
                if (table.count > 0) table.finish(builder, MEDIA)
            }
            if (tags.isNotEmpty()) {
                builder.section(TAGS, Bytes().varint(tags.size).apply {
                    for (t in tags) string(t.name).string(t.category).zigzag(t.order.toLong()).string(t.notes).zigzag(t.score.toLong())
                }.toByteArray())
            }
            builder.section(STRINGS, Bytes().int(stringList.size).apply {
                val heap = Bytes()
                for (s in stringList) {
                    int(heap.size)
                    heap.bytes(s.toByteArray(Charsets.UTF_8))
                }
                int(heap.size)
                bytes(heap.buffer, 0, heap.size)
            }.toByteArray())

            val info = JSONObject()
                .put("title", stored.title).put("revision", stored.revision)
                .put("terms", stored.terms).put("meta", stored.meta).put("kanji", stored.kanji)
                .put("frequencies", stored.frequencies).put("images", stored.images)
                .put("forms", stored.forms).put("kanji_meta", stored.kanjiMeta).put("tags", stored.tags)
                .put("longest_term_key", longestTermKey)
                .put("longest_meta_key", longestMetaKey)
            if (meanings) info.put("meaning_words", meaningCount)
            stored.frequencyMode?.let { info.put("frequency_mode", it) }
            stored.index?.let { info.put("index", it) }
            stored.styles?.let { info.put("styles", it) }
            builder.section(INFO, info.toString().toByteArray(Charsets.UTF_8))
            builder.finish()
            close()
            if (reverseKeys && terms > 0) open(file).use { it.buildReverseKeys() }
            return stored
        }

        /** Deletes the files beside; unless [finish]ed, the file too. */
        override fun close() {
            builder.close()
            runCatching { records.close() }
            recordsFile.delete()
            for (sorter in listOf(keys, termMeta, kanjiMeta, kanji, media, meaningWords)) sorter.close()
        }
    }

    /** A growable byte array, little-endian */
    internal class Bytes(capacity: Int = 64) {
        var buffer = ByteArray(capacity)
        var size = 0

        private fun room(n: Int) {
            if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        }

        fun byte(b: Int) = apply {
            room(1)
            buffer[size++] = b.toByte()
        }

        fun bytes(b: ByteArray, start: Int = 0, length: Int = b.size - start) = apply {
            room(length)
            System.arraycopy(b, start, buffer, size, length)
            size += length
        }

        fun int(v: Int) = apply { for (i in 0 until 4) byte(v ushr (8 * i)) }

        fun long(v: Long) = apply { for (i in 0 until 8) byte((v ushr (8 * i)).toInt()) }

        fun varint(v: Int) = varlong(v.toLong() and 0xffffffffL)

        fun varlong(v: Long) = apply {
            var rest = v
            while (rest and 0x7fL.inv() != 0L) {
                byte(((rest and 0x7f) or 0x80).toInt())
                rest = rest ushr 7
            }
            byte(rest.toInt())
        }

        fun zigzag(v: Long) = varlong((v shl 1) xor (v shr 63))

        fun string(s: String) = apply {
            val b = s.toByteArray(Charsets.UTF_8)
            varint(b.size)
            bytes(b)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    companion object {
        private val MAGIC = "HIROIDIC".toByteArray()
        private val EMPTY = ByteArray(0)

        /** The version of the layout */
        const val FORMAT = 3

        private const val HEADER_SIZE = 512
        private const val MAX_SECTIONS = (HEADER_SIZE - 16) / 20

        /** About how much glossary text goes into a block, before deflating */
        const val BLOCK_SIZE = 4 * 1024

        private const val LEVEL = 6

        /** Inflated blocks kept, per dictionary */
        private const val BLOCK_CACHE = 64

        /** Inflated first, for a block's glossaries' lengths */
        private const val HEADER_INFLATE = 1024

        /** Keys between two restarts of a [SortedTable] */
        private const val INTERVAL = 8

        /** Bits per key of a Bloom filter, and hashes: about 1% false positives */
        private const val BLOOM_BITS = 10
        private const val BLOOM_HASHES = 7

        /** What a sort keeps in memory before it goes on on disk */
        const val SORT_MEMORY = 8 shl 20

        /** Deflate looks back at most 32 KB, so a dictionary for it is no longer. */
        private const val DICTIONARY_SIZE = 32 * 1024

        private const val INFO = 1
        private const val STRINGS = 2
        private const val KEYS = 3
        private const val TERMS = 4
        private const val GLOSSARY_BLOCKS = 6
        private const val GLOSSARY_DICTIONARY = 7
        private const val TERM_META = 8
        private const val KANJI_META = 9
        private const val KANJI = 10
        private const val TAGS = 11
        private const val MEDIA = 12
        private const val DATA = 13

        /** Marks an export, which has no [KEYS] */
        private const val PORTABLE = 14

        /** The only section of a ".rev" file */
        private const val REVERSE_KEYS = 15

        private const val KEY_FILTER = 16
        private const val META_FILTER = 17

        /** The words of the definitions, each with its terms ([findTermsByMeaning]) */
        private const val MEANINGS = 18

        /** What the last word searched by meaning has to be long for words starting with it to count, how many of those, and how much worse they fit */
        private const val SHORTEST_PREFIX = 3
        private const val MOST_PREFIXED = 200
        private const val PREFIXED = 256

        /** Fits are below this: a word's (a byte) and what the start of a word adds */
        private const val FITS = 512

        fun open(file: File) = DictionaryFile(file)

        /** [text] backwards, by code point (StringBuilder keeps surrogate pairs together). */
        fun reversed(text: String): String = StringBuilder(text).reverse().toString()

        /** Whether [file] is a dictionary file (or an export of one) */
        fun isDictionaryFile(file: File): Boolean = runCatching {
            file.inputStream().use { input -> ByteArray(MAGIC.size).also { input.read(it) }.contentEquals(MAGIC) }
        }.getOrDefault(false)

        /**
         * Makes [target] from [portable], written by [writePortable]: its sections as they are,
         * with the keys and their filter made again from the term records.
         */
        fun restore(portable: File, target: File) {
            RandomAccessFile(portable, "r").use { raf ->
                val channel = raf.channel
                val sections = readHeader(channel, portable.name)
                FileBuilder(target).use { b ->
                    // The data first, so it stays where the header's room ends; then the others
                    for ((id, range) in sections.entries.sortedBy { if (it.key == DATA) -1 else it.key }) {
                        if (id == PORTABLE) continue
                        b.section(id) { b.copy(channel, range[0], range[1]) }
                    }
                    val terms = sections[TERMS]?.let { (o, l) -> map(channel, o, l) }
                    val termCount = terms?.getInt(0) ?: 0
                    ExternalSorter(File(target.path + ".keys")).use { keys ->
                        for (t in 0 until termCount) {
                            val r = Reader(terms!!, 4 + 4 * (termCount + 1) + terms.getInt(4 + 4 * t))
                            r.zigzag()
                            r.varint()
                            r.varint()
                            // The expression, even empty, as the import has it; the reading if it isn't the expression
                            for (k in 0..1) {
                                val bytes = ByteArray(r.varint()) { terms.get(r.position + it) }
                                r.position += bytes.size
                                if (k == 0 || bytes.isNotEmpty()) keys.add(bytes, 0, bytes.size, t.toLong())
                            }
                        }
                        keysTable(b, keys, File(target.path + ".table"), null)
                    }
                    b.finish()
                }
            }
        }

        /**
         * The [KEYS] table and its filter from [keys], whose entries are the keys with the term
         * they're for (as the terms came; [ids] gives their numbers, if they have others).
         */
        private fun keysTable(b: FileBuilder, keys: ExternalSorter, temp: File, ids: IntArray?) {
            var key = ByteArray(64)
            var keyLength = -1
            var terms = IntArray(16)
            var n = 0
            var hashes = LongArray(1024)
            var keyCount = 0
            val postings = Bytes(64)
            TableWriter(temp).use { table ->
                fun flush() {
                    if (keyLength < 0) return
                    // Its terms: how many, then the differences between them
                    terms.sort(0, n)
                    postings.size = 0
                    postings.varint(n)
                    var previous = 0
                    for (i in 0 until n) {
                        postings.varint(terms[i] - previous)
                        previous = terms[i]
                    }
                    table.add(key, 0, keyLength, postings.buffer, 0, postings.size)
                    if (keyCount == hashes.size) hashes = hashes.copyOf(keyCount * 2)
                    hashes[keyCount++] = hash(key, 0, keyLength)
                }
                keys.forEachSorted { e ->
                    if (keyLength < 0 || compareBytes(e.key, 0, e.keyLength, key, 0, keyLength) != 0) {
                        flush()
                        if (key.size < e.keyLength) key = ByteArray(e.keyLength * 2)
                        System.arraycopy(e.key, 0, key, 0, e.keyLength)
                        keyLength = e.keyLength
                        n = 0
                    }
                    if (n == terms.size) terms = terms.copyOf(n * 2)
                    terms[n++] = ids?.get(e.number.toInt()) ?: e.number.toInt()
                }
                flush()
                if (table.count > 0) table.finish(b, KEYS)
            }
            if (keyCount > 0) b.bloomFilter(KEY_FILTER, hashes, keyCount)
        }

        /** A 64-bit hash of [length] bytes of [bytes] at [start]: FNV-1a, mixed as MurmurHash3 finishes */
        internal fun hash(bytes: ByteArray, start: Int, length: Int): Long {
            var h = 0xcbf29ce484222325UL.toLong()
            for (i in start until start + length) {
                h = h xor (bytes[i].toLong() and 0xff)
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33)
            h *= 0xff51afd7ed558ccdUL.toLong()
            h = h xor (h ushr 33)
            h *= 0xc4ceb9fe1a85ec53UL.toLong()
            return h xor (h ushr 33)
        }

        /** The [i]th bit of [bits] for [hash] (double hashing) */
        private fun bloomBit(hash: Long, i: Int, bits: Long): Long =
            Math.floorMod((hash and 0xffffffffL) + i.toLong() * (hash ushr 32), bits)

        private fun readHeader(channel: FileChannel, name: String): Map<Int, LongArray> {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, header, 0)
            header.flip()
            val magic = ByteArray(MAGIC.size).also { header.get(it) }
            require(magic.contentEquals(MAGIC)) { "$name is not a Hiroi dictionary file" }
            val format = header.int
            require(format == FORMAT) { "$name is of format $format, not $FORMAT" }
            return buildMap { repeat(header.int) { put(header.int, longArrayOf(header.long, header.long)) } }
        }

        private fun facts(info: JSONObject): DictionaryFacts {
            fun opt(key: String) = if (info.has(key)) info.getString(key) else null
            return DictionaryFacts(
                title = info.getString("title"),
                revision = info.optString("revision"),
                frequencyMode = opt("frequency_mode"),
                index = opt("index"),
                styles = opt("styles"),
                terms = info.optInt("terms"),
                meta = info.optInt("meta"),
                kanji = info.optInt("kanji"),
                frequencies = info.optInt("frequencies"),
                images = info.optInt("images"),
                forms = if (info.has("forms")) info.getInt("forms") else null,
                kanjiMeta = if (info.has("kanji_meta")) info.getInt("kanji_meta") else null,
                tags = if (info.has("tags")) info.getInt("tags") else null,
            )
        }

        private fun readFully(channel: FileChannel, buffer: ByteBuffer, at: Long) {
            var position = at
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n < 0) throw IOException("The file ends early")
                position += n
            }
        }

        private fun readBytes(channel: FileChannel, offset: Long, length: Int): ByteArray =
            ByteArray(length).also { readFully(channel, ByteBuffer.wrap(it), offset) }

        private fun map(channel: FileChannel, offset: Long, length: Long): ByteBuffer =
            channel.map(FileChannel.MapMode.READ_ONLY, offset, length).order(ByteOrder.LITTLE_ENDIAN)

        /** Copies [length] bytes at [offset] of [from], a [buffer] at a time, to [write] (given the bytes in it) */
        private inline fun copy(from: FileChannel, offset: Long, length: Long, buffer: ByteBuffer, write: (Int) -> Unit) {
            var done = 0L
            while (done < length) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), length - done).toInt())
                val n = from.read(buffer, offset + done)
                if (n < 0) throw IOException("The file ends early")
                write(n)
                done += n
            }
        }

        /**
         * A dictionary for deflating glossaries: glossaries picked evenly from [glossaries], up to
         * 32 KB, the most typical last (deflate finds what's nearest most cheaply).
         */
        fun trainDictionary(glossaries: List<String>): ByteArray? {
            if (glossaries.isEmpty()) return null
            val step = maxOf(1, glossaries.size / 400)
            val sample = Bytes()
            for (i in glossaries.indices step step) sample.bytes(glossaries[i].toByteArray(Charsets.UTF_8))
            val all = sample.toByteArray()
            return all.copyOfRange(maxOf(0, all.size - DICTIONARY_SIZE), all.size)
        }

        private fun readStrings(buffer: ByteBuffer): Array<String> {
            val count = buffer.getInt(0)
            val heap = 4 + 4 * (count + 1)
            return Array(count) { i ->
                val start = buffer.getInt(4 + 4 * i)
                val bytes = ByteArray(buffer.getInt(4 + 4 * (i + 1)) - start)
                buffer.duplicate().apply { position(heap + start) }.get(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }

        /** 0 until [n] sorted by [compare], keeping the order of equal ones: a merge sort of ints, nothing boxed */
        internal inline fun sortedIndices(n: Int, compare: (Int, Int) -> Int): IntArray {
            var from = IntArray(n) { it }
            var to = IntArray(n)
            var width = 1
            while (width < n) {
                var lo = 0
                while (lo < n) {
                    val mid = minOf(lo + width, n)
                    val hi = minOf(lo + 2 * width, n)
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) to[k++] = if (compare(from[j], from[i]) < 0) from[j++] else from[i++]
                    while (i < mid) to[k++] = from[i++]
                    while (j < hi) to[k++] = from[j++]
                    lo = hi
                }
                val swap = from
                from = to
                to = swap
                width *= 2
            }
            return from
        }

        /** [a]'s bytes against [b]'s, unsigned (UTF-8's order is code points') */
        internal fun compareBytes(a: ByteArray, aStart: Int, aLength: Int, b: ByteArray, bStart: Int, bLength: Int): Int {
            for (i in 0 until minOf(aLength, bLength)) {
                val d = (a[aStart + i].toInt() and 0xff) - (b[bStart + i].toInt() and 0xff)
                if (d != 0) return d
            }
            return aLength - bLength
        }
    }
}
