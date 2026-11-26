package io.github.kvth.hiroi.audio

import io.github.kvth.hiroi.japanese.Japanese

/**
 * A recording in the local audio database: its [source] (nhk16, jpod, forvo…), [speaker], what
 * the add-on shows with it ([display]: a Forvo speaker, an NHK accent…), its [file] in the
 * database and the [reading] it's for (null: any).
 */
data class LocalRecording(val source: String, val speaker: String?, val display: String?, val file: String, val reading: String? = null)

/** The local audio database's contents (Android's SQLite reads it on the phone, a fake in tests) */
interface LocalAudioStore {
    /** The recordings of [term], for one of [readings] or for any */
    fun recordings(term: String, readings: List<String>): List<LocalRecording>

    /** A recording's bytes, null if the database lacks them */
    fun data(source: String, file: String): ByteArray?

    /** How many recordings each source has */
    fun counts(): Map<String, Int>
}

/**
 * Word audio from the database of the "Local Audio Server for Yomitan" Anki add-on
 * (local-audio-yomichan), as its Android version (android.db) has it: the entries table
 * (expression, reading, source, speaker, display, file) and the android table with each file's
 * bytes. Its recordings are found as the add-on's server finds them, without a server.
 */
object LocalAudio {
    /** How an [AudioInfo]'s URL says it's a recording in the database: local-audio:source/file */
    const val SCHEME = "local-audio:"

    /** The add-on's sources in its default order */
    val DEFAULT_ORDER = listOf("nhk16", "shinmeikai8", "forvo", "jpod", "jpod_alternate", "ozk5")

    /** How the add-on names each source's recordings (default_config.json); %s is a recording's display */
    private val NAMES = mapOf(
        "nhk16" to "NHK16 %s", "shinmeikai8" to "SMK8 %s", "forvo" to "Forvo (%s)",
        "jpod" to "JPod101", "jpod_alternate" to "JPod101 Alt", "ozk5" to "OZK5 %s",
    )

    /** A source's name without a recording's details: NHK16, Forvo */
    fun sourceName(source: String): String = (NAMES[source] ?: source).replace(Regex("\\s*\\(?%s\\)?"), "")

    /** A recording's name, as the add-on shows it: "NHK16 …", "Forvo (akitomo)" */
    fun name(recording: LocalRecording): String {
        val format = NAMES[recording.source] ?: "${recording.source} %s"
        val display = recording.display?.takeIf { it.isNotBlank() } ?: return sourceName(recording.source)
        return format.replace("%s", display)
    }

    /** [recordings] in [order] of their sources (others after, by name), each source's by its order in the database */
    fun sorted(recordings: List<LocalRecording>, order: List<String>): List<LocalRecording> =
        recordings.withIndex().sortedWith(
            compareBy<IndexedValue<LocalRecording>>({ order.indexOf(it.value.source).let { i -> if (i < 0) order.size else i } }, { it.value.source }, { it.index }),
        ).map { it.value }

    /** The readings to look a word up by: as given, and in the other kana (the database has some in katakana) */
    fun readings(reading: String): List<String> =
        if (reading.isEmpty()) emptyList() else listOf(reading, Japanese.toKatakana(reading), Japanese.toHiragana(reading)).distinct()

    fun url(recording: LocalRecording): String = SCHEME + recording.source + "/" + recording.file

    /** The source and file of a recording's [url], null if it isn't one */
    fun parse(url: String): Pair<String, String>? {
        if (!url.startsWith(SCHEME)) return null
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/').takeIf { it > 0 } ?: return null
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }
}
