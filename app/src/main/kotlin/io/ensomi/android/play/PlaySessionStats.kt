package io.ensomi.android.play

import io.ensomi.android.audio.AudioPlayClock
import java.util.Locale

/** Aggregated render-thread performance summary for one play session, with no filenames or PII. */
data class PlayFrameSummary(
    val frames: Long,
    val fps: Double,
    val refreshHz: Double,
    val missed: Long,
    val late: Long,
    val overran: Long,
    val drained: Long,
    val workP50Ms: Double,
    val workP99Ms: Double,
    val allocKbPerFrame: Double,
) {
    /** Compact technical context map for the `play_summary` diagnostics journal entry. */
    fun toJournalDetail(
        clock: AudioPlayClock?,
        underruns: Int,
        routeType: String,
    ): Map<String, String> = linkedMapOf(
        "fps" to String.format(Locale.ROOT, "%.1f", fps),
        "refreshHz" to String.format(Locale.ROOT, "%.1f", refreshHz),
        "missed" to missed.toString(),
        "late" to late.toString(),
        "overran" to overran.toString(),
        "drained" to drained.toString(),
        "workP50Ms" to String.format(Locale.ROOT, "%.2f", workP50Ms),
        "workP99Ms" to String.format(Locale.ROOT, "%.2f", workP99Ms),
        "allocKbPerFrame" to String.format(Locale.ROOT, "%.1f", allocKbPerFrame),
        "clockReads" to (clock?.reads ?: 0).toString(),
        "clockStepsBack" to (clock?.stepsBack ?: 0).toString(),
        "clockFurthestMs" to String.format(Locale.ROOT, "%.1f", clock?.furthestMs ?: 0.0),
        "underruns" to underruns.toString(),
        "routeType" to routeType,
    )
}

/**
 * Accumulates session-wide frame pacing metrics across surfaces and stats windows with bounded memory and zero
 * per-frame allocations. Per-frame work durations go into a fixed 200-bucket histogram (0.1 ms resolution up to 10 ms,
 * 0.5 ms up to 50 ms, 10 ms up to 250 ms).
 */
class PlaySessionStats {
    private val workHistogram = IntArray(BUCKETS)
    private var workSamples = 0L
    private var totalFrames = 0L
    private var totalElapsedNanos = 0L
    private var lastRefreshHz = 0.0
    private var totalMissed = 0L
    private var totalLate = 0L
    private var totalOverran = 0L
    private var totalDrained = 0L
    private var totalAllocatedBytes = 0L

    /** Records one frame's total CPU work (runtime + draw) in nanoseconds; called on the render thread. */
    fun recordWork(workNanos: Long) {
        workHistogram[bucketOf(workNanos)]++
        workSamples++
    }

    /** Merges one completed or trailing stats window into the session totals. */
    @Synchronized
    fun recordWindow(
        frames: Int,
        elapsedNanos: Long,
        refreshHz: Float,
        missed: Int,
        late: Int,
        overran: Int,
        drained: Int,
        allocatedBytes: Long,
    ) {
        if (frames <= 0 && drained <= 0) return
        totalFrames += frames
        if (elapsedNanos > 0L) totalElapsedNanos += elapsedNanos
        if (refreshHz.isFinite() && refreshHz > 0f) lastRefreshHz = refreshHz.toDouble()
        totalMissed += missed
        totalLate += late
        totalOverran += overran
        totalDrained += drained
        if (allocatedBytes > 0L) totalAllocatedBytes += allocatedBytes
    }

    /** Produces the final session summary after the render thread has stopped; null if no frames were drawn. */
    @Synchronized
    fun snapshot(): PlayFrameSummary? {
        if (totalFrames <= 0L) return null
        val fps = if (totalElapsedNanos > 0L) totalFrames * 1e9 / totalElapsedNanos else 0.0
        val allocKb = totalAllocatedBytes / 1024.0 / totalFrames
        return PlayFrameSummary(
            frames = totalFrames,
            fps = fps,
            refreshHz = lastRefreshHz,
            missed = totalMissed,
            late = totalLate,
            overran = totalOverran,
            drained = totalDrained,
            workP50Ms = percentileMs(50),
            workP99Ms = percentileMs(99),
            allocKbPerFrame = allocKb,
        )
    }

    private fun percentileMs(percent: Int): Double {
        if (workSamples <= 0L) return 0.0
        val targetRank = ((workSamples - 1L) * percent) / 100L + 1L
        var seen = 0L
        for (b in 0 until BUCKETS) {
            seen += workHistogram[b]
            if (seen >= targetRank) return bucketMidpointMs(b)
        }
        return bucketMidpointMs(BUCKETS - 1)
    }

    companion object {
        private const val FINE_BUCKETS = 100 // 0..10 ms in 0.1 ms steps
        private const val MID_BUCKETS = 80 // 10..50 ms in 0.5 ms steps
        private const val COARSE_BUCKETS = 20 // 50..250 ms in 10 ms steps
        private const val BUCKETS = FINE_BUCKETS + MID_BUCKETS + COARSE_BUCKETS

        private fun bucketOf(nanos: Long): Int {
            val clamped = maxOf(0L, nanos)
            return when {
                clamped < 10_000_000L -> (clamped / 100_000L).toInt()
                clamped < 50_000_000L -> FINE_BUCKETS + ((clamped - 10_000_000L) / 500_000L).toInt()
                else -> FINE_BUCKETS + MID_BUCKETS +
                    ((clamped - 50_000_000L) / 10_000_000L).toInt().coerceAtMost(COARSE_BUCKETS - 1)
            }
        }

        private fun bucketMidpointMs(bucket: Int): Double = when {
            bucket < FINE_BUCKETS -> (bucket + 0.5) * 0.1
            bucket < FINE_BUCKETS + MID_BUCKETS -> 10.0 + (bucket - FINE_BUCKETS + 0.5) * 0.5
            else -> 50.0 + (bucket - FINE_BUCKETS - MID_BUCKETS + 0.5) * 10.0
        }
    }
}
