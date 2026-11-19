package io.ensomi.android.audio

import kotlin.math.min

/**
 * A readable, seekable stream of interleaved float PCM frames at [sampleRate] Hz with [channels] channels.
 * Decouples [GameAudio] from how audio is stored so playback can come from an in-memory [DecodedSong]
 * ([ArrayPcmSource]) or a native streaming decoder.
 *
 * Called from one thread at a time (the audio writer thread); [close] runs after that thread stops.
 */
interface AudioPcmSource : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /** Total frames in the source when known up front, or null for an unbounded or streaming source. */
    val frames: Int?

    /**
     * Reads up to [frameCount] interleaved frames into [dest] starting at float index [offset], returning the number
     * of frames actually read (`0` at end of stream).
     */
    fun read(dest: FloatArray, offset: Int, frameCount: Int): Int

    /** Positions the next [read] at [frame] (0-based from the start of the song). */
    fun seek(frame: Long)

    override fun close() = Unit
}

/** An [AudioPcmSource] backed by a [DecodedSong] already in memory, respecting [DecodedSong.frames]. */
class ArrayPcmSource(private val song: DecodedSong) : AudioPcmSource {
    override val sampleRate: Int = song.sampleRate
    override val channels: Int = song.channels
    override val frames: Int = song.frames

    private var position: Int = 0

    override fun read(dest: FloatArray, offset: Int, frameCount: Int): Int {
        if (frameCount <= 0 || position >= frames) return 0
        val toRead = min(frameCount, frames - position)
        val start = position * channels
        song.samples.copyInto(dest, destinationOffset = offset, startIndex = start, endIndex = start + toRead * channels)
        position += toRead
        return toRead
    }

    override fun seek(frame: Long) {
        position = frame.coerceIn(0L, frames.toLong()).toInt()
    }

    override fun close() = Unit
}

/**
 * Stages chunks of a game's audio timeline (lead-in silence, [source] PCM with optional looping, trailing silence)
 * for [GameAudio]'s writer thread. Preserves unwritten tail samples across partial writes without re-reading or
 * seeking [source], and allocates no arrays after construction.
 */
internal class TimelineFeeder(
    private val source: AudioPcmSource,
    private val leadInFrames: Long,
    loopFromFrame: Int?,
    private val chunkFrames: Int,
) {
    private val channels = source.channels
    private val silence = FloatArray(chunkFrames * channels)
    private val pcm = FloatArray(chunkFrames * channels)
    private val loopFrom: Int? = loopFromFrame?.let { raw ->
        val known = source.frames
        if (known != null) raw.coerceIn(0, (known - 1).coerceAtLeast(0)) else raw.coerceAtLeast(0)
    }

    /** Current position on the playback timeline (frame 0 is the start of the lead-in). */
    var frame: Long = 0L
        private set

    /** True when the last [writeNext] accepted fewer samples than offered (output buffer is full). */
    var lastWritePartial: Boolean = false
        private set

    private var pendingOffset = 0
    private var pendingFrames = 0
    private var sourceEof = false

    /** Seeks the timeline to [timelineFrame], discarding any buffered partial-write remainder. */
    fun seekTimeline(timelineFrame: Long) {
        frame = maxOf(0L, timelineFrame)
        pendingOffset = 0
        pendingFrames = 0
        val known = source.frames
        when {
            frame < leadInFrames -> {
                source.seek(0L)
                sourceEof = false
            }
            known != null -> {
                val target = songFrameAt(frame, leadInFrames, known, loopFrom)
                if (target < 0L) {
                    sourceEof = true
                } else {
                    source.seek(target)
                    sourceEof = false
                }
            }
            else -> {
                source.seek(frame - leadInFrames)
                sourceEof = false
            }
        }
    }

    /**
     * Hands the next chunk to [write] and advances [frame]. Returns the sample count written (or a negative error code
     * from [write]). Sets [lastWritePartial] when `0 <= written < requested`.
     */
    fun writeNext(write: (buffer: FloatArray, offset: Int, sampleCount: Int) -> Int): Int {
        if (pendingFrames > 0) {
            val requested = pendingFrames * channels
            val written = write(pcm, pendingOffset, requested)
            if (written < 0) return written
            val writtenFrames = written / channels
            pendingOffset += writtenFrames * channels
            pendingFrames -= writtenFrames
            frame += writtenFrames
            lastWritePartial = written < requested
            return written
        }
        if (frame < leadInFrames) {
            val count = min(chunkFrames.toLong(), leadInFrames - frame).toInt()
            return writeSilence(count, write)
        }
        if (sourceEof) {
            return writeSilence(chunkFrames, write)
        }
        var readFrames = source.read(pcm, 0, chunkFrames)
        if (readFrames <= 0) {
            val loop = loopFrom
            if (loop != null) {
                source.seek(loop.toLong())
                readFrames = source.read(pcm, 0, chunkFrames)
            }
            if (readFrames <= 0) {
                sourceEof = true
                return writeSilence(chunkFrames, write)
            }
        }
        val requested = readFrames * channels
        val written = write(pcm, 0, requested)
        if (written < 0) return written
        val writtenFrames = written / channels
        frame += writtenFrames
        if (writtenFrames < readFrames) {
            pendingOffset = writtenFrames * channels
            pendingFrames = readFrames - writtenFrames
        }
        lastWritePartial = written < requested
        return written
    }

    private fun writeSilence(frameCount: Int, write: (FloatArray, Int, Int) -> Int): Int {
        val requested = frameCount * channels
        val written = write(silence, 0, requested)
        if (written < 0) return written
        frame += written / channels
        lastWritePartial = written < requested
        return written
    }
}
