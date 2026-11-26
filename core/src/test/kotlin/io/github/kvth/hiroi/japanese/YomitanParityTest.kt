package io.github.kvth.hiroi.japanese

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin port must return exactly what Yomitan's own transformer did (yomitan-samples.json). */
class YomitanParityTest {
    @Test
    fun matchesYomitansOwnResults() {
        val samples = JSONObject(javaClass.getResourceAsStream("yomitan-samples.json")!!.bufferedReader().use { it.readText() })
        for (word in samples.keys()) {
            val expected = samples.getJSONArray(word).let { results ->
                List(results.length()) { i ->
                    val result = results.getJSONArray(i)
                    val trace = result.getJSONArray(1)
                    result.getString(0) to List(trace.length()) { trace.getString(it) }
                }
            }
            val actual = Deinflector.transformer.transform(word).map { result -> result.text to result.trace.map { it.transform } }
            assertEquals(word, expected, actual)
        }
    }
}
