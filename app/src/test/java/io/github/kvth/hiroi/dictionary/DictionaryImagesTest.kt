package io.github.kvth.hiroi.dictionary

import io.github.kvth.hiroi.anki.StructuredHtml
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class DictionaryImagesTest {
    private val sizes = mapOf("img/cat.png" to (320 to 240), "stroke.svg" to (109 to 109))

    @Test
    fun knowsYomitansImageTypes() {
        assertEquals("image/png", DictionaryImages.mediaType("a/b.PNG"))
        assertEquals("image/jpeg", DictionaryImages.mediaType("x.jfif"))
        assertEquals("image/svg+xml", DictionaryImages.mediaType("x.svg"))
        assertNull(DictionaryImages.mediaType("term_bank_1.json"))
    }

    @Test
    fun readsSvgSizes() {
        assertEquals(109 to 109, DictionaryImages.svgSize("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="109" height="109.5">"""))
        assertEquals(200 to 100, DictionaryImages.svgSize("""<svg viewBox="0 0 200 100">"""))
        assertNull(DictionaryImages.svgSize("""<svg>"""))
    }

    @Test
    fun storesImagesAsYomitanDoes() {
        // A glossary image and one inside structured content, each with the size the dictionary wants
        val glossary = """["a cat",{"type":"image","path":"img/cat.png","width":10,"title":"Cat","pixelated":true,"other":1},
            {"type":"structured-content","content":[{"tag":"div","content":{"tag":"img","path":"stroke.svg","height":2,"sizeUnits":"em","verticalAlign":"middle"}}]},
            {"type":"image","path":"missing.png"}]"""
        val prepared = JSONArray(DictionaryImages.prepare(glossary, sizes::get))
        val image = prepared.getJSONObject(1)
        assertEquals(mapOf("type" to "image", "path" to "img/cat.png", "width" to 320, "height" to 240, "preferredWidth" to 10, "title" to "Cat", "pixelated" to true), image.toMap())
        val img = prepared.getJSONObject(2).getJSONArray("content").getJSONObject(0).getJSONObject("content")
        assertEquals(mapOf("tag" to "img", "path" to "stroke.svg", "width" to 109, "height" to 109, "preferredHeight" to 2, "sizeUnits" to "em", "verticalAlign" to "middle"), img.toMap())
        // Images the dictionary doesn't have stay as they are
        assertEquals(mapOf("type" to "image", "path" to "missing.png"), prepared.getJSONObject(3).toMap())
        // Glossaries without images are returned as they were
        val plain = """["to eat"]"""
        assertSame(plain, DictionaryImages.prepare(plain, sizes::get))
    }

    @Test
    fun showsImagesInTheResults() {
        val media = { path: String -> if (path == "img/cat.png") DictionaryMedia(path, "image/png", 320, 240) { byteArrayOf(1, 2, 3) } else null }
        val html = StructuredHtml.item(JSONObject("""{"type":"image","path":"img/cat.png","width":320,"height":240,"preferredWidth":160,"title":"Cat","description":"A cat"}"""), media)
        assertTrue(html, "width:160px" in html)
        assertTrue(html, "src=\"data:image/png;base64,AQID\"" in html)
        assertTrue(html, "aspect-ratio:1.3333" in html)
        assertTrue(html, "title=\"Cat\"" in html)
        assertTrue(html, "<div class=\"gloss-image-description\">A cat</div>" in html)
        // em sizes, collapsed images, and missing ones
        val em = StructuredHtml.item(JSONObject("""{"type":"structured-content","content":{"tag":"img","path":"img/cat.png","width":320,"height":240,"preferredHeight":3,"sizeUnits":"em","collapsed":true}}"""), media)
        assertTrue(em, "width:4em" in em)
        assertTrue(em, em.startsWith("<details class=\"gloss-image\"><summary>Image</summary>"))
        val missing = StructuredHtml.item(JSONObject("""{"type":"image","path":"gone.png","alt":"猫"}"""), media)
        assertTrue(missing, "[猫]" in missing)
    }

    @Test
    fun carriesImagesInCollections() {
        val out = StringWriter()
        YomitanCollection.Writer(out).use { w ->
            w.begin(mapOf("media" to 1))
            for (table in YomitanCollection.TABLES.keys) w.table(table) {
                if (table == "media") w.media("Pictures", DictionaryMedia("img/cat.png", "image/png", 320, 240) { byteArrayOf() }, byteArrayOf(1, 2, 3))
            }
            w.end()
        }
        val row = JSONObject(out.toString()).getJSONObject("data").getJSONArray("data").getJSONObject(3).getJSONArray("rows").getJSONObject(0)
        // As Dexie writes an ArrayBuffer: base64 with Typeson's type
        assertEquals("AQID", row.getString("content"))
        assertEquals("arraybuffer", row.getJSONObject("\$types").getString("content"))
        assertEquals(1, row.getInt("id"))

        val read = mutableListOf<YomitanCollection.MediaRow>()
        YomitanCollection.read(StringReader(out.toString()), object : YomitanCollection.Sink {
            override fun dictionary(summary: JSONObject) {}
            override fun terms(rows: List<TermEntry>) {}
            override fun termMeta(rows: List<TermMeta>) {}
            override fun kanji(rows: List<KanjiEntry>) {}
            override fun kanjiMeta(rows: List<TermMeta>) {}
            override fun tags(rows: List<TagInfo>) {}
            override fun media(rows: List<YomitanCollection.MediaRow>) { read += rows }
        })
        val image = read.single()
        assertEquals(listOf("Pictures", "img/cat.png", "image/png", 320, 240), listOf(image.dictionary, image.path, image.mediaType, image.width, image.height))
        assertEquals(listOf<Byte>(1, 2, 3), image.content.toList())
    }
}
