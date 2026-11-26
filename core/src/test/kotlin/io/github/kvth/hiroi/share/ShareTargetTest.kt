package io.github.kvth.hiroi.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTargetTest {
    @Test
    fun linksHaveTheTextEncoded() {
        val deepl = ShareTarget.PRESETS.first { it.name == "DeepL" }
        assertEquals("https://www.deepl.com/translator#ja/de/%E7%8C%AB%E3%81%A8%2F%20%E7%8A%AC", deepl.link("猫と/ 犬", "de", "Deutsch"))
        val google = ShareTarget.PRESETS.first { it.name == "Google Translate" }
        assertEquals("https://translate.google.com/?sl=ja&tl=en&text=%E7%8C%AB&op=translate", google.link("猫", "en", "English"))
    }

    @Test
    fun promptsGoAroundTheText() {
        val chatGpt = ShareTarget.PRESETS.first { it.name == "ChatGPT" }
        val message = chatGpt.message("猫が好き", "English")
        assertEquals("Explain this Japanese text in English: its meaning, vocabulary and grammar.\n\n猫が好き", message)
        assertEquals("https://chatgpt.com/?q=" + ShareTarget.encode(message), chatGpt.link("猫が好き", "en", "English"))
        // An empty template sends the text as it is
        assertEquals("猫", ShareTarget("app", "Notes", "a/b", "").message("猫", "English"))
    }

    @Test
    fun keptAsJson() {
        val target = ShareTarget("app", "Notes", "com.example/.Share", "Q: {text}", "android.intent.action.SEND")
        assertEquals(target, ShareTarget.fromJson(JSONObject(target.toJson().toString())))
        // Older or hand-made entries without a template send the text
        assertEquals("{text}", ShareTarget.fromJson(JSONObject("""{"type":"url","name":"X","url":"https://x/{text}"}""")).template)
    }
}
