package io.github.kvth.hiroi.translation

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP answer: its status and body. */
class HttpResponse(val code: Int, val body: String)

/** Sends an HTTP request ([body] means POST with JSON); tests replace it. */
fun interface Http {
    fun request(url: String, headers: Map<String, String>, body: String?): HttpResponse

    companion object {
        val DEFAULT = Http { url, headers, body ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "Hiroi")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** The services that translate over the network: DeepL, LibreTranslate and custom URLs. */
class OnlineTranslation(private val http: Http = Http.DEFAULT) {

    /** [text] (Japanese) in [target] by [service]; throws [TranslationException] when there's none. */
    fun translate(service: TranslationService, text: String, target: String): String {
        val response = try {
            when (service.type) {
                "deepl" -> {
                    if (service.key.isBlank()) throw TranslationException("No DeepL API key")
                    http.request(deeplUrl(service.key), mapOf("Authorization" to "DeepL-Auth-Key ${service.key.trim()}"), deeplBody(text, target))
                }
                "libretranslate" -> http.request(libreUrl(service.url), emptyMap(), libreBody(text, target, service.key))
                "custom" -> http.request(customUrl(service.url, text, target), emptyMap(), null)
                else -> throw TranslationException("Not an online service: ${service.type}")
            }
        } catch (e: java.io.IOException) {
            throw TranslationException(e.message ?: e.javaClass.simpleName, e)
        }
        if (response.code !in 200..299) throw TranslationException(httpError(service.type, response))
        val translation = try {
            when (service.type) {
                "deepl" -> deeplTranslation(response.body)
                "libretranslate" -> JSONObject(response.body).getString("translatedText")
                else -> customTranslation(response.body)
            }
        } catch (e: org.json.JSONException) {
            throw TranslationException("Unexpected answer: ${response.body.take(200)}", e)
        }
        return translation.trim().ifEmpty { throw TranslationException("Empty translation") }
    }

    companion object {
        /** DeepL API Free keys end in ":fx" and have their own server. */
        fun deeplUrl(key: String) =
            if (key.trim().endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"

        /** DeepL's codes: English and Portuguese need a variant, Chinese its script. */
        fun deeplTarget(code: String) = when (code) {
            "en" -> "EN-US"
            "pt" -> "PT-BR"
            "zh" -> "ZH-HANS"
            else -> code.uppercase()
        }

        fun deeplBody(text: String, target: String): String = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", "JA")
            .put("target_lang", deeplTarget(target))
            .toString()

        fun deeplTranslation(body: String): String =
            JSONObject(body).getJSONArray("translations").let { list -> List(list.length()) { list.getJSONObject(it).getString("text") } }.joinToString("\n")

        /** A server's address, or its /translate endpoint as it is. */
        fun libreUrl(url: String): String {
            val base = url.trim().trimEnd('/')
            if (base.isEmpty()) throw TranslationException("No LibreTranslate server")
            return if (base.endsWith("/translate")) base else "$base/translate"
        }

        fun libreBody(text: String, target: String, key: String): String = JSONObject()
            .put("q", text)
            .put("source", "ja")
            .put("target", if (target == "zh") "zh-Hans" else target)
            .put("format", "text")
            .apply { if (key.isNotBlank()) put("api_key", key.trim()) }
            .toString()

        /** A custom URL with {text}, {source} and {target} filled in (URL-encoded). */
        fun customUrl(template: String, text: String, target: String): String =
            Regex("\\{(text|source|target)\\}").replace(template.trim()) { m ->
                val value = when (m.groupValues[1]) {
                    "text" -> text
                    "source" -> "ja"
                    else -> target
                }
                URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }

        /**
         * A custom URL's answer: plain text, or JSON with the translation in "translatedText",
         * "translation", "text" or "result" (also inside a list or one level down), or Google's
         * [[["translation", "original", ...], ...]].
         */
        fun customTranslation(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("\"")) return trimmed
            return findText(JSONTokener(trimmed).nextValue()) ?: throw TranslationException("No translation in the answer: ${trimmed.take(200)}")
        }

        private val TEXT_KEYS = listOf("translatedText", "translation", "translated", "text", "result", "data", "translations")

        private fun findText(value: Any?, depth: Int = 0): String? {
            if (depth > 4) return null
            return when (value) {
                is String -> value
                is JSONObject -> TEXT_KEYS.firstNotNullOfOrNull { key -> value.opt(key)?.let { findText(it, depth + 1) } }
                is JSONArray -> {
                    // Google's answer: a list of [translation, original, ...] parts
                    val first = value.opt(0)
                    if (first is JSONArray && first.opt(0) is JSONArray) {
                        List(first.length()) { (first.opt(it) as? JSONArray)?.opt(0) as? String }.filterNotNull().joinToString("").takeIf { it.isNotEmpty() }
                    } else {
                        findText(first, depth + 1)
                    }
                }
                else -> null
            }
        }

        private fun httpError(type: String, response: HttpResponse): String {
            val detail = runCatching { JSONObject(response.body).let { it.optString("message").ifEmpty { it.optString("error") } } }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: response.body.trim().takeUnless { it.startsWith("<") }?.take(200).orEmpty() // not an error page's HTML
            return when {
                type == "deepl" && response.code == 403 -> "DeepL refused the API key"
                type == "deepl" && response.code == 456 -> "DeepL: the month's quota is used up"
                response.code == 429 -> "Too many requests, try again later"
                else -> "HTTP ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}"
            }
        }
    }
}
