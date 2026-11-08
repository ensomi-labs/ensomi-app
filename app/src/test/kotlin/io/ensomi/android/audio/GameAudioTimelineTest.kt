package io.ensomi.android.audio

import org.junit.Assert.assertEquals
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
}
