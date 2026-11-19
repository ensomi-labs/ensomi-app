package io.ensomi.android.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ensomi.runtime.AudioChannels
import io.ensomi.runtime.AudioDecoder
import io.ensomi.runtime.AudioException
import io.ensomi.runtime.AudioInfo
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal seam over [AudioDecoder] so [StreamingPcmSource] can be unit-tested on the JVM without native `.so`. */
internal interface NativeAudioStream : AutoCloseable {
    fun readF32le(maxFrames: UInt): ByteArray
    fun seekFrame(frame: ULong)
    override fun close()
}

private class RuntimeAudioStream(private val decoder: AudioDecoder) : NativeAudioStream {
    override fun readF32le(maxFrames: UInt): ByteArray = decoder.readF32le(maxFrames)
    override fun seekFrame(frame: ULong) = decoder.seekFrame(frame)
    override fun close() = decoder.close()
}

/**
 * Streams interleaved float PCM directly from the runtime's native [AudioDecoder] (`ensomi-audio` / Symphonia),
 * keeping full-song PCM off the JVM heap.
 */
class StreamingPcmSource internal constructor(
    private val stream: NativeAudioStream,
    override val sampleRate: Int,
    override val channels: Int,
    override val frames: Int?,
    val durationMs: Double?,
    val codec: String,
    val leadFrames: Int,
) : AudioPcmSource {
    private val closed = AtomicBoolean(false)

    constructor(decoder: AudioDecoder) : this(RuntimeAudioStream(decoder), decoder.info())

    private constructor(stream: NativeAudioStream, info: AudioInfo) : this(
        stream = stream,
        sampleRate = info.sampleRate.toInt(),
        channels = info.channels.toInt(),
        frames = info.totalFrames?.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt(),
        durationMs = info.durationMs,
        codec = info.codec,
        leadFrames = info.leadFrames,
    )

    override fun read(dest: FloatArray, offset: Int, frameCount: Int): Int {
        if (closed.get() || frameCount <= 0 || channels <= 0) return 0
        val maxFit = (dest.size - offset) / channels
        val toRead = minOf(frameCount, maxFit)
        if (toRead <= 0) return 0
        val bytes = try {
            stream.readF32le(toRead.toUInt())
        } catch (failure: AudioException) {
            Log.w(TAG, "Streaming read failed", failure)
            return 0
        }
        if (bytes.isEmpty()) return 0
        return decodeF32leInto(bytes, dest, offset, channels)
    }

    override fun seek(frame: Long) {
        if (closed.get()) return
        try {
            stream.seekFrame(maxOf(0L, frame).toULong())
        } catch (failure: AudioException) {
            Log.w(TAG, "Streaming seek to frame $frame failed", failure)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream.close() }
    }

    companion object {
        private const val TAG = "EnsomiAudio"

        /**
         * Opens [uri] for native stereo streaming via [AudioDecoder.openFd]. Ownership of the detached FD transfers
         * immediately to Rust, which closes it even on error.
         */
        fun open(context: Context, uri: Uri, hintExt: String?): StreamingPcmSource {
            val fd = openDetachedReadFd(context, uri)
            val decoder = AudioDecoder.openFd(fd, hintExt, AudioChannels.STEREO)
            return try {
                StreamingPcmSource(decoder)
            } catch (failure: Throwable) {
                runCatching { decoder.close() }
                throw failure
            }
        }
    }
}

/**
 * Provides an [AudioPcmSource] for each play run (initial launch and any Restart on [io.ensomi.android.play.PlayScreen]).
 * Claims a pre-opened, already-validated source on the first [open] call without double-opening the file, and calls
 * [reopen] on subsequent runs. Calling [close] releases any unclaimed pre-opened source so no FD can leak.
 */
class PlayAudio(
    initial: AudioPcmSource,
    private val reopen: () -> AudioPcmSource,
) : AutoCloseable {
    private var preopened: AudioPcmSource? = initial
    private var closed = false

    @Synchronized
    fun open(): AudioPcmSource {
        val claimed = preopened
        if (claimed != null) {
            preopened = null
            return claimed
        }
        return reopen()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val unclaimed = preopened
        preopened = null
        runCatching { unclaimed?.close() }
    }
}

/** Extracts a lowercase file extension hint (without leading dot), or null when [name] has none. */
fun fileExtensionHint(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.lastIndex) return null
    return name.substring(dot + 1).lowercase(Locale.ROOT)
}

/**
 * Opens [uri] read-only and detaches its POSIX file descriptor for immediate handoff to a Rust `*_fd` binding.
 * Never close the returned FD from Kotlin: Rust assumes ownership and closes it on return or error.
 */
fun openDetachedReadFd(context: Context, uri: Uri): Int {
    val pfd = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (e: FileNotFoundException) {
        throw DecodeException("The file is not there.", missing = true, cause = e)
    } catch (e: SecurityException) {
        throw DecodeException("Ensomi may no longer read the file.", missing = true, cause = e)
    } catch (e: IOException) {
        throw DecodeException("The file could not be opened.", cause = e)
    } ?: throw DecodeException("The file is not there.", missing = true)
    return pfd.detachFd()
}

/** Copies little-endian IEEE 754 float bytes into [dest] starting at [offset], returning the interleaved frame count. */
internal fun decodeF32leInto(bytes: ByteArray, dest: FloatArray, offset: Int, channels: Int): Int {
    val sampleCount = bytes.size / Float.SIZE_BYTES
    val frameCount = sampleCount / channels
    val alignedSamples = frameCount * channels
    if (alignedSamples <= 0) return 0
    ByteBuffer.wrap(bytes, 0, alignedSamples * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()
        .get(dest, offset, alignedSamples)
    return frameCount
}
