package io.github.kvth.hiroi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {
    private val banks = mapOf(
        "Jitendex" to listOf(TagInfo("★", "popular", -10, "high priority entry", 2), TagInfo("name", "name", 0, "a name", 0), TagInfo("v1", "partOfSpeech", 0, "Ichidan verb", 0)),
        "JMdict" to listOf(TagInfo("★", "popular", 0, "common word", 5)),
    )
    private val find = { dictionary: String, names: Collection<String> -> banks[dictionary].orEmpty().filter { it.name in names } }

    @Test
    fun looksTagsUpInTheirDictionarysBankAsYomitanDoes() {
        assertEquals(
            listOf(
                Tags.Tag("★", "popular", -10, 2, listOf("high priority entry"), listOf("Jitendex")),
                // By the name before a colon, but shown with all of it
                Tags.Tag("name:place", "name", 0, 0, listOf("a name"), listOf("Jitendex")),
                // Not in the bank: in default, without notes
                Tags.Tag("rare", "default", 0, 0, emptyList(), listOf("Jitendex")),
                Tags.Tag("v1", "partOfSpeech", 0, 0, listOf("Ichidan verb"), listOf("Jitendex")),
            ),
            Tags.expand(find, "Jitendex", "v1 rare name:place ★ v1".split(' ')),
        )
    }

    @Test
    fun mergesTheSameTagOfSeveralDictionaries() {
        assertEquals(
            listOf(Tags.Tag("★", "popular", -10, 5, listOf("high priority entry", "common word"), listOf("Jitendex", "JMdict"))),
            Tags.expand(find, mapOf("Jitendex" to listOf("★"), "JMdict" to listOf("★"))),
        )
    }
}
