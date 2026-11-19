package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrainTest {
    private val interval = 8_333_333L
    private val quick = 1_000_000L
    private val held = 7_000_000L

    @Test
    fun framesHandedOverQuicklyAreNeverSkipped() {
        val drain = QueueDrain()
        repeat(100) {
            drain.posted(quick, interval)
            assertFalse(drain.takeSkip())
        }
    }

    @Test
    fun oneHeldFrameIsNoReasonToSkip() {
        val drain = QueueDrain()
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(quick, interval)
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
    }

    @Test
    fun twoHeldFramesInARowSkipTheNextVsyncOnce() {
        val drain = QueueDrain()
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(held, interval)
        assertTrue(drain.takeSkip())
        assertFalse(drain.takeSkip())
    }

    @Test
    fun afterASkipItTakesTwoMoreHeldFrames() {
        val drain = QueueDrain()
        repeat(QueueDrain.HELD_FRAMES) { drain.posted(held, interval) }
        assertTrue(drain.takeSkip())
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(held, interval)
        assertTrue(drain.takeSkip())
    }

    @Test
    fun halfARefreshIsNotYetHeld() {
        val drain = QueueDrain()
        repeat(4) { drain.posted(interval / 2, interval) }
        assertFalse(drain.takeSkip())
    }

    @Test
    fun displayListenerSnapsRefreshIntervalImmediately() {
        val tracker = RefreshIntervalTracker(60f)
        assertEquals(16_666_666.0, tracker.intervalNanos.toDouble(), 100.0)

        for (hz in listOf(90f, 120f, 144f, 165f, 60f)) {
            tracker.onDisplayRefreshRate(hz)
            val expected = (1e9 / hz).toLong()
            assertEquals("for $hz Hz", expected.toDouble(), tracker.intervalNanos.toDouble(), 50.0)
            assertEquals("hz for $hz", hz.toDouble(), tracker.refreshHz.toDouble(), 0.1)
        }
        // Invalid values are ignored.
        tracker.onDisplayRefreshRate(0f)
        tracker.onDisplayRefreshRate(Float.NaN)
        assertEquals(60.0, tracker.refreshHz.toDouble(), 0.1)
    }

    @Test
    fun vsyncDeltasTrackLtpoTransitionsWithoutWaitingForTwoSecondWindow() {
        val tracker = RefreshIntervalTracker(120f)
        var t = 1_000_000_000L
        val ns120 = 8_333_333L
        val ns60 = 16_666_667L
        val ns90 = 11_111_111L
        val ns144 = 6_944_444L
        val ns165 = 6_060_606L

        // Steady 120 Hz
        repeat(5) {
            t += ns120
            tracker.onVsync(t)
        }
        assertEquals(ns120.toDouble(), tracker.intervalNanos.toDouble(), 50_000.0)

        // Single missed vsync at 120 Hz (16.67 ms gap followed by 8.33 ms) must NOT falsely switch to 60 Hz.
        t += ns60
        tracker.onVsync(t)
        assertEquals(ns120.toDouble(), tracker.intervalNanos.toDouble(), 50_000.0)
        t += ns120
        tracker.onVsync(t)
        assertEquals(ns120.toDouble(), tracker.intervalNanos.toDouble(), 50_000.0)

        // Genuine LTPO drop 120 -> 60 Hz confirms within CONFIRM_DELTAS (2) deltas (~33 ms).
        t += ns60
        tracker.onVsync(t)
        t += ns60
        tracker.onVsync(t)
        assertEquals(ns60.toDouble(), tracker.intervalNanos.toDouble(), 100_000.0)

        // 60 -> 90 -> 144 -> 165 Hz transitions each confirm within 2 deltas.
        for (target in listOf(ns90, ns144, ns165, ns120)) {
            repeat(RefreshIntervalTracker.CONFIRM_DELTAS) {
                t += target
                tracker.onVsync(t)
            }
            assertEquals("target=$target", target.toDouble(), tracker.intervalNanos.toDouble(), 150_000.0)
        }
    }

    @Test
    fun longPauseGapDoesNotCorruptTrackedInterval() {
        val tracker = RefreshIntervalTracker(120f)
        var t = 1_000_000_000L
        val ns120 = 8_333_333L
        repeat(3) {
            t += ns120
            tracker.onVsync(t)
        }
        // 500 ms pause gap is outside valid refresh range and ignored.
        t += 500_000_000L
        tracker.onVsync(t)
        assertEquals(ns120.toDouble(), tracker.intervalNanos.toDouble(), 50_000.0)
    }

    @Test
    fun queueDrainAvoidsFalseSkipsAfterLtpoDropTo60Hz() {
        val tracker = RefreshIntervalTracker(120f)
        val drain = QueueDrain()
        // Drop to 60 Hz via DisplayListener: a 5 ms post is well under half a 16.67 ms refresh (8.33 ms),
        // whereas with a stale 120 Hz interval (8.33 / 2 = 4.17 ms) it would have falsely skipped frames.
        tracker.onDisplayRefreshRate(60f)
        repeat(10) {
            drain.posted(5_000_000L, tracker.intervalNanos)
            assertFalse(drain.takeSkip())
        }
        // Jump to 144 Hz (6.94 ms): two 5 ms posts now exceed half a refresh (3.47 ms) and immediately drain.
        tracker.onDisplayRefreshRate(144f)
        drain.posted(5_000_000L, tracker.intervalNanos)
        assertFalse(drain.takeSkip())
        drain.posted(5_000_000L, tracker.intervalNanos)
        assertTrue(drain.takeSkip())
    }
}
