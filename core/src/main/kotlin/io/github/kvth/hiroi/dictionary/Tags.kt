package io.github.kvth.hiroi.dictionary

/**
 * Tags as Yomitan's translator expands them (_expandTagGroups, _groupTags): each name looked up
 * in its dictionary's tag bank by the part before a colon ("name:place" → "name"), a name the bank
 * doesn't have in the "default" category; the same name and category from several dictionaries
 * merged into one, then all sorted by order and name.
 */
object Tags {
    /** A tag as Yomitan's results and Anki templates have it: [notes] from each of its [dictionaries] that has some */
    data class Tag(val name: String, val category: String, val order: Int, val score: Int, val notes: List<String>, val dictionaries: List<String>)

    /** The tags [names] of [dictionary]; [find] is [DictionaryStore.findTags] */
    fun expand(find: (dictionary: String, names: Collection<String>) -> List<TagInfo>, dictionary: String, names: List<String>): List<Tag> =
        expand(find, mapOf(dictionary to names))

    /** The tags named by each dictionary of [names], merged and sorted */
    fun expand(find: (dictionary: String, names: Collection<String>) -> List<TagInfo>, names: Map<String, List<String>>): List<Tag> {
        val tags = ArrayList<Tag>()
        for ((dictionary, list) in names) {
            val wanted = list.filter { it.isNotBlank() }.distinct()
            if (wanted.isEmpty()) continue
            val known = find(dictionary, wanted.map(::base).distinct()).groupBy { it.name }
            for (name in wanted) {
                val info = known[base(name)]?.first()
                val tag = Tag(
                    name, info?.category?.ifEmpty { null } ?: "default", info?.order ?: 0, info?.score ?: 0,
                    listOfNotNull(info?.notes?.ifEmpty { null }), listOf(dictionary),
                )
                val same = tags.indexOfFirst { it.name == tag.name && it.category == tag.category }
                if (same < 0) {
                    tags += tag
                } else {
                    val other = tags[same]
                    tags[same] = other.copy(
                        order = minOf(other.order, tag.order), score = maxOf(other.score, tag.score),
                        notes = (other.notes + tag.notes).distinct(), dictionaries = other.dictionaries + dictionary,
                    )
                }
            }
        }
        return tags.sortedWith(compareBy<Tag> { it.order }.thenBy { it.name })
    }

    /** What Yomitan looks a tag up by: its name up to a colon */
    private fun base(name: String) = name.substringBefore(':')
}
