package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class YomitanCollectionTest {
    /** Collects what a collection holds. */
    private class Collected : YomitanCollection.Sink {
        val dictionaries = mutableListOf<JSONObject>()
        val terms = mutableListOf<TermEntry>()
        val termMeta = mutableListOf<TermMeta>()
        val kanji = mutableListOf<KanjiEntry>()
        val kanjiMeta = mutableListOf<TermMeta>()
        val tags = mutableListOf<TagInfo>()
        override fun dictionary(summary: JSONObject) { dictionaries += summary }
        override fun terms(rows: List<TermEntry>) { terms += rows }
        override fun termMeta(rows: List<TermMeta>) { termMeta += rows }
        override fun kanji(rows: List<KanjiEntry>) { kanji += rows }
        override fun kanjiMeta(rows: List<TermMeta>) { kanjiMeta += rows }
        override fun tags(rows: List<TagInfo>) { tags += rows }
    }

    private fun read(json: String) = Collected().also { YomitanCollection.read(StringReader(json), it) }

    // As Yomitan exports it (trimmed): outbound tables as [key, value] (Dexie may wrap those with
    // Typeson's $types, as in tagMeta), terms and media with their id, media content as base64
    // with $types, a term from an old version with "tags".
    private val yomitanExport = """
        {"formatName":"dexie","formatVersion":1,"data":{"databaseName":"dict","databaseVersion":6,
        "tables":[{"name":"dictionaries","schema":"++,title,version","rowCount":1},{"name":"terms","schema":"++id,dictionary,expression","rowCount":2}],
        "data":[
          {"tableName":"dictionaries","inbound":false,"rows":[[1,{"title":"Jitendex","revision":"2026.11.01","sequenced":true,"version":3,
            "importDate":1,"prefixWildcardsSupported":false,"styles":".x{}","frequencyMode":"rank-based","author":"Stephen Kraus",
            "counts":{"terms":{"total":2}}}]]},
          {"tableName":"kanji","inbound":false,"rows":[[1,{"character":"食","onyomi":"ショク","kunyomi":"た.べる","tags":"jouyou","meanings":["eat","food"],"stats":{"strokes":"9"},"dictionary":"KANJIDIC"}]]},
          {"tableName":"kanjiMeta","inbound":false,"rows":[[1,{"character":"食","mode":"freq","data":250,"dictionary":"KANJIDIC"}]]},
          {"tableName":"media","inbound":true,"rows":[{"dictionary":"Jitendex","path":"a.png","mediaType":"image/png","width":1,"height":1,"content":"iVBO","${'$'}types":{"content":"arraybuffer"},"id":1}]},
          {"tableName":"tagMeta","inbound":false,"rows":[{"${'$'}":[1,{"name":"★","category":"popular","order":-10,"notes":"high priority","score":0,"dictionary":"Jitendex"}],"${'$'}types":{"${'$'}":{"":"arrayNonindexKeys"}}}]},
          {"tableName":"termMeta","inbound":false,"rows":[[1,{"expression":"食べる","mode":"freq","data":{"reading":"たべる","frequency":480},"dictionary":"JPDB"}]]},
          {"tableName":"terms","inbound":true,"rows":[
            {"expression":"食べる","reading":"たべる","definitionTags":"v1","rules":"v1","score":5,"glossary":["to eat",{"type":"structured-content","content":"x"}],"sequence":1358280,"termTags":"★","dictionary":"Jitendex","id":1},
            {"expression":"猫","reading":"","tags":"n","rules":"","score":0,"glossary":["cat"],"dictionary":"Jitendex","id":2}]}
        ]}}
    """.trimIndent()

    @Test
    fun readsYomitansExport() {
        val c = read(yomitanExport)
        assertEquals("Jitendex", c.dictionaries.single().getString("title"))
        assertEquals("Stephen Kraus", c.dictionaries.single().getString("author"))
        val eat = c.terms[0]
        assertEquals(TermEntry("食べる", "たべる", "v1", "v1", 5, """["to eat",{"type":"structured-content","content":"x"}]""", 1358280, "★", "Jitendex"), eat)
        // Old rows: "tags" for definitionTags; an empty reading is the expression, as Yomitan reads it
        assertEquals("n", c.terms[1].definitionTags)
        assertEquals("猫", c.terms[1].reading)
        assertEquals(TermMeta("食べる", "freq", """[{"reading":"たべる","frequency":480}]""", "JPDB"), c.termMeta.single())
        assertEquals(KanjiEntry("食", "ショク", "た.べる", "jouyou", listOf("eat", "food"), "KANJIDIC"), c.kanji.single())
        assertEquals(TermMeta("食", "freq", "[250]", "KANJIDIC"), c.kanjiMeta.single())
        assertEquals(TagInfo("★", "popular", -10, "high priority", 0, "Jitendex"), c.tags.single())
    }

    @Test
    fun writesWhatItReads() {
        val out = StringWriter()
        YomitanCollection.Writer(out).use { w ->
            w.begin(mapOf("dictionaries" to 1, "terms" to 1, "termMeta" to 1, "kanji" to 1, "kanjiMeta" to 1, "tagMeta" to 1))
            for (table in YomitanCollection.TABLES.keys) w.table(table) {
                when (table) {
                    "dictionaries" -> w.dictionary(JSONObject().put("title", "Jitendex").put("revision", "1").toString())
                    "terms" -> w.term(TermEntry("食べる", "たべる", "v1", "v1", 5, """["to eat"]""", 7, "★", "Jitendex"))
                    "termMeta" -> w.meta(TermMeta("食べる", "pitch", """[{"reading":"たべる","pitches":[{"position":2}]}]""", "NHK"), "expression")
                    "kanji" -> w.kanji(KanjiEntry("食", "ショク", "た.べる", "", listOf("eat"), "KANJIDIC"))
                    "kanjiMeta" -> w.meta(TermMeta("食", "freq", "[\"250\"]", "KANJIDIC"), "character")
                    "tagMeta" -> w.tag(TagInfo("v1", "partOfSpeech", 0, "Ichidan verb", 0, "Jitendex"))
                }
            }
            w.end()
        }
        val json = JSONObject(out.toString())
        // Dexie's export structure, as Yomitan's import expects it
        assertEquals("dexie", json.getString("formatName"))
        val data = json.getJSONObject("data")
        assertEquals("dict", data.getString("databaseName"))
        assertEquals(6, data.getInt("databaseVersion"))
        val tables = data.getJSONArray("tables")
        assertEquals(YomitanCollection.TABLES.keys.toList(), List(tables.length()) { tables.getJSONObject(it).getString("name") })
        assertEquals("++id,dictionary,expression,expressionReverse,reading,readingReverse,sequence", tables.getJSONObject(6).getString("schema"))
        val terms = data.getJSONArray("data").getJSONObject(6)
        assertTrue(terms.getBoolean("inbound"))
        assertEquals(1, terms.getJSONArray("rows").getJSONObject(0).getInt("id"))
        val tags = data.getJSONArray("data").getJSONObject(4)
        assertEquals(false, tags.getBoolean("inbound"))
        assertEquals(1, (tags.getJSONArray("rows").getJSONArray(0)).getInt(0))

        val c = read(out.toString())
        assertEquals(TermEntry("食べる", "たべる", "v1", "v1", 5, """["to eat"]""", 7, "★", "Jitendex"), c.terms.single())
        assertEquals(TermMeta("食べる", "pitch", """[{"reading":"たべる","pitches":[{"position":2}]}]""", "NHK"), c.termMeta.single())
        assertEquals(TermMeta("食", "freq", "[\"250\"]", "KANJIDIC"), c.kanjiMeta.single())
        assertEquals(KanjiEntry("食", "ショク", "た.べる", "", listOf("eat"), "KANJIDIC"), c.kanji.single())
        assertEquals(TagInfo("v1", "partOfSpeech", 0, "Ichidan verb", 0, "Jitendex"), c.tags.single())
    }

    @Test
    fun buildsTheSummaryYomitanStores() {
        val index = """{"title":"JPDB","revision":"v2","format":3,"frequencyMode":"rank-based","author":"me","sequenced":false,"isUpdatable":true}"""
        val stored = YomitanCollection.summaryFromIndex(index)
        val summary = JSONObject(
            YomitanCollection.summary(
                stored, DictionaryInfo(1, "JPDB", "v2", 0, 3, 0, 3, "rank-based"), null,
                YomitanCollection.Counts(termMeta = mapOf("freq" to 3)), importDate = 42,
            ),
        )
        assertEquals(3, summary.getInt("version"))
        assertEquals(42, summary.getLong("importDate"))
        assertEquals("me", summary.getString("author"))
        assertEquals("rank-based", summary.getString("frequencyMode"))
        assertTrue(summary.getBoolean("isUpdatable"))
        assertEquals(3, summary.getJSONObject("counts").getJSONObject("termMeta").getInt("freq"))
        assertEquals(3, summary.getJSONObject("counts").getJSONObject("termMeta").getInt("total"))
        assertEquals("", summary.getString("styles"))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { read("""{"version":1,"options":{}}""") }
        assertThrows(IllegalArgumentException::class.java) { read("""{"formatName":"dexie","formatVersion":1,"data":{"databaseName":"other","data":[]}}""") }
        assertEquals(0, JSONArray().length())
    }
}
