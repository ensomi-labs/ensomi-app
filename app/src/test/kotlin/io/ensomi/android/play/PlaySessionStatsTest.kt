package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionStatsTest {
    @Test
    fun emptySessionReturnsNull() {
        val stats = PlaySessionStats()
        assertNull(stats.snapshot())
    }

    @Test
    fun accumulatesWindowsPercentilesAndJournalDetail() {
        val stats = PlaySessionStats()
        // 98 frames at 1.2 ms, 2 outlier frames at 6.5 ms -> p50 ≈ 1.25 ms, p99 ≈ 6.55 ms.
        repeat(98) { stats.recordWork(1_200_000L) }
        repeat(2) { stats.recordWork(6_500_000L) }
        stats.recordWindow(
            frames = 60,
            elapsedNanos = 500_000_000L,
            refreshHz = 120f,
            missed = 1,
            late = 2,
            overran = 3,
            drained = 1,
            allocatedBytes = 60L * 4 * 1024,
        )
        stats.recordWindow(
            frames = 40,
            elapsedNanos = 500_000_000L,
            refreshHz = 120f,
            missed = 0,
            late = 1,
            overran = 0,
            drained = 2,
            allocatedBytes = 40L * 4 * 1024,
        )

        val summary = stats.snapshot()!!
        assertEquals(100L, summary.frames)
        assertEquals(100.0, summary.fps, 0.1)
        assertEquals(120.0, summary.refreshHz, 0.1)
        assertEquals(1L, summary.missed)
        assertEquals(3L, summary.late)
        assertEquals(3L, summary.overran)
        assertEquals(3L, summary.drained)
        assertEquals(4.0, summary.allocKbPerFrame, 0.05)
        assertEquals(1.25, summary.workP50Ms, 0.1)
        assertEquals(6.55, summary.workP99Ms, 0.1)

        val detail = summary.toJournalDetail(clock = null, underruns = 2, routeType = "speaker")
        val expectedKeys = setOf(
            "fps", "refreshHz", "missed", "late", "overran", "drained",
            "workP50Ms", "workP99Ms", "allocKbPerFrame",
            "clockReads", "clockStepsBack", "clockFurthestMs", "underruns", "routeType",
        )
        assertEquals(expectedKeys, detail.keys)
        assertEquals("100.0", detail["fps"])
        assertEquals("120.0", detail["refreshHz"])
        assertEquals("2", detail["underruns"])
        assertEquals("speaker", detail["routeType"])
        assertTrue(detail["workP99Ms"]!!.toDouble() >= 6.0)
    }
}
