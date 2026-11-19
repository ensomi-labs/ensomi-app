package io.ensomi.android.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBuilderTest {
    private fun shorts(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
            flip()
        }

    private fun BuiltPcm.validSamples(): FloatArray = samples.copyOf(sampleCount)

    @Test
    fun monoIsTheMeanOfTheChannelsAndStereoKeepsThem() {
        val mono = PcmBuilder(channels = 1, estimatedFrames = 1)
        mono.add(shorts(16_384, 0, -32_768, -32_768), AudioFormat.ENCODING_PCM_16BIT, fileChannels = 2, skipFrames = 0)
        val monoBuilt = mono.build()
        assertEquals(2, monoBuilt.frames)
        assertArrayEquals(floatArrayOf(0.25f, -1f), monoBuilt.validSamples(), 1e-6f)

        val stereo = PcmBuilder(channels = 2, estimatedFrames = 8)
        stereo.add(floats(0.5f, -0.5f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 2, skipFrames = 0)
        stereo.add(floats(0.25f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 0)
        val stereoBuilt = stereo.build()
        assertEquals(2, stereoBuilt.frames)
        assertEquals(4, stereoBuilt.sampleCount)
        // Reuses backing buffer directly without copying to shrink.
        assertEquals(16, stereoBuilt.samples.size)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0.25f), stereoBuilt.validSamples(), 0f)
    }

    @Test
    fun aLeadIsSilenceFirstAndATrimSpansBuffers() {
        val lead = PcmBuilder(channels = 1, estimatedFrames = 2)
        lead.addSilence(3)
        lead.add(floats(1f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 0)
        val leadBuilt = lead.build()
        assertEquals(4, leadBuilt.frames)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), leadBuilt.validSamples(), 0f)

        val trim = PcmBuilder(channels = 1, estimatedFrames = 4)
        var left = trim.add(floats(1f, 2f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 3)
        assertEquals(1L, left)
        left = trim.add(floats(3f, 4f, 5f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = left)
        assertEquals(0L, left)
        val trimBuilt = trim.build()
        assertEquals(2, trimBuilt.frames)
        assertArrayEquals(floatArrayOf(4f, 5f), trimBuilt.validSamples(), 0f)
    }

    @Test
    fun directBuffersRespectOffsetsTrimmingAndChannelSumOrder() {
        val buffer = ByteBuffer.allocateDirect(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(99f)
            floatArrayOf(2f, 3f, 4f, 0.75f, 1e-8f, -0.75f, 0.5f, 0.25f, -0.125f).forEach { putFloat(it) }
            putFloat(77f)
            position(4)
            limit(40)
        }
        val builder = PcmBuilder(channels = 1, estimatedFrames = 2)
        assertEquals(0L, builder.add(buffer, AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 3, skipFrames = 1))
        assertEquals(buffer.limit(), buffer.position())
        val built = builder.build()
        assertEquals(2, built.frames)
        assertArrayEquals(floatArrayOf(0f, 0.625f / 3), built.validSamples(), 0f)
    }

    @Test
    fun otherEncodingsConvertToFloat() {
        val builder = PcmBuilder(channels = 1, estimatedFrames = 4)
        val packed = ByteBuffer.wrap(byteArrayOf(0, 0, 0x40, 0, 0, 0x80.toByte()))
        builder.add(packed, AudioFormat.ENCODING_PCM_24BIT_PACKED, fileChannels = 1, skipFrames = 0)
        builder.add(ByteBuffer.wrap(byteArrayOf(0x80.toByte(), 0)), AudioFormat.ENCODING_PCM_8BIT, fileChannels = 1, skipFrames = 0)
        val built = builder.build()
        assertEquals(4, built.frames)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 0f, -1f), built.validSamples(), 1e-6f)
    }

    @Test
    fun decodedSongTracksValidFrameCountIndependentOfSpareCapacity() {
        val backing = FloatArray(100) { if (it < 6) (it + 1).toFloat() else 999f }
        val song = DecodedSong(backing, channels = 2, sampleRate = 1_000, leadFrames = 0, mime = "audio/raw", codec = "test", header = null, frameCount = 3)
        assertEquals(3, song.frames)
        assertEquals(6, song.sampleCount)
        assertEquals(3.0, song.durationMs, 1e-9)
        assertTrue(song.samples.size > song.sampleCount)
        val le = song.toF32le()
        assertEquals(6 * Float.SIZE_BYTES, le.size)
        val decoded = FloatArray(6)
        ByteBuffer.wrap(le).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(decoded)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), decoded, 0f)
    }
}
