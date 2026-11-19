package io.ensomi.android.play

import kotlin.math.abs

/**
 * Keeps the surface's buffer queue from staying a frame too deep. A frame that reaches SurfaceFlinger late can leave one
 * more buffer queued than the display takes, and since every vsync brings a new frame nothing takes it out again: each
 * frame then waits most of a refresh in HWUI for a free buffer, and is shown a refresh later than it was drawn for, until
 * a frame is skipped. So when HWUI holds [HELD_FRAMES] frames in a row for over half a refresh, the next vsync draws
 * nothing. Used on the render thread.
 */
class QueueDrain {
    private var heldInARow = 0
    private var skipNext = false

    /** A frame was handed to HWUI, which held it for [postNanos]. */
    fun posted(postNanos: Long, intervalNanos: Long) {
        if (postNanos <= intervalNanos / 2) {
            heldInARow = 0
        } else if (++heldInARow >= HELD_FRAMES) {
            heldInARow = 0
            skipNext = true
        }
    }

    /** Whether this vsync draws nothing; asked once per vsync. */
    fun takeSkip(): Boolean {
        val skip = skipNext
        skipNext = false
        return skip
    }

    companion object {
        const val HELD_FRAMES = 2
    }
}

/**
 * Tracks the display's active refresh interval on the render thread without per-frame allocations so [QueueDrain] and
 * [FrameStats] react immediately to LTPO / VRR switches (60↔90↔120↔144/165 Hz) rather than waiting up to two seconds
 * for a stats window.
 *
 * Combines two signals:
 * 1. Explicit `DisplayManager.DisplayListener` updates ([onDisplayRefreshRate]), which snap the interval immediately.
 * 2. Live `Choreographer` `vsyncNanos` deltas ([onVsync]), which smooth fine clock differences within ±15 % and switch
 *    after [CONFIRM_DELTAS] consecutive deltas at a new refresh rate while ignoring isolated missed vsyncs or pauses.
 */
class RefreshIntervalTracker(initialRefreshHz: Float = 60f) {
    private var lastDisplayHz: Float = clampHz(initialRefreshHz)

    /** Best current estimate of one display refresh period, in nanoseconds. */
    var intervalNanos: Long = hzToNanos(lastDisplayHz)
        private set

    val refreshHz: Float
        get() = (1e9 / intervalNanos).toFloat()

    private var lastVsyncNanos = 0L
    private var pendingIntervalNanos = 0L
    private var pendingCount = 0

    /** Applies a refresh-rate update from `Display.getRefreshRate()` / `DisplayListener`, snapping immediately on change. */
    fun onDisplayRefreshRate(refreshHz: Float) {
        if (!refreshHz.isFinite() || refreshHz < MIN_HZ || refreshHz > MAX_HZ) return
        if (abs(refreshHz - lastDisplayHz) < DISPLAY_CHANGE_EPSILON_HZ) return
        lastDisplayHz = refreshHz
        intervalNanos = hzToNanos(refreshHz)
        pendingCount = 0
    }

    /** Records one Choreographer vsync timestamp and returns the current active refresh interval in nanoseconds. */
    fun onVsync(vsyncNanos: Long): Long {
        if (lastVsyncNanos == 0L) {
            lastVsyncNanos = vsyncNanos
            return intervalNanos
        }
        val delta = vsyncNanos - lastVsyncNanos
        lastVsyncNanos = vsyncNanos
        if (delta < MIN_INTERVAL_NANOS || delta > MAX_INTERVAL_NANOS) {
            pendingCount = 0
            return intervalNanos
        }
        if (withinTolerance(delta, intervalNanos)) {
            intervalNanos = (intervalNanos * 3 + delta) / 4
            pendingCount = 0
        } else if (pendingCount > 0 && withinTolerance(delta, pendingIntervalNanos)) {
            pendingIntervalNanos = (pendingIntervalNanos + delta) / 2
            pendingCount++
            if (pendingCount >= CONFIRM_DELTAS) {
                intervalNanos = pendingIntervalNanos
                pendingCount = 0
            }
        } else {
            pendingIntervalNanos = delta
            pendingCount = 1
        }
        return intervalNanos
    }

    /** Clears vsync delta history across a surface pause or recreation. */
    fun resetVsync() {
        lastVsyncNanos = 0L
        pendingCount = 0
    }

    private fun withinTolerance(value: Long, reference: Long): Boolean =
        abs(value - reference) * 100L <= reference * TOLERANCE_PERCENT

    companion object {
        const val MIN_HZ = 40f
        const val MAX_HZ = 250f
        const val MIN_INTERVAL_NANOS = 4_000_000L // 250 Hz
        const val MAX_INTERVAL_NANOS = 25_000_000L // 40 Hz
        const val CONFIRM_DELTAS = 2
        private const val TOLERANCE_PERCENT = 8L
        private const val DISPLAY_CHANGE_EPSILON_HZ = 0.5f

        private fun clampHz(hz: Float): Float = if (hz.isFinite()) hz.coerceIn(MIN_HZ, MAX_HZ) else 60f

        private fun hzToNanos(hz: Float): Long = (1e9 / clampHz(hz)).toLong()
    }
}
