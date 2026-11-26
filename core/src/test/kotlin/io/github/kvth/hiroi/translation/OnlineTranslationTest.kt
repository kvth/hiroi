package io.github.kvth.hiroi.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTranslationTest {
    private class Sent(val url: String, val headers: Map<String, String>, val body: String?)

    /** Answers every request with [code] and [body], keeping what was sent. */
    private class FakeHttp(private val code: Int, private val body: String) : Http {
        val sent = mutableListOf<Sent>()
        override fun request(url: String, headers: Map<String, String>, body: String?): HttpResponse {
            sent += Sent(url, headers, body)
            return HttpResponse(code, this.body)
        }
    }

    @Test
    fun deeplAsItsApiWantsIt() {
        val http = FakeHttp(200, """{"translations":[{"detected_source_language":"JA","text":"It will rain tomorrow."}]}""")
        val translation = OnlineTranslation(http).translate(TranslationService("deepl", key = "abc:fx"), "明日は雨が降るでしょう。", "en")
        assertEquals("It will rain tomorrow.", translation)
        val sent = http.sent.single()
        // API Free keys end in :fx and have their own server
        assertEquals("https://api-free.deepl.com/v2/translate", sent.url)
        assertEquals("DeepL-Auth-Key abc:fx", sent.headers["Authorization"])
        val body = JSONObject(sent.body!!)
        assertEquals("明日は雨が降るでしょう。", body.getJSONArray("text").getString(0))
        assertEquals("JA", body.getString("source_lang"))
        assertEquals("EN-US", body.getString("target_lang"))
        assertEquals("https://api.deepl.com/v2/translate", OnlineTranslation.deeplUrl("pro-key"))
        assertEquals("ZH-HANS", OnlineTranslation.deeplTarget("zh"))
        assertEquals("DE", OnlineTranslation.deeplTarget("de"))
    }

    @Test
    fun deeplErrorsSayWhatsWrong() {
        val wrongKey = assertThrows(TranslationException::class.java) {
            OnlineTranslation(FakeHttp(403, "")).translate(TranslationService("deepl", key = "x"), "猫", "en")
        }
        assertEquals("DeepL refused the API key", wrongKey.message)
        val quota = assertThrows(TranslationException::class.java) {
            OnlineTranslation(FakeHttp(456, "")).translate(TranslationService("deepl", key = "x"), "猫", "en")
        }
        assertTrue(quota.message!!.contains("quota"))
        // No request without a key
        val http = FakeHttp(200, "")
        assertThrows(TranslationException::class.java) { OnlineTranslation(http).translate(TranslationService("deepl"), "猫", "en") }
        assertTrue(http.sent.isEmpty())
    }

    @Test
    fun libreTranslate() {
        val http = FakeHttp(200, """{"translatedText":"Cat"}""")
        assertEquals("Cat", OnlineTranslation(http).translate(TranslationService("libretranslate", "https://lt.example.com/", "k"), "猫", "zh"))
        val sent = http.sent.single()
        assertEquals("https://lt.example.com/translate", sent.url)
        val body = JSONObject(sent.body!!)
        assertEquals("猫", body.getString("q"))
        assertEquals("ja", body.getString("source"))
        assertEquals("zh-Hans", body.getString("target"))
        assertEquals("k", body.getString("api_key"))
        // Without a key, none is sent; an endpoint URL stays as it is
        assertTrue(!JSONObject(OnlineTranslation.libreBody("猫", "en", "")).has("api_key"))
        assertEquals("http://192.168.1.5:5000/translate", OnlineTranslation.libreUrl("http://192.168.1.5:5000/translate"))
        val error = assertThrows(TranslationException::class.java) {
            OnlineTranslation(FakeHttp(400, """{"error":"Invalid API key"}""")).translate(TranslationService("libretranslate", "https://lt.example.com"), "猫", "en")
        }
        assertEquals("HTTP 400: Invalid API key", error.message)
    }

    @Test
    fun customUrlsAndTheirAnswers() {
        assertEquals(
            "https://example.com/t?q=%E7%8C%AB%20%E7%8A%AC&sl=ja&tl=en&x={other}",
            OnlineTranslation.customUrl("https://example.com/t?q={text}&sl={source}&tl={target}&x={other}", "猫 犬", "en"),
        )
        val http = FakeHttp(200, "Cat\n")
        assertEquals("Cat", OnlineTranslation(http).translate(TranslationService("custom", "https://example.com/?q={text}"), "猫", "en"))
        assertNull(http.sent.single().body) // a GET
        // JSON answers, as various services give them
        assertEquals("Cat", OnlineTranslation.customTranslation("""{"translatedText":"Cat"}"""))
        assertEquals("Cat", OnlineTranslation.customTranslation("""{"translation":"Cat","from":"ja"}"""))
        assertEquals("Cat", OnlineTranslation.customTranslation("""{"data":{"translations":[{"translatedText":"Cat"}]}}"""))
        assertEquals("Cat", OnlineTranslation.customTranslation("""{"translations":[{"text":"Cat"}]}"""))
        assertEquals("Cat", OnlineTranslation.customTranslation("\"Cat\""))
        // Google's: the translated parts first in each
        assertEquals("It rains. Tomorrow.", OnlineTranslation.customTranslation("""[[["It rains. ","雨。",null],["Tomorrow.","明日。",null]],null,"ja"]"""))
        assertThrows(TranslationException::class.java) { OnlineTranslation.customTranslation("""{"status":"ok"}""") }
    }

    @Test
    fun noEmptyTranslations() {
        assertThrows(TranslationException::class.java) {
            OnlineTranslation(FakeHttp(200, "  ")).translate(TranslationService("custom", "https://example.com/?q={text}"), "猫", "en")
        }
    }
}
