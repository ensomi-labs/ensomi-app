package io.ensomi.android.audio

import io.ensomi.android.play.PlayClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A reading of the output: a frame of its timeline (the lead-in's first frame is 0), of which only the low 32 bits are
 * valid, and when it was presented.
 */
data class OutputTimestamp(val framePosition: Long, val nanoTime: Long)

/** What the clock reads and drives: `AudioTrack.getTimestamp`, `play`, `pause`, and play again from a frame. */
interface PlayOutput {
    /** The newest timestamp, or null while the output has none. */
    fun timestamp(): OutputTimestamp?

    fun play()

    fun pause()

    /**
     * Plays again after a pause from [frame] of the timeline, dropping what the output holds: the frames still queued,
     * and those it had already taken, which play out after the pause. An output that gets to play only later starts as
     * much further on, so that the music keeps step with a clock that ran on from the call. Its timestamps go on from
     * where it starts.
     */
    fun playFrom(frame: Long)
}

/**
 * The game's song time: a line of its own that runs on from frame to frame and is steered toward the song time heard,
 * so that it never steps. osu!'s gameplay clock follows its track the same way (gameplay.md, The clock).
 *
 * The output's newest timestamp pair gives the song time heard, carried to any time at rate 1:
 *
 * ```
 * heardMs(t) = (framePosition − leadInFrames) / sampleRate × 1000 + (t − nanoTime) / 1e6
 * ```
 *
 * - Only the low 32 bits of the position are valid; the clock extends them across wraps.
 * - It reads the output every 10 ms after a start, a resume or [outputChanged] for [WARM_NANOS], then every
 *   [STABLE_POLL_NANOS] (with a quick [CONFIRM_POLL_NANOS] re-read if a steady-state sample jumps by
 *   [OUTLIER_CONFIRM_MS] or more). A pair counts after a start or a resume only once presented after it, with a frame
 *   past the one play starts from.
 * - After a start, the game has no time until the first pair; its time starts there, on the time heard.
 * - Raw timestamp offsets are passed through a small sliding median filter ([FILTER_WINDOW]) and, in steady state,
 *   exponentially smoothed with bounded per-read step so single-sample outliers and backward steps on deep-buffer or
 *   Bluetooth outputs do not yank the reference line.
 * - Each frame its rate becomes 1 plus the difference from the time heard × ln 2 / [HALF_LIFE_MS], clamped to
 *   [STEADY_MAX_SLEW] (±3 %) in steady state and [RECOVERY_MAX_SLEW] (±12 %) during warm-up or after a confirmed
 *   sustained offset of [RECOVERY_OFFSET_MS] or more. The rate changes smoothly ([STEADY_RATE_CHANGE_PER_MS] /
 *   [RECOVERY_RATE_CHANGE_PER_MS]), and the line turns at the latest time asked for, so nothing shown changes or runs
 *   back.
 * - Pause holds the furthest time shown. Resume runs the game's time on from it at once, from the latest time asked for
 *   (the last paused frame's presentation), and plays the music from where that time will be when it is heard: by how
 *   long the last start or resume took to be heard. Until the first pair after it the game's time runs at rate 1; then
 *   it is steered as ever.
 *
 * Thread-safe: the render thread calls [update] and [songTimeMs], the main thread [pause] and [resume].
 */
class AudioPlayClock(
    private val output: PlayOutput,
    private val sampleRate: Int,
    private val leadInFrames: Long,
    private val log: (String) -> Unit = {},
) : PlayClock {
    /** Song time [ms] at [nanos], running on at [rate]. */
    private class Line(val ms: Double, val nanos: Long, val rate: Double = 1.0) {
        fun at(t: Long): Double = ms + (t - nanos) / 1e6 * rate
    }

    private var started = false

    /** Read without the lock by [isPaused]: a touch asks on the main thread, and must not contend with a frame. */
    @Volatile
    private var paused = false

    /** Waiting for the first timestamp presented after [waitFromNanos], with a frame past [waitFrame]. */
    private var waiting = false
    private var waitFromNanos = 0L
    private var waitFrame = 0L

    /** The filtered time heard: null after a start or a resume until the first pair that counts. */
    private var heard: Line? = null

    /** The last raw timestamp line seen since the last start/resume, for counting raw [stepsBack]. */
    private var lastRawHeard: Line? = null

    /** Fixed monotonic origin for offset arithmetic (`offsetMs = ms - (nanos - originNanos) / 1e6`). */
    private var originNanos = 0L
    private var hasOrigin = false

    /** Filtered offset relative to [originNanos], and sliding window of recent raw offsets (zero per-read alloc). */
    private var heardOffsetMs = 0.0
    private val offsetWindow = DoubleArray(FILTER_WINDOW)
    private val sortScratch = DoubleArray(FILTER_WINDOW)
    private var offsetCount = 0
    private var offsetIndex = 0

    /** Consecutive steady-state reads outside [RECOVERY_OFFSET_MS] with the same sign, to confirm sustained shifts. */
    private var sustainedOffsetCount = 0
    private var sustainedSign = 0
    private var confirmPending = false
    private var pendingOutlierOffsetMs = Double.NaN
    private var pendingOutlierSign = 0

    /** The game's time: null after a start until the first pair. */
    private var game: Line? = null

    /** Nothing is answered below the time held at the last resume, for a time asked for from before it. */
    private var floorMs = Double.NEGATIVE_INFINITY

    /** The time shown while paused: null if the game had no time yet. */
    private var heldMs: Double? = null

    /** When the clock paused with a time, for [eventSongTimeMs]; null while it runs, or after a pause before one. */
    private var pausedAtNanos: Long? = null

    /** The latest time asked for: the game's line turns there, so nothing shown up to it changes. */
    private var latestQueryNanos = Long.MIN_VALUE

    /** How long the last start or resume took to be heard: a resume's music starts that far past the time held. */
    private var restartNanos = 0L

    private var wraps = 0L
    private var lastLow = -1L

    private var lastPollNanos = Long.MIN_VALUE
    private var warmUntilNanos = Long.MIN_VALUE
    private var recoveryUntilNanos = Long.MIN_VALUE

    /** For logs and tests: pairs read; pairs that put the time heard back; the furthest the game's time was from it. */
    var reads = 0
        @Synchronized get
        private set
    var stepsBack = 0
        @Synchronized get
        private set
    var furthestMs = 0.0
        @Synchronized get
        private set

    @Synchronized
    override fun start(nanoTime: Long, fromMs: Double) {
        // The lead-in is in the audio: [leadInFrames] of silence before the song's first frame.
        if (started) return
        started = true
        paused = false
        if (!hasOrigin) {
            originNanos = nanoTime
            hasOrigin = true
        }
        waitFor(nanoTime, frame = 0L)
        output.play()
    }

    @Synchronized
    override fun update(nanoTime: Long) {
        if (!started || paused) return
        read(nanoTime)
        steer(nanoTime)
    }

    private fun read(nanoTime: Long) {
        val interval = when {
            waiting || nanoTime < warmUntilNanos -> WARM_POLL_NANOS
            confirmPending -> CONFIRM_POLL_NANOS
            else -> STABLE_POLL_NANOS
        }
        if (lastPollNanos != Long.MIN_VALUE && nanoTime - lastPollNanos < interval) return
        lastPollNanos = nanoTime
        val timestamp = output.timestamp() ?: return
        reads++
        val frame = extend(timestamp.framePosition)
        val next = Line((frame - leadInFrames) * 1000.0 / sampleRate, timestamp.nanoTime)
        if (waiting) {
            if (timestamp.nanoTime < waitFromNanos || frame <= waitFrame) return
            waiting = false
            warmUntilNanos = nanoTime + WARM_NANOS
            val rawOffset = offsetOf(next)
            resetFilter(rawOffset)
            heardOffsetMs = rawOffset
            heard = next
            lastRawHeard = next
            // When the frame play started from was heard, by this pair: how long the output took to play it.
            val heardFromNanos = next.nanos - ((frame - waitFrame) * 1e9 / sampleRate).toLong()
            restartNanos = (heardFromNanos - waitFromNanos).coerceIn(0L, MAX_RESTART_NANOS)
            val running = game
            if (running == null) {
                game = Line(next.at(nanoTime), nanoTime)
                return
            }
            val at = max(nanoTime, latestQueryNanos)
            log(
                String.format(
                    Locale.ROOT,
                    "Clock: heard again %.1f ms after the resume; the music %+.1f ms from the game's time",
                    restartNanos / 1e6,
                    next.at(at) - running.at(at),
                ),
            )
            return
        }
        val beforeRaw = lastRawHeard
        if (beforeRaw != null && beforeRaw.at(nanoTime) - next.at(nanoTime) > STEP_EPSILON_MS) stepsBack++
        lastRawHeard = next
        game?.let { furthestMs = max(furthestMs, abs(next.at(nanoTime) - it.at(nanoTime))) }

        val rawOffset = offsetOf(next)
        val rawJump = rawOffset - heardOffsetMs
        if (nanoTime < warmUntilNanos) {
            confirmPending = false
            pendingOutlierOffsetMs = Double.NaN
            pendingOutlierSign = 0
            sustainedOffsetCount = 0
            sustainedSign = 0
            heardOffsetMs = pushOffset(rawOffset)
        } else {
            val outlierSign = when {
                rawJump >= OUTLIER_CONFIRM_MS -> 1
                rawJump <= -OUTLIER_CONFIRM_MS -> -1
                else -> 0
            }
            // Hold an isolated steady-state outlier jump for one confirmation read so transient backward steps or
            // deep-buffer spikes neither yank `heard` nor bias the sliding median.
            if (outlierSign != 0 && pendingOutlierSign != outlierSign) {
                pendingOutlierOffsetMs = rawOffset
                pendingOutlierSign = outlierSign
                confirmPending = true
                sustainedSign = if (abs(rawJump) >= RECOVERY_OFFSET_MS) outlierSign else 0
                sustainedOffsetCount = if (sustainedSign != 0) 1 else 0
                return
            }
            if (outlierSign != 0 && pendingOutlierSign == outlierSign) {
                // Confirmed by a second consecutive jump in the same direction: admit the held sample too.
                if (!pendingOutlierOffsetMs.isNaN()) {
                    pushOffset(pendingOutlierOffsetMs)
                    pendingOutlierOffsetMs = Double.NaN
                }
            } else {
                pendingOutlierOffsetMs = Double.NaN
                pendingOutlierSign = 0
            }
            val medianOffset = pushOffset(rawOffset)
            val recoverySign = when {
                rawJump >= RECOVERY_OFFSET_MS -> 1
                rawJump <= -RECOVERY_OFFSET_MS -> -1
                else -> 0
            }
            if (recoverySign != 0 && (sustainedSign == 0 || sustainedSign == recoverySign)) {
                sustainedSign = recoverySign
                sustainedOffsetCount++
            } else {
                sustainedSign = recoverySign
                sustainedOffsetCount = if (recoverySign != 0) 1 else 0
            }
            if (sustainedOffsetCount >= SUSTAINED_CONFIRM_COUNT) {
                recoveryUntilNanos = nanoTime + WARM_NANOS
                heardOffsetMs = medianOffset
                sustainedOffsetCount = 0
                sustainedSign = 0
                pendingOutlierOffsetMs = Double.NaN
                pendingOutlierSign = 0
                confirmPending = false
            } else {
                val step = ((medianOffset - heardOffsetMs) * STEADY_ALPHA)
                    .coerceIn(-MAX_STEADY_STEP_MS, MAX_STEADY_STEP_MS)
                heardOffsetMs += step
                confirmPending = outlierSign != 0
            }
        }
        heard = Line(heardOffsetMs + (nanoTime - originNanos) / 1e6, nanoTime)
    }

    private fun offsetOf(line: Line): Double = line.ms - (line.nanos - originNanos) / 1e6

    private fun resetFilter(initialOffsetMs: Double) {
        offsetWindow[0] = initialOffsetMs
        offsetCount = 1
        offsetIndex = 1
        sustainedOffsetCount = 0
        sustainedSign = 0
        confirmPending = false
        pendingOutlierOffsetMs = Double.NaN
        pendingOutlierSign = 0
    }

    private fun pushOffset(offsetMs: Double): Double {
        offsetWindow[offsetIndex] = offsetMs
        offsetIndex = (offsetIndex + 1) % FILTER_WINDOW
        if (offsetCount < FILTER_WINDOW) offsetCount++
        for (i in 0 until offsetCount) sortScratch[i] = offsetWindow[i]
        // Insertion sort on at most FILTER_WINDOW (5) elements, zero allocations.
        for (i in 1 until offsetCount) {
            val v = sortScratch[i]
            var j = i - 1
            while (j >= 0 && sortScratch[j] > v) {
                sortScratch[j + 1] = sortScratch[j]
                j--
            }
            sortScratch[j + 1] = v
        }
        return sortScratch[offsetCount / 2]
    }

    /** The game's line turns toward the time heard, from the latest time asked for. */
    private fun steer(nanoTime: Long) {
        val current = game ?: return
        val at = maxOf(nanoTime, latestQueryNanos, current.nanos)
        val shown = current.at(at)
        val recovering = nanoTime < warmUntilNanos || nanoTime < recoveryUntilNanos
        val maxSlew = if (recovering) RECOVERY_MAX_SLEW else STEADY_MAX_SLEW
        val rateChangePerMs = if (recovering) RECOVERY_RATE_CHANGE_PER_MS else STEADY_RATE_CHANGE_PER_MS
        val target = heard?.let { 1.0 + ((it.at(at) - shown) * LN2 / HALF_LIFE_MS).coerceIn(-maxSlew, maxSlew) }
            ?: 1.0
        val turn = (at - current.nanos) / 1e6 * rateChangePerMs
        game = Line(shown, at, current.rate + (target - current.rate).coerceIn(-turn, turn))
    }

    @Synchronized
    override fun songTimeMs(nanoTime: Long): Double? {
        if (!started) return null
        if (nanoTime > latestQueryNanos) latestQueryNanos = nanoTime
        if (paused) return heldMs
        return gameAt(nanoTime)
    }

    private fun gameAt(nanoTime: Long): Double? = game?.let { max(it.at(nanoTime), floorMs) }

    /**
     * While paused, a time before the pause on the line the game kept to then, up to the time held; a pause before the
     * game had a time has none, and that is the answer.
     */
    @Synchronized
    override fun eventSongTimeMs(nanoTime: Long): Double? {
        if (!paused) return songTimeMs(nanoTime)
        val held = heldMs
        val at = pausedAtNanos ?: return held
        if (nanoTime >= at) return held
        val value = gameAt(nanoTime) ?: return held
        return if (held == null) value else min(value, held)
    }

    /** At rate 1 from the game's line: for scripted input, and for when a time long past was heard. */
    @Synchronized
    override fun nanoTimeAt(songMs: Double): Long {
        val current = game ?: return waitFromNanos
        return current.nanos + ((songMs - current.ms) * 1e6).toLong()
    }

    @Synchronized
    override fun pause(nanoTime: Long) {
        if (!started || paused) return
        // The furthest time shown, so that nothing drawn moves back.
        heldMs = gameAt(max(nanoTime, latestQueryNanos))
        pausedAtNanos = if (game != null) nanoTime else null
        paused = true
        waiting = false
        recoveryUntilNanos = Long.MIN_VALUE
        confirmPending = false
        output.pause()
    }

    @Synchronized
    override fun resume(nanoTime: Long) {
        if (!paused) return
        paused = false
        pausedAtNanos = null
        heard = null
        val held = heldMs
        heldMs = null
        // With no time yet, the lead-in plays again from its first frame. Otherwise the game goes on from the time
        // held, from the last paused frame's presentation, and the music from where the game will be when it is heard.
        var from = 0L
        if (held != null) {
            latestQueryNanos = max(latestQueryNanos, nanoTime)
            game = Line(held, latestQueryNanos)
            floorMs = held
            val heardMs = gameAt(nanoTime + restartNanos) ?: held
            from = max(0L, (heardMs * sampleRate / 1000).roundToLong() + leadInFrames)
        }
        waitFor(nanoTime, from)
        output.playFrom(from)
    }

    /** The output moved to another device: read it often again while the new route settles. */
    @Synchronized
    fun outputChanged(nanoTime: Long) {
        warmUntilNanos = nanoTime + WARM_NANOS
        lastPollNanos = Long.MIN_VALUE
        confirmPending = false
    }

    /** For the game's log when it closes. */
    @Synchronized
    fun summary(): String = String.format(
        Locale.ROOT,
        "%d timestamps read, %d steps back of the time heard; the game's time within %.1f ms of it",
        reads,
        stepsBack,
        furthestMs,
    )

    override val isPaused: Boolean
        get() = paused

    private fun waitFor(nanoTime: Long, frame: Long) {
        waiting = true
        waitFromNanos = nanoTime
        waitFrame = frame
        lastPollNanos = Long.MIN_VALUE
        lastRawHeard = null
        offsetCount = 0
        offsetIndex = 0
        sustainedOffsetCount = 0
        sustainedSign = 0
        confirmPending = false
        pendingOutlierOffsetMs = Double.NaN
        pendingOutlierSign = 0
        recoveryUntilNanos = Long.MIN_VALUE
    }

    /** The low 32 bits of [position], extended across wraps. */
    private fun extend(position: Long): Long {
        val low = position and 0xFFFF_FFFFL
        if (lastLow >= 0 && low < lastLow && lastLow - low > HALF_RANGE) wraps++
        lastLow = low
        return (wraps shl 32) or low
    }

    companion object {
        /** How often the output is read while it warms up and for a second after each start, resume or route change. */
        const val WARM_POLL_NANOS = 10_000_000L
        const val WARM_NANOS = 1_000_000_000L

        /** How often once it is stable; a phone measurement may change it (gameplay.md). */
        const val STABLE_POLL_NANOS = 1_000_000_000L

        /** Re-read interval after a steady-state outlier jump to confirm whether the shift is sustained. */
        const val CONFIRM_POLL_NANOS = 100_000_000L

        /** The difference from the time heard halves in this: osu!'s gameplay `DriftRecoveryHalfLife`. */
        const val HALF_LIFE_MS = 80.0

        /** Steady-state slew limit (±3 %): below perceptual scroll-speed threshold on jittery outputs. */
        const val STEADY_MAX_SLEW = 0.03

        /** Transient recovery slew limit (±12 %) during warm-up after start/resume/route change or confirmed shift. */
        const val RECOVERY_MAX_SLEW = 0.12

        /** Upper bound on rate departure from 1 across all clock phases. */
        const val MAX_SLEW = RECOVERY_MAX_SLEW

        /** How fast the rate may change in steady state (0 -> 3 % over 100 ms). */
        const val STEADY_RATE_CHANGE_PER_MS = 0.0003

        /** How fast the rate may change during warm-up/recovery (0 -> 12 % over 80 ms, zero overshoot). */
        const val RECOVERY_RATE_CHANGE_PER_MS = 0.0015

        /** Upper bound on rate change per millisecond. */
        const val RATE_CHANGE_PER_MS = RECOVERY_RATE_CHANGE_PER_MS

        /** Sliding median window size for raw timestamp offsets. */
        private const val FILTER_WINDOW = 5

        /** Steady-state EMA factor toward the sliding median, clamped to [MAX_STEADY_STEP_MS] per read. */
        private const val STEADY_ALPHA = 0.25
        private const val MAX_STEADY_STEP_MS = 8.0

        /** A steady-state jump at least this large is held for one confirmation read. */
        private const val OUTLIER_CONFIRM_MS = 18.0

        /** Consecutive same-sign reads at least this far from heard trigger transient recovery slew. */
        private const val RECOVERY_OFFSET_MS = 50.0
        private const val SUSTAINED_CONFIRM_COUNT = 3

        /** A restart heard later than this is not the output's latency. */
        private const val MAX_RESTART_NANOS = 500_000_000L

        private val LN2 = ln(2.0)

        /** Differences smaller than this are rounding, not steps. */
        private const val STEP_EPSILON_MS = 0.01
        private const val HALF_RANGE = 0x8000_0000L
    }
}
