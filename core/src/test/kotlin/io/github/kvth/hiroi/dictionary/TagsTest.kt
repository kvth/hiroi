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

    @Test
    fun marksPartOfSpeechTagsThatRepeatThePreviousDefinitionsOfTheirDictionary() {
        fun tags(dictionary: String, names: String) = dictionary to Tags.expand(find, dictionary, names.split(' '))
        val flagged = Tags.flagRedundant(
            listOf(tags("Jitendex", "v1 ★"), tags("Jitendex", "v1"), tags("Jitendex", "★"), tags("Jitendex", ""), tags("JMdict", "★")),
        )
        assertEquals(
            listOf(emptyList(), listOf("v1"), emptyList(), emptyList(), emptyList()),
            flagged.map { list -> list.filter { it.redundant }.map { it.name } },
        )
        // Another dictionary's begins anew: its first definition's are never repeats
        val other = Tags.flagRedundant(listOf(tags("Jitendex", "v1"), "Other" to listOf(Tags.Tag("v1", "partOfSpeech", 0, 0, emptyList(), listOf("Other")))))
        assertEquals(listOf(false, false), other.map { it.single().redundant })
    }
}
