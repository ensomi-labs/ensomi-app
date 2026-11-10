package io.ensomi.android.chart

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a precise search ([PreciseSearch]) leaves with the chart it kept: how many [charts] it made, the seed it began
 * with ([startSeed], as text like [StoredChart.seed]), and the star the kept chart's own request carried
 * ([requestStars], from its receipt; the star the person asked for is [StoredChart.stars]).
 */
@Serializable
data class PreciseRecord(val charts: Int, val startSeed: String, val requestStars: Double)

/** What the app keeps of the last chart made for a song, beside its `.osu` and the runtime's receipt. */
@Serializable
data class StoredChart(
    val songId: String,
    /** The model's name and version, such as "Ensomi R2 r2-1.0". */
    val modelName: String,
    /**
     * The seed the generator used for this chart, from its receipt, as text: JSON numbers cannot hold every 64-bit
     * unsigned value.
     */
    val seed: String,
    /** The star asked for. */
    val stars: Double,
    val noteCount: Int,
    val holdCount: Int,
    /** The chart's length as the runtime gave it. */
    val durationMs: Int,
    /** The SHA-256 of the song's file it was made from. */
    val sha256: String? = null,
    val madeAtMs: Long = 0,
    /**
     * The star the chart came out at, the runtime's `starRating` of it: a number, so each screen words it in its own
     * language. Null in a record written by 0.2.1, which measured nothing; such a chart shows the star asked for.
     */
    val measuredStars: Double? = null,
    /** Set for a chart a precise search kept; null for a chart made in one pass, and in older records. */
    val precise: PreciseRecord? = null,
) {
    /**
     * The seed to ask with again, as the screens show it: this chart's own, or the one its precise search began with,
     * since asking again with that one repeats the search and so the chart.
     */
    val seedToReuse: String
        get() = precise?.startSeed ?: seed

    fun toMade(): MadeChart = MadeChart(
        noteCount = noteCount,
        holdCount = holdCount,
        seed = seedToReuse.toULongOrNull() ?: 0u,
        stars = stars,
        modelName = modelName,
        measuredStars = measuredStars,
        preciseCharts = precise?.charts,
    )
}

/**
 * The last chart made for each song, in app storage (`files/charts/<song id>/`): `chart.osu`, the runtime's
 * `receipt.json`, and `chart.json`, the app's record, written last so a chart is there only once all three are. A new
 * chart for the song replaces the one before; so a game can be played again after the process was killed.
 *
 * Blocking file work: call it off the main thread.
 */
class ChartStore(private val root: File) {
    private val lock = Any()
    private val mutableIds = MutableStateFlow(readIds())

    /** The songs that have a chart. */
    val songIds: StateFlow<Set<String>> = mutableIds.asStateFlow()

    fun save(chart: StoredChart, osu: String, receiptJson: String) {
        synchronized(lock) {
            val dir = directory(chart.songId)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not make $dir")
            // The record goes first: a chart half replaced has none, and reads as no chart.
            File(dir, RECORD).delete()
            writeAtomically(File(dir, OSU), osu)
            writeAtomically(File(dir, RECEIPT), receiptJson)
            writeAtomically(File(dir, RECORD), json.encodeToString(StoredChart.serializer(), chart))
            mutableIds.value = mutableIds.value + chart.songId
        }
    }

    fun chart(songId: String): StoredChart? {
        synchronized(lock) {
            val file = File(directory(songId), RECORD)
            if (!file.isFile) return null
            return runCatching { json.decodeFromString(StoredChart.serializer(), file.readText()) }.getOrNull()
                ?.takeIf { it.songId == songId && File(directory(songId), OSU).isFile }
        }
    }

    fun osu(songId: String): String? {
        synchronized(lock) {
            if (chart(songId) == null) return null
            return runCatching { File(directory(songId), OSU).readText() }.getOrNull()
        }
    }

    fun remove(songId: String) {
        synchronized(lock) {
            directory(songId).deleteRecursively()
            mutableIds.value = mutableIds.value - songId
        }
    }

    private fun directory(songId: String): File {
        require(songId.isNotEmpty() && songId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "A song id names a directory: $songId"
        }
        return File(root, songId)
    }

    private fun readIds(): Set<String> =
        root.listFiles()?.filter { File(it, RECORD).isFile && File(it, OSU).isFile }?.map { it.name }?.toSet().orEmpty()

    private fun writeAtomically(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not write $file")
        }
    }

    private companion object {
        const val OSU = "chart.osu"
        const val RECEIPT = "receipt.json"
        const val RECORD = "chart.json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * The [limit] latest values by key, oldest dropped first; a value read counts as used. Dropped values are closed
 * unless [close] says otherwise. Thread-safe.
 */
class RecentValues<K, V>(private val limit: Int, private val close: (V) -> Unit = {}) {
    private val values = LinkedHashMap<K, V>(limit + 1, 0.75f, true)

    @Synchronized
    fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V) {
        val previous = values.put(key, value)
        if (previous != null && previous !== value) close(previous)
        while (values.size > limit) {
            val oldest = values.entries.first()
            values.remove(oldest.key)
            close(oldest.value)
        }
    }

    @Synchronized
    fun clear() {
        values.values.forEach(close)
        values.clear()
    }

    @Synchronized
    fun keys(): List<K> = values.keys.toList()
}
