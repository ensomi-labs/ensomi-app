package io.ensomi.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a game's output plays at each frame of its timeline, which is where it plays again from after a pause. */
class GameAudioTimelineTest {
    @Test
    fun theLeadInIsSilenceThenTheSongThenSilence() {
        assertEquals(-1L, songFrameAt(0, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
        assertEquals(-1L, songFrameAt(95_999, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
        assertEquals(0L, songFrameAt(96_000, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
        assertEquals(479_999L, songFrameAt(575_999, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
        assertEquals(-1L, songFrameAt(576_000, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
        assertEquals(-1L, songFrameAt(10_000_000, leadInFrames = 96_000, songFrames = 480_000, loopFrom = null))
    }

    /** The calibration's click track: a second of silence, then one 500 ms period with its click, repeated. */
    @Test
    fun aLoopRepeatsFromItsFrameSoEveryClickStaysOnItsTime() {
        val frames = ClickTrack.song().frames
        val loopFrom = ClickTrack.loopFromFrame()
        assertEquals(47_999L, songFrameAt(47_999, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
        assertEquals(48_000L, songFrameAt(48_000, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
        assertEquals(71_999L, songFrameAt(71_999, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
        assertEquals(48_000L, songFrameAt(72_000, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
        // Click k is heard at 1 000 + 500 k ms: its frame is the click's, whichever frame play goes on from.
        for (k in listOf(1, 7, 1_000)) {
            val frame = (ClickTrack.FIRST_CLICK_MS + ClickTrack.PERIOD_MS * k).toLong() * ClickTrack.SAMPLE_RATE / 1000
            assertEquals(48_000L, songFrameAt(frame, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
            assertEquals(48_123L, songFrameAt(frame + 123, leadInFrames = 0, songFrames = frames, loopFrom = loopFrom))
        }
    }

    @Test
    fun arrayPcmSourceRespectsValidFrameCountAndSeeksCleanly() {
        // Backing array has trailing sentinel values beyond frameCount = 3 (6 stereo floats).
        val samples = floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f, 99f, 99f, 99f, 99f)
        val song = DecodedSong(samples, channels = 2, sampleRate = 48_000, leadFrames = 0, mime = "audio/raw", codec = "test", header = null, frameCount = 3)
        val source = ArrayPcmSource(song)
        assertEquals(3, source.frames)

        val out = FloatArray(10)
        assertEquals(2, source.read(out, 0, 2))
        assertArrayEquals(floatArrayOf(1f, -1f, 2f, -2f), out.copyOf(4), 0f)
        assertEquals(1, source.read(out, 4, 2))
        assertArrayEquals(floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f), out.copyOf(6), 0f)
        assertEquals(0, source.read(out, 0, 2))

        source.seek(1)
        assertEquals(2, source.read(out, 0, 4))
        assertArrayEquals(floatArrayOf(2f, -2f, 3f, -3f), out.copyOf(4), 0f)

        source.seek(100)
        assertEquals(0, source.read(out, 0, 2))
    }

    @Test
    fun timelineFeederPreservesSamplesAcrossPartialWritesAndSeeks() {
        // Stereo song with 5 frames, lead-in of 3 frames, chunk size 4 frames.
        val songSamples = floatArrayOf(
            1f, 10f,
            2f, 20f,
            3f, 30f,
            4f, 40f,
            5f, 50f,
            999f, 999f, // spare capacity, must never be read
        )
        val song = DecodedSong(songSamples, channels = 2, sampleRate = 48_000, leadFrames = 0, mime = "audio/raw", codec = "test", header = null, frameCount = 5)
        val feeder = TimelineFeeder(ArrayPcmSource(song), leadInFrames = 3L, loopFromFrame = null, chunkFrames = 4)

        val collected = ArrayList<Float>()
        // Simulate partial writes (at most 2 frames = 4 floats per call, plus a 0-sample full-buffer stall).
        var stalls = 0
        while (collected.size < 12 * 2) {
            feeder.writeNext { buf, offset, count ->
                if (collected.size == 10 && stalls++ == 0) return@writeNext 0
                val take = minOf(count, 4)
                for (i in 0 until take) collected += buf[offset + i]
                take
            }
        }
        val expected = floatArrayOf(
            // 3 frames lead-in silence
            0f, 0f, 0f, 0f, 0f, 0f,
            // 5 frames of song
            1f, 10f, 2f, 20f, 3f, 30f, 4f, 40f, 5f, 50f,
            // 4 frames trailing silence
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
        assertArrayEquals(expected, collected.toFloatArray(), 0f)

        // Seek back into song frame 2 (timeline frame 5) and verify exact resume.
        feeder.seekTimeline(5L)
        val resumed = ArrayList<Float>()
        repeat(2) {
            feeder.writeNext { buf, offset, count ->
                for (i in 0 until count) resumed += buf[offset + i]
                count
            }
        }
        assertArrayEquals(
            floatArrayOf(3f, 30f, 4f, 40f, 5f, 50f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            resumed.toFloatArray(),
            0f,
        )
    }

    @Test
    fun timelineFeederSupportsUnknownLengthStreamingSourceAndLooping() {
        // Streaming source with frames == null (e.g. native AudioDecoder) returning 3 mono frames then EOF.
        val data = floatArrayOf(10f, 20f, 30f)
        val streaming = object : AudioPcmSource {
            override val sampleRate = 48_000
            override val channels = 1
            override val frames: Int? = null
            var pos = 0

            override fun read(dest: FloatArray, offset: Int, frameCount: Int): Int {
                val n = minOf(frameCount, data.size - pos)
                for (i in 0 until n) dest[offset + i] = data[pos + i]
                pos += n
                return n
            }

            override fun seek(frame: Long) {
                pos = frame.coerceIn(0L, data.size.toLong()).toInt()
            }
        }
        val feeder = TimelineFeeder(streaming, leadInFrames = 1L, loopFromFrame = null, chunkFrames = 2)
        val out = ArrayList<Float>()
        repeat(4) {
            feeder.writeNext { buf, offset, count ->
                for (i in 0 until count) out += buf[offset + i]
                count
            }
        }
        assertArrayEquals(floatArrayOf(0f, 10f, 20f, 30f, 0f, 0f), out.toFloatArray(), 0f)
        assertFalse(feeder.lastWritePartial)

        // Looping feeder wraps seamlessly at loopFromFrame = 1.
        streaming.seek(0)
        val looping = TimelineFeeder(streaming, leadInFrames = 0L, loopFromFrame = 1, chunkFrames = 2)
        val looped = ArrayList<Float>()
        repeat(4) {
            looping.writeNext { buf, offset, count ->
                for (i in 0 until count) looped += buf[offset + i]
                count
            }
        }
        // Frames: [10, 20], [30], [20, 30], [20, 30]
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 20f, 30f, 20f, 30f), looped.toFloatArray(), 0f)
        assertTrue(looping.frame == 7L)
    }
}
