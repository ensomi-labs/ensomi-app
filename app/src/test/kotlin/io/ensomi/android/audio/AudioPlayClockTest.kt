package io.ensomi.android.audio

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayClockTest {
    private val ms = 1_000_000L
    private val rate = 48_000

    /** An output whose timestamps the test sets one by one. */
    private class ScriptedOutput : PlayOutput {
        var next: OutputTimestamp? = null
        var reads = 0
        var plays = 0
        var pauses = 0
        val playedFrom = ArrayList<Long>()

        override fun timestamp(): OutputTimestamp? {
            reads++
            return next
        }

        override fun play() {
            plays++
        }

        override fun pause() {
            pauses++
        }

        override fun playFrom(frame: Long) {
            playedFrom += frame
        }
    }

    private fun frames(songMs: Double, leadIn: Long = 0) = (songMs * rate / 1000).toLong() + leadIn

    @Test
    fun holdsNothingUntilAFramePlaysAfterTheStart() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 96_000)
        assertNull(clock.songTimeMs(0))
        clock.start(nanoTime = 1_000 * ms, fromMs = -2_000.0)
        assertEquals(1, output.plays)
        clock.update(1_000 * ms)
        assertNull("No timestamp yet", clock.songTimeMs(1_000 * ms))
        // A position of 0, or one stamped before the start, is not a frame heard since it.
        output.next = OutputTimestamp(0, 1_005 * ms)
        clock.update(1_010 * ms)
        assertNull(clock.songTimeMs(1_010 * ms))
        output.next = OutputTimestamp(480, 990 * ms)
        clock.update(1_020 * ms)
        assertNull(clock.songTimeMs(1_020 * ms))
        output.next = OutputTimestamp(480, 1_025 * ms)
        clock.update(1_030 * ms)
        // 480 frames are 10 ms into the two-second lead-in.
        assertEquals(-1_990.0, clock.songTimeMs(1_025 * ms)!!, 1e-9)
        assertEquals(10.0, clock.songTimeMs(3_025 * ms)!!, 1e-9)
    }

    @Test
    fun runsAtRateOneFromTheNewestPair() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(100.0), 100 * ms)
        clock.update(110 * ms)
        assertEquals(150.0, clock.songTimeMs(150 * ms)!!, 1e-9)
        assertEquals(150 * ms, clock.nanoTimeAt(150.0))
    }

    @Test
    fun extendsTheLow32BitsAcrossAWrap() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        val nearTheTop = (1L shl 32) - 4_800 // 100 ms before the position wraps
        output.next = OutputTimestamp(nearTheTop, 10 * ms)
        clock.update(10 * ms)
        val before = clock.songTimeMs(10 * ms)!!
        // 200 ms later the low 32 bits have wrapped; the platform may report them as a negative or small value.
        output.next = OutputTimestamp((nearTheTop + 9_600) and 0xFFFF_FFFFL, 210 * ms)
        clock.update(10 * ms + AudioPlayClock.WARM_NANOS + AudioPlayClock.STABLE_POLL_NANOS)
        assertEquals(before + 200.0, clock.songTimeMs(210 * ms)!!, 1e-6)
        output.next = OutputTimestamp((nearTheTop + 19_200) or (0xFFFF_FFFFL shl 32), 410 * ms)
        clock.update(10 * ms + AudioPlayClock.WARM_NANOS + 2 * AudioPlayClock.STABLE_POLL_NANOS)
        assertEquals(before + 400.0, clock.songTimeMs(410 * ms)!!, 1e-6)
    }

    /**
     * Runs frames every [frameNanos] from [fromNanos] to [untilNanos] as the render thread does: update, the time now, the
     * time at the presentation [aheadNanos] later. Neither ever goes back, and the time drawn moves on by a frame's time
     * at a rate within [AudioPlayClock.MAX_SLEW] of 1, so it never stalls and never jumps. [before] runs first in each
     * frame, to move the output on or pause; [check] sees each frame's time now.
     */
    private fun runFrames(
        clock: AudioPlayClock,
        fromNanos: Long,
        untilNanos: Long,
        frameNanos: Long = 8_333_333L,
        aheadNanos: Long = 3 * frameNanos,
        before: (now: Long) -> Unit = {},
        check: (now: Long, songMs: Double) -> Unit = { _, _ -> },
    ): Long {
        var now = fromNanos
        var lastNow = Double.NEGATIVE_INFINITY
        var lastPresent = Double.NEGATIVE_INFINITY
        var lastPresentNanos = 0L
        while (now < untilNanos) {
            before(now)
            clock.update(now)
            val atNow = clock.songTimeMs(now)
            val atPresent = clock.songTimeMs(now + aheadNanos)
            if (atNow != null && atPresent != null) {
                assertTrue("now went back at $now: $atNow < $lastNow", atNow >= lastNow)
                assertTrue("present went back at $now: $atPresent < $lastPresent", atPresent >= lastPresent)
                if (lastPresent > Double.NEGATIVE_INFINITY && !clock.isPaused) {
                    val stepMs = atPresent - lastPresent
                    val frameMs = (now + aheadNanos - lastPresentNanos) / 1e6
                    assertTrue(
                        "drawn time stepped $stepMs ms in a $frameMs ms frame at $now",
                        stepMs >= frameMs * (1 - AudioPlayClock.MAX_SLEW) - 1e-6 &&
                            stepMs <= frameMs * (1 + AudioPlayClock.MAX_SLEW) + 1e-6,
                    )
                }
                lastNow = atNow
                lastPresent = atPresent
                lastPresentNanos = now + aheadNanos
                check(now, atNow)
            }
            now += frameNanos
        }
        return now
    }

    /**
     * A stepped time heard, as the K90's deep-buffer output gave after a restart (pairs a 40 ms period behind) and the
     * emulator after one (pairs 170 ms ahead): the game's time turns toward it and back at a bounded rate, never jumping
     * to it nor holding, and is on the time heard again within a second.
     */
    @Test
    fun aSteppedTimeHeardIsSteeredOutWithoutAJump() {
        var offsetMs = 0.0
        val output = object : PlayOutput {
            var nowNanos = 0L

            override fun timestamp() = OutputTimestamp(frames(nowNanos / 1e6 + offsetMs), nowNanos)

            override fun play() = Unit

            override fun pause() = Unit

            override fun playFrom(frame: Long) = Unit
        }
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        runFrames(clock, 10 * ms, 6_000 * ms, before = { now ->
            output.nowNanos = now
            offsetMs = when {
                now in 2_000 * ms until 2_100 * ms -> -40.0
                now in 4_000 * ms until 4_200 * ms -> 170.0
                else -> 0.0
            }
            // Read often throughout, as after a resume.
            clock.outputChanged(now)
        }) { now, songMs ->
            if (now in 1_000 * ms until 2_000 * ms || now in 3_000 * ms until 4_000 * ms || now >= 5_000 * ms) {
                assertEquals("on the time heard at $now", now / 1e6, songMs, 0.5)
            }
        }
        assertTrue("The steps back are counted", clock.stepsBack >= 1)
        assertTrue("and how far the game's time was from the time heard", clock.furthestMs in 100.0..171.0)
    }

    @Test
    fun pauseHoldsAndResumeGoesOnAtOnceWithTheMusicInStep() {
        val logs = ArrayList<String>()
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0) { logs += it }
        clock.start(0, 0.0)
        // Frame 0 was heard 50 ms after the start.
        output.next = OutputTimestamp(frames(4_950.0), 5_000 * ms)
        clock.update(5_000 * ms)
        assertEquals(4_966.0, clock.songTimeMs(5_016 * ms)!!, 1e-9)
        clock.pause(5_010 * ms)
        assertTrue(clock.isPaused)
        assertEquals(1, output.pauses)
        // Held at the furthest time shown, whatever is asked; the last paused frame is presented at 20 008 ms.
        assertEquals(4_966.0, clock.songTimeMs(9_000 * ms)!!, 1e-9)
        assertEquals(4_966.0, clock.songTimeMs(20_008 * ms)!!, 1e-9)
        clock.update(9_000 * ms)
        // Resumed at 20 s: the game's time goes on from 4 966 ms where that frame showed it, and the music is asked from
        // where the game's time will be when it is heard, 50 ms on, as after the start: 4 966 + 50 − 8.
        clock.resume(20_000 * ms)
        assertFalse(clock.isPaused)
        assertEquals(listOf(frames(5_008.0)), output.playedFrom)
        assertEquals("Not a plain play", 1, output.plays)
        assertEquals("Up to that frame it holds", 4_966.0, clock.songTimeMs(20_004 * ms)!!, 1e-9)
        clock.update(20_008 * ms)
        assertEquals("then runs on at once", 4_974.0, clock.songTimeMs(20_016 * ms)!!, 1e-9)
        // A pair from before the resume, or one with no frame past where the music starts, changes nothing.
        output.next = OutputTimestamp(frames(5_030.0), 19_990 * ms)
        clock.update(20_020 * ms)
        output.next = OutputTimestamp(frames(5_008.0), 20_030 * ms)
        clock.update(20_030 * ms)
        assertEquals(4_998.0, clock.songTimeMs(20_040 * ms)!!, 1e-9)
        assertTrue(logs.isEmpty())
        // The music was heard 60 ms after the resume, 10 ms later than the start's: 10 ms behind the game's time, which
        // slows until it is on the time heard.
        val heardNanos = 20_060 * ms
        fun heardMs(t: Long) = 5_008.0 + (t - heardNanos) / 1e6
        runFrames(clock, 20_048 * ms, 21_500 * ms, before = { now ->
            if (now >= heardNanos + 20 * ms) output.next = OutputTimestamp(frames(heardMs(now)), now)
        }) { now, songMs ->
            assertTrue("never behind the time heard while it catches up: $songMs at $now", songMs >= heardMs(now) - 0.05)
            if (now >= 21_000 * ms) assertEquals(heardMs(now), songMs, 0.1)
        }
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().startsWith("Clock: heard again 60.0 ms after the resume; the music -10."))
        // The next resume plays the music that much further ahead.
        clock.pause(21_500 * ms)
        val held = clock.songTimeMs(21_500 * ms)!!
        clock.resume(30_000 * ms)
        assertEquals(frames(held + 60.0).toDouble(), output.playedFrom.last().toDouble(), 2.0)
    }

    /**
     * The K90's speaker as its log shows it (a deep-buffer output with 40 ms periods): the first frame of a start or of a
     * restart is heard [restartsMs] later, one value for each in turn, and the output reports the frame heard once a
     * period, each report 15 ms after it, so the first after a restart comes about 55 ms after its frame was heard.
     */
    private class PhoneOutput(private val rate: Int, private val restartsMs: List<Double>) : PlayOutput {
        private class Stretch(val fromFrame: Long, val heardFromNanos: Long, var untilNanos: Long = Long.MAX_VALUE)

        private val stretches = ArrayList<Stretch>()
        var nowNanos = 0L
        var plays = 0
        val playedFrom = ArrayList<Long>()

        private fun frames(nanos: Long) = nanos * rate / 1_000_000_000L

        /** The frame heard at [t]; null before the first frame of the latest start or restart is heard. */
        fun heardAt(t: Long): Long? {
            val stretch = stretches.lastOrNull() ?: return null
            if (t < stretch.heardFromNanos) return null
            return stretch.fromFrame + frames(minOf(t, stretch.untilNanos) - stretch.heardFromNanos)
        }

        override fun timestamp(): OutputTimestamp? {
            val stretch = stretches.lastOrNull() ?: return null
            val since = minOf(nowNanos, stretch.untilNanos) - REPORT_LAG_NANOS - stretch.heardFromNanos
            if (since < PERIOD_NANOS) return null
            val tick = stretch.heardFromNanos + since / PERIOD_NANOS * PERIOD_NANOS
            return OutputTimestamp(heardAt(tick)!!, tick)
        }

        private fun begin(frame: Long) {
            stretches += Stretch(frame, nowNanos + (restartsMs[stretches.size] * 1e6).toLong())
        }

        override fun play() {
            plays++
            begin(0)
        }

        override fun pause() {
            stretches.last().untilNanos = nowNanos
        }

        override fun playFrom(frame: Long) {
            playedFrom += frame
            begin(frame)
        }

        private companion object {
            const val PERIOD_NANOS = 40_000_000L
            const val REPORT_LAG_NANOS = 15_000_000L
        }
    }

    /**
     * The jump the owner saw after Resume on the K90 (2026-11-08): the clock held the paused frame until the output's first
     * timestamp after the restart, 77 to 88 ms for the sound and about 55 ms more for the report, then took its line at
     * once, a step of 50 to 60 ms. Now the game's time goes on from the paused frame as soon as the count-in ends, frame by
     * frame, and the music is played from where that time will be when it is heard, by how long the start took. The
     * first pair after the resume finds the music 6 ms behind the game's time (heard after 86 ms, not 80), and the next
     * resume, whose flush took 80 ms longer, 76 ms behind: the game's time slows to it rather than stepping, and is on the
     * time heard within a second. The 120 Hz frames are presented three vsyncs after their work starts.
     */
    @Test
    fun afterAResumeTheChartGoesOnFromThePausedFrameAtOnceWithoutAJump() {
        val lead = 96_000L
        val logs = ArrayList<String>()
        val output = PhoneOutput(rate, restartsMs = listOf(80.0, 86.0, 162.0))
        val clock = AudioPlayClock(output, rate, leadInFrames = lead) { logs += it }
        fun heardMs(t: Long) = output.heardAt(t)?.let { (it - lead) * 1000.0 / rate }
        output.nowNanos = 1_000 * ms
        clock.start(1_000 * ms, -2_000.0)
        val pauses = listOf(6_000 * ms, 9_000 * ms)
        val countIn = 1_500 * ms
        val held = ArrayList<Double>()
        var resumedAt = Long.MIN_VALUE
        runFrames(clock, 1_000 * ms, 13_000 * ms, before = { now ->
            output.nowNanos = now
            if (!clock.isPaused && pauses.any { now >= it && now - it < 8_333_333L }) {
                clock.pause(now)
                held += clock.songTimeMs(now)!!
            }
            if (clock.isPaused && now >= pauses[held.size - 1] + countIn) {
                clock.resume(now)
                resumedAt = now
            }
        }) { now, songMs ->
            if (clock.isPaused) assertEquals("Held through the pause and the count-in", held.last(), songMs, 0.0)
            // On the time heard from the start, and from 1.2 s after each resume.
            val settled = if (resumedAt == Long.MIN_VALUE) now >= 2_000 * ms else now - resumedAt >= 1_200 * ms
            if (!clock.isPaused && settled) assertEquals("on the time heard at $now", heardMs(now)!!, songMs, 1.0)
        }
        assertEquals(2, held.size)
        assertEquals("Not a plain play", 1, output.plays)
        assertEquals(2, output.playedFrom.size)
        val moves = logs.filter { it.startsWith("Clock: heard again") }
        assertEquals(moves.toString(), 2, moves.size)
        fun number(line: String, after: String) =
            Regex(Regex.escape(after) + " ([-+]?[0-9.]+)").find(line)!!.groupValues[1].toDouble()
        assertEquals(86.0, number(moves[0], "heard again"), 0.1)
        assertEquals(-6.0, number(moves[0], "the music"), 0.2)
        assertEquals(162.0, number(moves[1], "heard again"), 0.1)
        assertEquals(-76.0, number(moves[1], "the music"), 0.2)
    }

    /**
     * An input made before a pause and taken by a paused frame keeps its own song time: while paused, a time before the
     * pause maps on the line the clock kept to, never past the time held; from the pause on it is the time held.
     */
    @Test
    fun whilePausedAnEventBeforeThePauseMapsAsItDidThen() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        assertNull(clock.eventSongTimeMs(0))
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(5_000.0), 5_000 * ms)
        clock.update(5_000 * ms)
        assertEquals("While it runs it is the song time", 5_004.0, clock.eventSongTimeMs(5_004 * ms)!!, 1e-9)
        assertEquals(5_016.0, clock.songTimeMs(5_016 * ms)!!, 1e-9)
        clock.pause(5_010 * ms)
        assertEquals(5_007.0, clock.eventSongTimeMs(5_007 * ms)!!, 1e-9)
        assertEquals("Held from the pause on", 5_016.0, clock.eventSongTimeMs(5_010 * ms)!!, 1e-9)
        assertEquals(5_016.0, clock.eventSongTimeMs(9_000 * ms)!!, 1e-9)
        assertEquals("The time asked for is not moved", 5_016.0, clock.songTimeMs(9_000 * ms)!!, 1e-9)
        // Resumed, and paused again 5 ms later, before a timestamp came: the game's time ran on from 5 016 ms meanwhile,
        // and nothing from before the resume maps below it.
        output.next = OutputTimestamp(frames(5_012.0), 5_013 * ms)
        clock.resume(20_000 * ms)
        clock.pause(20_005 * ms)
        assertEquals(5_017.0, clock.eventSongTimeMs(20_001 * ms)!!, 1e-9)
        assertEquals(5_016.0, clock.eventSongTimeMs(5_007 * ms)!!, 1e-9)
        assertEquals(5_021.0, clock.eventSongTimeMs(20_005 * ms)!!, 1e-9)
    }

    @Test
    fun readsEvery10MsWhileItWarmsUpThenOnceASecond() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(5.0), 5 * ms)
        var t = 0L
        while (t < 3_000 * ms) {
            output.next = OutputTimestamp(frames(t / 1e6), t)
            clock.update(t)
            t += 4 * ms
        }
        // The first second: a read every 10 ms (4 ms frames, so every 12 ms); then two more, at 1 s intervals.
        val warm = output.reads
        assertTrue("reads $warm", warm in 80..90)
        clock.outputChanged(t)
        val before = output.reads
        repeat(25) {
            clock.update(t)
            t += 4 * ms
        }
        assertTrue("A route change reads often again", output.reads - before >= 8)
    }

    /**
     * A whole song, scripted: frames at 60 Hz, each asking for its time now and at its presentation two frames later,
     * timestamps with a millisecond of jitter, a 40 ms underrun in the middle and a pause. Neither series ever goes
     * back, and the clock ends on the output's own time.
     */
    @Test
    fun neverRunsBackwardsOverAWholeSong() {
        val random = Random(7)
        var stalledMs = 0.0
        var heardAt = 0.0
        var lastNanos = 0L
        var playing = false
        val output = object : PlayOutput {
            var nowNanos = 0L

            override fun timestamp(): OutputTimestamp {
                val stampNanos = nowNanos - 3 * ms + (random.nextDouble(-1.0, 1.0) * ms).toLong()
                return OutputTimestamp(frames(heardAt - (nowNanos - stampNanos) / 1e6, leadIn = 96_000), stampNanos)
            }

            override fun play() {
                playing = true
            }

            override fun pause() {
                playing = false
            }

            override fun playFrom(frame: Long) {
                heardAt = (frame - 96_000) * 1000.0 / rate
                playing = true
            }
        }
        val clock = AudioPlayClock(output, rate, leadInFrames = 96_000)
        val frameNanos = 16_666_667L
        var now = 1_000 * ms
        clock.start(now, -2_000.0)
        heardAt = -2_000.0
        lastNanos = now
        var lastNow = Double.NEGATIVE_INFINITY
        var lastPresent = Double.NEGATIVE_INFINITY
        var frames = 0
        while (heardAt < 180_000) {
            now += frameNanos
            output.nowNanos = now
            val step = (now - lastNanos) / 1e6
            lastNanos = now
            if (playing) {
                if (heardAt in 60_000.0..60_040.0 && stalledMs < 40) stalledMs += step else heardAt += step
            }
            if (frames == 5_000) clock.pause(now)
            if (frames == 5_100) clock.resume(now)
            clock.update(now)
            val atNow = clock.songTimeMs(now) ?: continue
            val atPresent = clock.songTimeMs(now + 2 * frameNanos)!!
            assertTrue("now went back at frame $frames: $atNow < $lastNow", atNow >= lastNow)
            assertTrue("present went back at frame $frames: $atPresent < $lastPresent", atPresent >= lastPresent)
            lastNow = atNow
            lastPresent = atPresent
            frames++
        }
        assertEquals(heardAt, clock.songTimeMs(now)!!, 2.0)
        assertTrue("The underrun put the time heard back, and the game's time did not follow it back", clock.stepsBack >= 1)
    }
}
