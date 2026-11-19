package io.ensomi.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamingPcmSourceTest {
    private class FakeNativeStream(
        private val samples: FloatArray,
        private val channels: Int,
    ) : NativeAudioStream {
        var framePos = 0
        var closeCount = 0
        val seeks = ArrayList<Long>()

        override fun readF32le(maxFrames: UInt): ByteArray {
            val totalFrames = samples.size / channels
            val toRead = minOf(maxFrames.toInt(), totalFrames - framePos)
            if (toRead <= 0) return ByteArray(0)
            val count = toRead * channels
            val bytes = ByteArray(count * Float.SIZE_BYTES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                .put(samples, framePos * channels, count)
            framePos += toRead
            return bytes
        }

        override fun seekFrame(frame: ULong) {
            seeks += frame.toLong()
            framePos = frame.toInt().coerceIn(0, samples.size / channels)
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun readsLittleEndianFloatsSeeksAndClosesIdempotently() {
        val pcm = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f, 1.0f, -1.0f)
        val fake = FakeNativeStream(pcm, channels = 2)
        val source = StreamingPcmSource(
            stream = fake,
            sampleRate = 48_000,
            channels = 2,
            frames = 3,
            durationMs = 0.0625,
            codec = "flac",
            leadFrames = 0,
        )
        val dest = FloatArray(8)
        assertEquals(2, source.read(dest, offset = 2, frameCount = 2))
        assertArrayEquals(floatArrayOf(0f, 0f, 0.25f, -0.25f, 0.5f, -0.5f, 0f, 0f), dest, 0f)
        assertEquals(1, source.read(dest, offset = 0, frameCount = 4))
        assertArrayEquals(floatArrayOf(1.0f, -1.0f), dest.copyOf(2), 0f)
        assertEquals(0, source.read(dest, offset = 0, frameCount = 4))

        source.seek(1)
        assertEquals(listOf(1L), fake.seeks)
        assertEquals(2, source.read(dest, offset = 0, frameCount = 4))
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 1.0f, -1.0f), dest.copyOf(4), 0f)

        source.close()
        source.close()
        assertEquals(1, fake.closeCount)
        assertEquals(0, source.read(dest, offset = 0, frameCount = 2))
    }

    @Test
    fun playAudioHandsOffPreopenedOnceReopensOnRestartAndClosesUnclaimed() {
        val fake1 = FakeNativeStream(floatArrayOf(1f, 2f), channels = 1)
        val fake2 = FakeNativeStream(floatArrayOf(3f, 4f), channels = 1)
        val first = StreamingPcmSource(fake1, 48_000, 1, 2, 0.04, "mp3", 0)
        val second = StreamingPcmSource(fake2, 48_000, 1, 2, 0.04, "mp3", 0)
        var opens = 0
        val playAudio = PlayAudio(first) {
            opens++
            second
        }
        assertSame(first, playAudio.open())
        assertEquals(0, opens)
        assertSame(second, playAudio.open())
        assertEquals(1, opens)
        // Already claimed: close() must not close either live source.
        playAudio.close()
        assertEquals(0, fake1.closeCount)
        assertEquals(0, fake2.closeCount)

        // Unclaimed preopened source IS closed when PlayAudio closes (e.g. Back during load).
        val fakeUnclaimed = FakeNativeStream(floatArrayOf(1f), channels = 1)
        val unclaimed = StreamingPcmSource(fakeUnclaimed, 48_000, 1, 1, 0.02, "flac", 0)
        val abandoned = PlayAudio(unclaimed) { error("should not reopen") }
        abandoned.close()
        assertEquals(1, fakeUnclaimed.closeCount)
    }

    @Test
    fun fileExtensionHintExtractsLowercaseExtension() {
        assertEquals("flac", fileExtensionHint("Dark Side.FLAC"))
        assertEquals("mp3", fileExtensionHint("Artist - Title.mp3"))
        assertEquals("m4a", fileExtensionHint("a.b.c.M4A"))
        assertNull(fileExtensionHint("noextension"))
        assertNull(fileExtensionHint(".hidden"))
        assertNull(fileExtensionHint("trailing."))
        assertNull(fileExtensionHint(null))
    }
}
