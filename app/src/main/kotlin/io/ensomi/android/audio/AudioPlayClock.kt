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
 *   [STABLE_POLL_NANOS]. A pair counts after a start or a resume only once presented after it, with a frame past the
 *   one play starts from.
 * - After a start, the game has no time until the first pair; its time starts there, on the time heard.
 * - Each frame its rate becomes 1 plus the difference from the time heard × ln 2 / [HALF_LIFE_MS], so the difference
 *   halves every [HALF_LIFE_MS], as osu!'s does, but never more than [MAX_SLEW] from 1: where osu! jumps to its track,
 *   this goes on at that rate until it is back in. The rate changes by at most [RATE_CHANGE_PER_MS] a millisecond, and
 *   the line turns at the latest time asked for, so nothing shown changes or runs back.
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

    /** The time heard, from the newest pair: null after a start or a resume until the first that counts. */
    private var heard: Line? = null

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
        val interval = if (waiting || nanoTime < warmUntilNanos) WARM_POLL_NANOS else STABLE_POLL_NANOS
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
            heard = next
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
        val before = heard
        if (before != null && before.at(nanoTime) - next.at(nanoTime) > STEP_EPSILON_MS) stepsBack++
        heard = next
        game?.let { furthestMs = max(furthestMs, abs(next.at(nanoTime) - it.at(nanoTime))) }
    }

    /** The game's line turns toward the time heard, from the latest time asked for. */
    private fun steer(nanoTime: Long) {
        val current = game ?: return
        val at = maxOf(nanoTime, latestQueryNanos, current.nanos)
        val shown = current.at(at)
        val target = heard?.let { 1.0 + ((it.at(at) - shown) * LN2 / HALF_LIFE_MS).coerceIn(-MAX_SLEW, MAX_SLEW) }
            ?: 1.0
        val turn = (at - current.nanos) / 1e6 * RATE_CHANGE_PER_MS
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

        /** The difference from the time heard halves in this: osu!'s gameplay `DriftRecoveryHalfLife`. */
        const val HALF_LIFE_MS = 80.0

        /**
         * The most the rate leaves 1: what the half-life gives at osu!'s `AllowableErrorMilliseconds` (two 60 Hz
         * frames, 33.3 ms), past which osu! jumps to its track.
         */
        val MAX_SLEW = 1000.0 / 60 * 2 * ln(2.0) / HALF_LIFE_MS

        /** How fast the rate may change, a millisecond: to [MAX_SLEW] in about 70 ms. */
        const val RATE_CHANGE_PER_MS = 0.004

        /** A restart heard later than this is not the output's latency. */
        private const val MAX_RESTART_NANOS = 500_000_000L

        private val LN2 = ln(2.0)

        /** Differences smaller than this are rounding, not steps. */
        private const val STEP_EPSILON_MS = 0.01
        private const val HALF_RANGE = 0x8000_0000L
    }
}
