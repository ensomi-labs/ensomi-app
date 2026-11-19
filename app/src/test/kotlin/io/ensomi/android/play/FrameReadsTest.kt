package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameReadsTest {
    @Test
    fun aListHoldsUntilItsTimeRunsOut() {
        val window = VisibleWindow()
        assertTrue(window.mustAsk(1_000.0, 459.0))
        window.asked(1_000.0, 459.0)
        assertFalse(window.mustAsk(1_000.0, 459.0))
        assertFalse(window.mustAsk(1_000.0 + VisibleWindow.REUSE_MS, 459.0))
        assertTrue(window.mustAsk(1_000.0 + VisibleWindow.REUSE_MS + 1, 459.0))
    }

    @Test
    fun anInputOrAnEventMakesANewList() {
        val window = VisibleWindow()
        window.asked(1_000.0, 459.0)
        window.invalidate()
        assertTrue(window.mustAsk(1_000.0, 459.0))
        window.asked(1_000.0, 459.0)
        assertFalse(window.mustAsk(1_008.0, 459.0))
    }

    @Test
    fun aRestartOrAnotherScrollTimeMakesANewList() {
        val window = VisibleWindow()
        window.asked(5_000.0, 459.0)
        assertTrue(window.mustAsk(-2_000.0, 459.0))
        assertTrue(window.mustAsk(5_000.0, 500.0))
    }

    @Test
    fun aListReachesAsFarAsItsLastFrameNeeds() {
        val window = VisibleWindow()
        val lastFrame = 1_000.0 + VisibleWindow.REUSE_MS
        assertEquals(lastFrame + 459.0 + PlayController.VISIBLE_AFTER_MS, window.upperMs(1_000.0, 459.0), 1e-9)
        assertEquals(1_000.0 - PlayController.VISIBLE_BEFORE_MS, window.lowerMs(1_000.0), 1e-9)
    }

    @Test
    fun aDarkLaneIsNotAskedUntilItsNextInput() {
        val lanes = LaneAsking()
        assertFalse(lanes.mustAsk(2))
        lanes.touched(2)
        assertTrue(lanes.mustAsk(2))
        lanes.answered(2, isLit = true)
        assertTrue(lanes.mustAsk(2))
        lanes.answered(2, isLit = false)
        assertFalse(lanes.mustAsk(2))
        assertFalse(lanes.mustAsk(1))
    }

    @Test
    fun aRestartLeavesEveryLaneDark() {
        val lanes = LaneAsking()
        lanes.touched(0)
        lanes.answered(1, isLit = true)
        lanes.reset()
        assertFalse(lanes.mustAsk(0))
        assertFalse(lanes.mustAsk(1))
    }

    /** The runtime's rule as `crates/gameplay/src/layout.rs::y_position` has it, 18 short of the top. */
    private fun runtimeY(objectMs: Double, renderMs: Double, scrollTimeMs: Double, receptorY: Double): Double {
        val travelHeight = maxOf(receptorY - 18.0, 1.0)
        val progress = (objectMs - renderMs) / maxOf(scrollTimeMs, 1.0)
        return receptorY - progress * travelHeight
    }

    /** Direct reference translation of `crates/gameplay/src/layout.rs::geometry` for holds. */
    private data class ReferenceHold(val bodyTopY: Double, val bodyBottomY: Double, val headY: Double, val tailY: Double?)

    private fun referenceHoldGeometry(
        startTimeMs: Double,
        endTimeMs: Double?,
        holding: Boolean,
        renderMs: Double,
        scrollTimeMs: Double,
        receptorY: Double,
    ): ReferenceHold {
        fun displayedY(timeMs: Double): Double {
            val rawY = runtimeY(timeMs, renderMs, scrollTimeMs, receptorY)
            return if (holding) minOf(rawY, receptorY) else rawY
        }
        val headY = displayedY(startTimeMs)
        val tailY = endTimeMs?.let(::displayedY)
        val rawTopY = if (tailY == null) minOf(0.0, headY) else minOf(headY, tailY)
        val rawBottomY = if (tailY == null) maxOf(0.0, headY) else maxOf(headY, tailY)
        val bodyHeight = maxOf(rawBottomY - rawTopY, 12.0)
        return ReferenceHold(
            bodyTopY = rawBottomY - bodyHeight,
            bodyBottomY = rawBottomY,
            headY = headY,
            tailY = tailY,
        )
    }

    @Test
    fun theLineIsTheRuntimesAnswerAtAnyTime() {
        val line = TapLine(::runtimeY)
        for (renderMs in listOf(-1_865.0, 0.0, 3_141.59, 157_313.07)) {
            for (ahead in listOf(-700.0, 0.0, 1.0, 229.5, 459.0, 709.0)) {
                val expected = runtimeY(renderMs + ahead, renderMs, 459.0, 1_400.0)
                assertEquals(expected, line.y(renderMs + ahead, renderMs, 459.0, 1_400.0), 1e-6)
            }
        }
    }

    @Test
    fun theLineAsksAgainOnlyWhenTheScrollTimeOrTheFallChanges() {
        var asked = 0
        val line = TapLine { objectMs, renderMs, scrollTimeMs, receptorY ->
            asked++
            runtimeY(objectMs, renderMs, scrollTimeMs, receptorY)
        }
        line.y(1_000.0, 900.0, 459.0, 1_400.0)
        val firstFit = asked
        line.y(2_000.0, 1_900.0, 459.0, 1_400.0)
        line.y(3_000.0, 2_950.0, 459.0, 1_400.0)
        assertEquals(firstFit, asked)
        assertEquals(runtimeY(1_000.0, 900.0, 300.0, 1_400.0), line.y(1_000.0, 900.0, 300.0, 1_400.0), 1e-6)
        assertEquals(firstFit * 2, asked)
        assertEquals(runtimeY(1_000.0, 900.0, 300.0, 900.0), line.y(1_000.0, 900.0, 300.0, 900.0), 1e-6)
        assertEquals(firstFit * 3, asked)
    }

    @Test
    fun holdGeometryMatchesLayoutRsAcrossStatesLengthsAndSpeeds() {
        var calls = 0
        val layout = NoteLayout { objectMs, renderMs, scrollTimeMs, receptorY ->
            calls++
            runtimeY(objectMs, renderMs, scrollTimeMs, receptorY)
        }
        val out = HoldPlacement()
        val scrollSpeeds = listOf(0.5, 115.0, 300.0, 459.0, 1_148.5)
        val fallLengths = listOf(10.0, 18.0, 300.0, 1_400.0, 3_500.0)
        val renderTimes = listOf(-1_865.25, 0.0, 12_345.678, 157_313.07)
        // Covers normal holds, zero-length holds, ultra-short holds (< 12 px), clipped/past-receptor holds,
        // above-far-end holds, and open-ended holds (endTimeMs == null).
        val headOffsets = listOf(-700.0, -50.0, 0.0, 1.0, 150.0, 600.0)
        val durations: List<Double?> = listOf(null, 0.0, 0.5, 5.0, 120.0, 900.0)

        var calibrations = 0
        for (scrollTimeMs in scrollSpeeds) {
            for (fallLength in fallLengths) {
                calibrations++
                for (holding in listOf(false, true)) {
                    for (renderMs in renderTimes) {
                        for (headOffset in headOffsets) {
                            val startMs = renderMs + headOffset
                            for (duration in durations) {
                                val endMs = duration?.let { startMs + it }
                                layout.hold(startMs, endMs, holding, renderMs, scrollTimeMs, fallLength, out)
                                val expected = referenceHoldGeometry(startMs, endMs, holding, renderMs, scrollTimeMs, fallLength)
                                assertEquals("headY", expected.headY, out.headY, 1e-6)
                                assertEquals("bodyTopY", expected.bodyTopY, out.bodyTopY, 1e-6)
                                assertEquals("bodyBottomY", expected.bodyBottomY, out.bodyBottomY, 1e-6)
                                assertEquals("hasTail", expected.tailY != null, out.hasTail)
                                if (expected.tailY != null) {
                                    assertEquals("tailY", expected.tailY, out.tailY, 1e-6)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Only two calibration calls per (scrollTimeMs, fallLength) pair; zero per note.
        assertEquals(calibrations * 2, calls)
    }

    @Test
    fun holdSpecificCasesMatchExactRules() {
        val layout = NoteLayout(::runtimeY)
        val out = HoldPlacement()
        val receptorY = 1_000.0

        // HOLDING clamps head past receptor to receptorY while tail stays above receptor.
        layout.hold(startTimeMs = 900.0, endTimeMs = 1_200.0, holding = true, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertEquals(receptorY, out.headY, 1e-9)
        assertTrue(out.hasTail)
        assertTrue(out.tailY < receptorY)
        assertEquals(out.tailY, out.bodyTopY, 1e-9)
        assertEquals(receptorY, out.bodyBottomY, 1e-9)

        // HOLDING with both head and tail past receptor clamps both to receptorY and enforces 12 px minimum body height.
        layout.hold(startTimeMs = 800.0, endTimeMs = 950.0, holding = true, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertEquals(receptorY, out.headY, 1e-9)
        assertEquals(receptorY, out.tailY, 1e-9)
        assertEquals(receptorY - NoteLayout.MINIMUM_HOLD_BODY_HEIGHT, out.bodyTopY, 1e-9)
        assertEquals(receptorY, out.bodyBottomY, 1e-9)

        // WAITING / MISSED_BUT_VISIBLE does not clamp past receptorY.
        layout.hold(startTimeMs = 900.0, endTimeMs = 1_200.0, holding = false, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertTrue(out.headY > receptorY)
        assertEquals(out.headY, out.bodyBottomY, 1e-9)

        // Zero-length hold uses MINIMUM_HOLD_BODY_HEIGHT above headY.
        layout.hold(startTimeMs = 1_200.0, endTimeMs = 1_200.0, holding = false, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertEquals(out.headY, out.tailY, 1e-9)
        assertEquals(out.headY - NoteLayout.MINIMUM_HOLD_BODY_HEIGHT, out.bodyTopY, 1e-9)
        assertEquals(out.headY, out.bodyBottomY, 1e-9)

        // OPEN_ENDED (no tail) spans from far end (0.0) to headY when on screen.
        layout.hold(startTimeMs = 1_200.0, endTimeMs = null, holding = false, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertFalse(out.hasTail)
        assertEquals(0.0, out.bodyTopY, 1e-9)
        assertEquals(out.headY, out.bodyBottomY, 1e-9)

        // OPEN_ENDED above the far end (headY < 0.0) spans headY..0.0 with minimum 12 px height.
        layout.hold(startTimeMs = 2_000.0, endTimeMs = null, holding = false, renderMs = 1_000.0, scrollTimeMs = 500.0, fallLength = receptorY, out = out)
        assertFalse(out.hasTail)
        assertTrue(out.headY < 0.0)
        assertEquals(out.headY, out.bodyTopY, 1e-9)
        assertEquals(0.0, out.bodyBottomY, 1e-9)
    }
}
