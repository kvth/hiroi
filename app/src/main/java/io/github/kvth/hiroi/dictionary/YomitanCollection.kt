package io.github.kvth.hiroi.dictionary

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONObject
import java.io.Closeable
import java.io.Reader
import java.util.Base64

/**
 * Yomitan's dictionary collection file (Settings → Backup → Export / Import dictionary
 * collection): Dexie's export (dexie-export-import, format 1) of Yomitan's IndexedDB "dict", with
 * Yomitan's tables and rows (types/ext/dictionary-database.d.ts). Hiroi writes the same, so its
 * exports can be imported into Yomitan, and reads Yomitan's, streaming either way: collections
 * can be hundreds of MB.
 */
object YomitanCollection {
    const val DATABASE = "dict"

    /** Yomitan opens its IndexedDB in version 60, which Dexie counts as 6. */
    const val DATABASE_VERSION = 6

    /** Yomitan's tables, in the order Dexie exports them, with their Dexie schema. */
    val TABLES = linkedMapOf(
        "dictionaries" to "++,title,version",
        "kanji" to "++,character,dictionary",
        "kanjiMeta" to "++,character,dictionary",
        "media" to "++id,dictionary,path",
        "tagMeta" to "++,dictionary,name",
        "termMeta" to "++,dictionary,expression",
        "terms" to "++id,dictionary,expression,expressionReverse,reading,readingReverse,sequence",
    )

    /** Tables whose rows carry their key (Yomitan's "id"); the others' rows are [key, value]. */
    private val INBOUND = setOf("media", "terms")

    /** What a dictionary summary takes over from index.json, besides title, revision and format. */
    private val INDEX_KEYS = listOf(
        "sequenced", "author", "url", "description", "attribution", "sourceLanguage", "targetLanguage",
        "frequencyMode", "isUpdatable", "indexUrl", "downloadUrl", "minimumYomitanVersion",
    )

    /** The parts of a dictionary's index.json that go into its summary when it's exported. */
    fun summaryFromIndex(indexJson: String): String {
        val index = JSONObject(indexJson)
        val out = JSONObject()
            .put("title", index.getString("title"))
            .put("revision", index.optString("revision"))
            .put("version", index.optInt("format", index.optInt("version", 3)))
        for (key in INDEX_KEYS) if (index.has(key)) out.put(key, index.get(key))
        return out.toString()
    }

    /** Row counts of one dictionary, by table, and of its meta rows by mode, as a summary lists them. */
    class Counts(
        val terms: Int = 0,
        val termMeta: Map<String, Int> = emptyMap(),
        val kanji: Int = 0,
        val kanjiMeta: Map<String, Int> = emptyMap(),
        val tags: Int = 0,
    )

    /**
     * A dictionary's summary as Yomitan stores it: what its index.json said ([stored], from
     * [summaryFromIndex] or an imported collection), with its counts and styles.
     */
    fun summary(stored: String?, info: DictionaryInfo, styles: String?, counts: Counts, importDate: Long): String {
        val base = stored?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        fun meta(byMode: Map<String, Int>) = JSONObject().put("total", byMode.values.sum()).also { o -> byMode.forEach { (mode, n) -> o.put(mode, n) } }
        val out = JSONObject()
            .put("title", info.title)
            .put("revision", info.revision)
            .put("sequenced", base.optBoolean("sequenced", false))
            .put("version", base.optInt("version", 3))
            .put("importDate", base.optLong("importDate", importDate))
            .put("prefixWildcardsSupported", false)
            .put(
                "counts",
                JSONObject()
                    .put("terms", JSONObject().put("total", counts.terms))
                    .put("termMeta", meta(counts.termMeta))
                    .put("kanji", JSONObject().put("total", counts.kanji))
                    .put("kanjiMeta", meta(counts.kanjiMeta))
                    .put("tagMeta", JSONObject().put("total", counts.tags))
                    .put("media", JSONObject().put("total", 0)),
            )
            .put("styles", styles.orEmpty())
            .put("importSuccess", true)
        for (key in INDEX_KEYS) if (base.has(key) && key != "sequenced") out.put(key, base.get(key))
        info.frequencyMode?.let { out.put("frequencyMode", it) }
        return out.toString()
    }

    /**
     * Writes a collection: [begin] with each table's row count, then each table in [TABLES] order
     * with [table], its rows written by the row functions inside it.
     */
    class Writer(out: java.io.Writer) : Closeable {
        private val json = JsonWriter(out)
        private var key = 0

        fun begin(rowCounts: Map<String, Int>) {
            json.beginObject()
            json.name("formatName").value("dexie")
            json.name("formatVersion").value(1)
            json.name("data").beginObject()
            json.name("databaseName").value(DATABASE)
            json.name("databaseVersion").value(DATABASE_VERSION)
            json.name("tables").beginArray()
            for ((name, schema) in TABLES) {
                json.beginObject().name("name").value(name).name("schema").value(schema).name("rowCount").value(rowCounts[name] ?: 0).endObject()
            }
            json.endArray()
            json.name("data").beginArray()
        }

        fun table(name: String, rows: () -> Unit) {
            json.beginObject().name("tableName").value(name).name("inbound").value(name in INBOUND)
            json.name("rows").beginArray()
            key = 0
            rows()
            json.endArray().endObject()
        }

        /** An outbound row: [key, value]. */
        private fun outbound(fields: JsonWriter.() -> Unit) {
            json.beginArray().value(++key)
            json.beginObject()
            json.fields()
            json.endObject()
            json.endArray()
        }

        fun dictionary(summary: String) {
            json.beginArray().value(++key).jsonValue(summary).endArray()
        }

        fun term(entry: TermEntry) {
            json.beginObject()
            json.name("expression").value(entry.expression)
            json.name("reading").value(entry.reading)
            json.name("definitionTags").value(entry.definitionTags)
            json.name("rules").value(entry.rules)
            json.name("score").value(entry.score)
            json.name("glossary").jsonValue(entry.glossary)
            json.name("sequence").value(entry.sequence)
            json.name("termTags").value(entry.termTags)
            json.name("dictionary").value(entry.dictionary)
            json.name("id").value(++key)
            json.endObject()
        }

        /** A term or kanji meta row; Hiroi keeps the data wrapped in an array. */
        fun meta(meta: TermMeta, keyName: String) = outbound {
            name(keyName).value(meta.expression)
            name("mode").value(meta.mode)
            name("data").jsonValue(meta.data.trim().removePrefix("[").removeSuffix("]"))
            name("dictionary").value(meta.dictionary)
        }

        fun kanji(kanji: KanjiEntry) = outbound {
            name("character").value(kanji.character)
            name("onyomi").value(kanji.onyomi)
            name("kunyomi").value(kanji.kunyomi)
            name("tags").value(kanji.tags)
            name("meanings").beginArray()
            kanji.meanings.forEach { value(it) }
            endArray()
            name("dictionary").value(kanji.dictionary)
            name("stats").beginObject().endObject()
        }

        /** An image; its content is an ArrayBuffer, which Typeson writes as base64 with its type "arraybuffer". */
        fun media(dictionary: String, media: DictionaryMedia, content: ByteArray) {
            json.beginObject()
            json.name("dictionary").value(dictionary)
            json.name("path").value(media.path)
            json.name("mediaType").value(media.mediaType)
            json.name("width").value(media.width)
            json.name("height").value(media.height)
            json.name("content").value(Base64.getEncoder().encodeToString(content))
            json.name("\$types").beginObject().name("content").value("arraybuffer").endObject()
            json.name("id").value(++key)
            json.endObject()
        }

        fun tag(tag: TagInfo) = outbound {
            name("name").value(tag.name)
            name("category").value(tag.category)
            name("order").value(tag.order)
            name("notes").value(tag.notes)
            name("score").value(tag.score)
            name("dictionary").value(tag.dictionary)
        }

        fun end() {
            json.endArray().endObject().endObject()
            json.flush()
        }

        override fun close() = json.close()
    }

    /** An image row: the image of [dictionary] at [path], with its bytes. */
    class MediaRow(val dictionary: String, val path: String, val mediaType: String, val width: Int, val height: Int, val content: ByteArray)

    /** Where a collection's rows go; each row names its dictionary by title. */
    interface Sink {
        fun media(rows: List<MediaRow>) {}
        /** A dictionary's summary (title, revision, styles, counts, what its index.json said). */
        fun dictionary(summary: JSONObject)
        fun terms(rows: List<TermEntry>)
        fun termMeta(rows: List<TermMeta>)
        fun kanji(rows: List<KanjiEntry>)
        fun kanjiMeta(rows: List<TermMeta>)
        fun tags(rows: List<TagInfo>)
    }

    /**
     * Reads a collection into [sink], a few thousand rows at a time (images a few at a time);
     * [onRows] gets the number of rows read so far.
     */
    fun read(input: Reader, sink: Sink, onRows: (Int) -> Unit = {}) {
        val json = JsonReader(input)
        var dexie = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "formatName" -> dexie = json.nextString() == "dexie"
                "formatVersion" -> require(json.nextInt() <= 1) { "This collection's format is newer than Hiroi knows" }
                "data" -> {
                    require(dexie) { NOT_A_COLLECTION }
                    readData(json, sink, onRows)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        require(dexie) { NOT_A_COLLECTION }
    }

    private const val NOT_A_COLLECTION = "Not a dictionary collection (Yomitan: Settings → Backup → Export dictionary collection)"
    private const val BATCH = 2000
    private const val MEDIA_BATCH = 20

    private fun readData(json: JsonReader, sink: Sink, onRows: (Int) -> Unit) {
        var read = 0
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "databaseName" -> json.nextString().let { require(it == DATABASE) { "Not a Yomitan dictionary collection (database \"$it\")" } }
                "data" -> {
                    json.beginArray()
                    while (json.hasNext()) read = readTable(json, sink, read, onRows)
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
    }

    private fun readTable(json: JsonReader, sink: Sink, readBefore: Int, onRows: (Int) -> Unit): Int {
        var read = readBefore
        var table: String? = null
        var inbound = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "tableName" -> table = json.nextString()
                "inbound" -> inbound = json.nextBoolean()
                "rows" -> {
                    val name = table ?: throw IllegalArgumentException("A table's rows come before its name")
                    val batch = mutableListOf<JsonObject>()
                    json.beginArray()
                    while (json.hasNext()) {
                        if (json.peek() == JsonToken.NULL) { json.nextNull(); continue }
                        val row = unwrap(JsonParser.parseReader(json))
                        val value = if (inbound) row.asJsonObject else row.asJsonArray[1].asJsonObject
                        if (name == "dictionaries") sink.dictionary(JSONObject(value.toString())) else if (name in TABLES) batch += value
                        if (batch.size >= (if (name == "media") MEDIA_BATCH else BATCH)) flush(name, batch, sink)
                        if (++read % 10_000 == 0) onRows(read)
                    }
                    json.endArray()
                    flush(name, batch, sink)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return read
    }

    /**
     * Dexie writes rows through Typeson, which wraps a row whose root has a type of its own: an
     * outbound row ([key, value], an array with extra properties) comes as
     * {"$": [key, value], "$types": {"$": {"": "arrayNonindexKeys"}}}.
     */
    private fun unwrap(row: JsonElement): JsonElement {
        if (!row.isJsonObject) return row
        val obj = row.asJsonObject
        return if (obj.has("\$types") && obj.has("\$")) obj.get("\$") else row
    }

    private fun flush(table: String, rows: MutableList<JsonObject>, sink: Sink) {
        if (rows.isEmpty()) return
        when (table) {
            "terms" -> sink.terms(rows.map(::term))
            "termMeta" -> sink.termMeta(rows.map { meta(it, "expression") })
            "kanji" -> sink.kanji(rows.map(::kanji))
            "kanjiMeta" -> sink.kanjiMeta(rows.map { meta(it, "character") })
            "tagMeta" -> sink.tags(rows.map(::tag))
            "media" -> sink.media(rows.mapNotNull(::media))
        }
        rows.clear()
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber?.toInt() ?: 0
    private fun JsonObject.long(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber?.toLong() ?: 0

    /** Yomitan's term row (older ones have tags instead of definitionTags). */
    fun term(row: JsonObject): TermEntry {
        val expression = row.str("expression").orEmpty()
        return TermEntry(
            expression = expression,
            reading = row.str("reading").orEmpty().ifEmpty { expression },
            definitionTags = row.str("definitionTags") ?: row.str("tags").orEmpty(),
            rules = row.str("rules").orEmpty(),
            score = row.int("score"),
            glossary = (row.get("glossary") as? com.google.gson.JsonArray ?: com.google.gson.JsonArray()).toString(),
            sequence = row.long("sequence"),
            termTags = row.str("termTags").orEmpty(),
            dictionary = row.str("dictionary").orEmpty(),
        )
    }

    /** A term or kanji meta row, its data wrapped in an array as Hiroi keeps it. */
    fun meta(row: JsonObject, keyName: String): TermMeta =
        TermMeta(row.str(keyName).orEmpty(), row.str("mode").orEmpty(), "[" + (row.get("data") ?: JsonParser.parseString("null")).toString() + "]", row.str("dictionary").orEmpty())

    fun kanji(row: JsonObject): KanjiEntry {
        val meanings = row.get("meanings")?.takeIf { it.isJsonArray }?.asJsonArray?.map(JsonElement::getAsString).orEmpty()
        return KanjiEntry(row.str("character").orEmpty(), row.str("onyomi").orEmpty(), row.str("kunyomi").orEmpty(), row.str("tags").orEmpty(), meanings, row.str("dictionary").orEmpty())
    }

    /** An image row; its content is base64 (Typeson's arraybuffer). Rows without content are left out. */
    fun media(row: JsonObject): MediaRow? {
        val content = row.str("content")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        val path = row.str("path") ?: return null
        return MediaRow(row.str("dictionary").orEmpty(), path, row.str("mediaType") ?: DictionaryImages.mediaType(path).orEmpty(), row.int("width"), row.int("height"), content)
    }

    fun tag(row: JsonObject): TagInfo =
        TagInfo(row.str("name").orEmpty(), row.str("category").orEmpty(), row.int("order"), row.str("notes").orEmpty(), row.int("score"), row.str("dictionary").orEmpty())
}
