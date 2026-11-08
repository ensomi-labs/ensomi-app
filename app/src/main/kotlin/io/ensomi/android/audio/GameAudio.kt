package io.ensomi.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A game's sound: one `AudioTrack` (`ENCODING_PCM_FLOAT`, `MODE_STREAM`) fed by its own writer thread, first with
 * [leadInMs] of silence, then the song, then silence until the game closes it, so the output keeps reporting real
 * timestamps past the song's end. With [loopFromFrame] the song repeats from that frame instead of ending, as the
 * calibration's click track does. The music volume is the track's gain, which leaves frame positions alone.
 *
 * The writer fills the track's buffer ([QUEUED_MS] or more) before [play] and blocks while it is full, so at least
 * 200 ms stay queued; while the track does not play, it waits once the buffer is full. [playFrom] plays again from a
 * frame after a pause: the writer flushes the track, fills it from that frame and plays it. [onRoute] hears the device
 * the track plays on, on the main thread: once when it starts, and at every change after.
 */
class GameAudio(
    context: Context,
    private val song: DecodedSong,
    leadInMs: Double,
    volume: Float,
    private val onRoute: (AudioDeviceInfo?) -> Unit,
    /** Where the song starts again once it has played to its end; null to end it. */
    private val loopFromFrame: Int? = null,
) : PlayOutput, AutoCloseable {
    val sampleRate: Int = song.sampleRate
    val leadInFrames: Long = (leadInMs * sampleRate / 1000).roundToLong()
    private val channels = song.channels
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val track: AudioTrack
    private val writer: Thread
    private val timestamp = AudioTimestamp()

    /** Held for every call on the track but the writer's writes, so none comes after [close] released it. */
    private val lock = Any()

    @Volatile
    private var running = true

    private var released = false

    /** The track is to play: from [play] or [playFrom] until [pause]. Under [lock]. */
    private var playing = false

    /** The frame [playFrom] asked for, until the writer has flushed the track for it. Under [lock]. */
    private var restartFrame: Long? = null

    /** The frame of the timeline the track's positions count from: 0, or the last restart's. Under [lock]. */
    private var baseFrame = 0L

    private val loopFrom = loopFromFrame?.coerceIn(0, song.frames - 1)

    /** The track's underruns when it was released. */
    private var closedUnderruns = 0

    private val routing = AudioRouting.OnRoutingChangedListener { router -> if (running) onRoute(router.routedDevice) }

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, format.channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val queued = QUEUED_MS * sampleRate / 1000 * channels * Float.SIZE_BYTES
        track = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum, queued))
            .build()
        track.setVolume(volume.coerceIn(0f, 1f))
        track.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        Log.i(
            TAG,
            "Output: $sampleRate Hz, $channels channels, buffer ${track.bufferSizeInFrames} of " +
                "${track.bufferCapacityInFrames} frames, lead-in $leadInFrames frames, song ${song.frames} frames",
        )
        writer = Thread(::write, "EnsomiAudioWriter").also { it.start() }
    }

    /** The track's underruns so far (`AudioTrack.getUnderrunCount`), or at [close] once closed. */
    val underruns: Int
        get() = synchronized(lock) { if (released) closedUnderruns else track.underrunCount }

    /** The device the track plays on now; null until it has started. */
    val routedDevice: AudioDeviceInfo?
        get() = synchronized(lock) { if (released) null else track.routedDevice }

    /** On the timeline: until the writer has flushed the track for a restart, its frames are those to be dropped. */
    override fun timestamp(): OutputTimestamp? = synchronized(lock) {
        if (!released && restartFrame == null && track.getTimestamp(timestamp)) {
            OutputTimestamp(baseFrame + timestamp.framePosition, timestamp.nanoTime)
        } else {
            null
        }
    }

    override fun play() {
        synchronized(lock) {
            playing = true
            if (!released) track.play()
        }
        LockSupport.unpark(writer)
    }

    override fun pause() {
        synchronized(lock) {
            playing = false
            if (!released) track.pause()
        }
    }

    /** The track is paused, so the writer alone flushes it: no write of the frames it drops can come after. */
    override fun playFrom(frame: Long) {
        synchronized(lock) {
            if (released) return
            // Where a plain play would have gone on from: past the frames the output had taken, heard after the pause.
            val taken = baseFrame + (track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL)
            Log.i(
                TAG,
                String.format(
                    Locale.ROOT,
                    "Output: plays again from song time %.1f ms; the track had taken %.1f ms past it",
                    (frame - leadInFrames) * 1000.0 / sampleRate,
                    (taken - frame) * 1000.0 / sampleRate,
                ),
            )
            playing = true
            restartFrame = frame
        }
        LockSupport.unpark(writer)
    }

    /**
     * Debug builds only: asks the track for another output than the one it plays on, which Android reports as a route
     * change, so the pause on a change of output can be checked on an emulator, which has one output.
     */
    fun preferAnotherOutput(): String? {
        synchronized(lock) {
            if (released) return null
            val current = track.routedDevice?.id
            val other = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.isSink && it.id != current && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
                ?: return null
            track.preferredDevice = other
            return "${other.type}/${other.productName}"
        }
    }

    /**
     * Hands the track the timeline from its frame 0, or from a restart's frame: the lead-in's silence, the song, then
     * silence. A track that does not play takes nothing once full, and returns at once, so the writer waits then.
     */
    private fun write() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val chunk = CHUNK_MS * sampleRate / 1000
        val silence = FloatArray(chunk * channels)
        var frame = 0L
        // The track took less than it was handed: it does not play, and is full.
        var full = false
        // Filled again after a restart, it plays once full.
        var refilling = false
        while (running) {
            val wait = synchronized(lock) {
                val from = restartFrame
                if (from != null && !released) {
                    track.flush()
                    baseFrame = from
                    restartFrame = null
                    frame = from
                    full = false
                    refilling = true
                }
                full && !playing && !refilling
            }
            if (wait) {
                LockSupport.park(this)
                continue
            }
            // Full, though it is to play: it has not started yet. A moment later, rather than ask it again and again.
            if (full && !refilling) LockSupport.parkNanos(this, FULL_WAIT_NANOS)
            val songFrame = songFrameAt(frame, leadInFrames, song.frames, loopFrom)
            val count = when {
                frame < leadInFrames -> min(chunk.toLong(), leadInFrames - frame).toInt()
                songFrame < 0 -> chunk
                else -> min(chunk.toLong(), song.frames - songFrame).toInt()
            }
            val written = if (songFrame < 0) {
                track.write(silence, 0, count * channels, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(song.samples, songFrame.toInt() * channels, count * channels, AudioTrack.WRITE_BLOCKING)
            }
            if (written < 0) {
                if (running) Log.w(TAG, "The output refused a write: $written")
                return
            }
            frame += written / channels
            full = written < count * channels
            if (refilling && full) {
                refilling = false
                synchronized(lock) { if (playing && restartFrame == null && !released) track.play() }
            }
        }
    }

    /** Stops the writer and releases the track; logs the game's underruns. Any thread. */
    override fun close() {
        synchronized(lock) {
            if (released) return
            running = false
            closedUnderruns = track.underrunCount
            Log.i(TAG, "Output closed: $closedUnderruns underruns, routed to ${track.routedDevice?.productName}")
            track.removeOnRoutingChangedListener(routing)
            // Flushing frees the buffer and stop() wakes a blocked write; a writer waiting on a full track is woken.
            track.pause()
            track.flush()
            track.stop()
        }
        LockSupport.unpark(writer)
        writer.join(WRITER_JOIN_MS)
        synchronized(lock) {
            released = true
            track.release()
        }
    }

    companion object {
        private const val TAG = "EnsomiAudio"

        /** The track's buffer: what the writer keeps queued, at least. */
        const val QUEUED_MS = 400

        /** How much the writer hands the track at a time. */
        private const val CHUNK_MS = 20
        private const val FULL_WAIT_NANOS = 5_000_000L
        private const val WRITER_JOIN_MS = 1_000L

        /** A game's music: the same attributes for the track and for its audio focus. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}

/**
 * The song's frame at [frame] of a game's timeline, or -1 for silence: [leadInFrames] of silence, then the song's
 * [songFrames], then silence, or with [loopFrom] the song again from that frame without end.
 */
internal fun songFrameAt(frame: Long, leadInFrames: Long, songFrames: Int, loopFrom: Int?): Long {
    val inSong = frame - leadInFrames
    if (inSong < 0) return -1
    if (inSong < songFrames) return inSong
    val from = loopFrom ?: return -1
    return from + (inSong - songFrames) % (songFrames - from)
}
