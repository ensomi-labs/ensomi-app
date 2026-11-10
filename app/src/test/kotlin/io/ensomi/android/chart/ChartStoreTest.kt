package io.ensomi.android.chart

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartStoreTest {
    private fun root(): File = Files.createTempDirectory("charts").toFile()

    private fun chart(songId: String, seed: ULong = 18_446_744_073_709_551_615u) = StoredChart(
        songId = songId,
        modelName = "Ensomi R2 r2-1.0",
        seed = seed.toString(),
        stars = 3.5,
        noteCount = 1229,
        holdCount = 230,
        durationMs = 222_000,
        sha256 = "ab".repeat(32),
    )

    @Test
    fun keepsTheLastChartOfASongAcrossStores() {
        val root = root()
        val store = ChartStore(root)
        store.save(chart("song-1", seed = 1u), osu = "osu one", receiptJson = "{\"seed\":1}")
        store.save(chart("song-1"), osu = "osu two", receiptJson = "{}")
        val again = ChartStore(root)
        assertEquals(setOf("song-1"), again.songIds.value)
        assertEquals(chart("song-1"), again.chart("song-1"))
        assertEquals("osu two", again.osu("song-1"))
        // The largest 64-bit seed survives the trip through JSON.
        assertEquals(18_446_744_073_709_551_615u, again.chart("song-1")!!.toMade().seed)
    }

    @Test
    fun aChartWithoutItsRecordIsNoChart() {
        val root = root()
        val store = ChartStore(root)
        store.save(chart("song-2"), osu = "osu", receiptJson = "{}")
        // A save cut off before its record was written.
        File(root, "song-2/chart.json").delete()
        assertNull(ChartStore(root).chart("song-2"))
        assertNull(ChartStore(root).osu("song-2"))
        assertTrue(ChartStore(root).songIds.value.isEmpty())
    }

    @Test
    fun removeForgetsTheSongsChart() {
        val store = ChartStore(root())
        store.save(chart("song-3"), osu = "osu", receiptJson = "{}")
        store.remove("song-3")
        assertNull(store.chart("song-3"))
        assertTrue(store.songIds.value.isEmpty())
    }

    @Test
    fun keepsTheStarAChartCameOutAt() {
        val root = root()
        ChartStore(root).save(chart("song-4").copy(stars = 5.5, measuredStars = 4.6), osu = "osu", receiptJson = "{}")
        val kept = ChartStore(root).chart("song-4")!!
        assertEquals(5.5, kept.stars, 0.0)
        assertEquals(4.6, kept.measuredStars!!, 0.0)
        assertEquals(4.6, kept.toMade().measuredStars!!, 0.0)
        assertEquals(5.5, kept.toMade().stars, 0.0)
    }

    @Test
    fun aRecordWrittenBeforeChartsWereMeasuredReadsWithoutAMeasuredStar() {
        val root = root()
        val directory = File(root, "song-5").apply { mkdirs() }
        File(directory, "chart.osu").writeText("osu")
        File(directory, "receipt.json").writeText("{}")
        // chart.json as 0.2.1 wrote it: every field it had, and no measuredStars.
        File(directory, "chart.json").writeText(
            """{"songId":"song-5","modelName":"Ensomi R2 r2-1.0.1","seed":"2914","stars":4.5,"noteCount":1229,""" +
                """"holdCount":230,"durationMs":222000,"sha256":null,"madeAtMs":1762700000000}""",
        )
        val store = ChartStore(root)
        assertEquals(setOf("song-5"), store.songIds.value)
        val kept = store.chart("song-5")!!
        assertNull(kept.measuredStars)
        assertEquals(4.5, kept.stars, 0.0)
        assertNull(kept.toMade().measuredStars)
        assertEquals("osu", store.osu("song-5"))
        // Such a chart shows the star asked for alone, as 0.2.1 did.
        assertEquals(StarDisplay(4.5, null, StarGap.NONE), StarDisplay.of(kept.stars, kept.measuredStars))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSongIdCannotLeaveTheDirectory() {
        ChartStore(root()).chart("../settings")
    }

    @Test
    fun recentValuesDropTheLeastRecentlyUsedAndCloseIt() {
        val closed = mutableListOf<String>()
        val values = RecentValues<String, String>(limit = 2) { closed += it }
        values.put("a", "A")
        values.put("b", "B")
        assertEquals("A", values.get("a"))
        values.put("c", "C")
        // "b" was used least recently: "a" was read after it.
        assertEquals(listOf("B"), closed)
        assertEquals(listOf("a", "c"), values.keys())
        values.put("a", "A2")
        assertEquals(listOf("B", "A"), closed)
    }
}
