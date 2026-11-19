package io.ensomi.android.play

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ensomi.android.R
import io.ensomi.android.audio.ArrayPcmSource
import io.ensomi.android.audio.DecodeException
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.PlayAudio
import io.ensomi.android.audio.PlayAudioCache
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.audio.StreamingPcmSource
import io.ensomi.android.audio.fileExtensionHint
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.StoredChart
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.runtime.AudioException
import io.ensomi.runtime.OsuException
import io.ensomi.runtime.parseOsu
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a game of the player's song needs before it starts: its last chart and a playable audio source. */
sealed interface GameLoad {
    data object Loading : GameLoad

    class Ready(val chart: PlayChart, val audio: PlayAudio, val record: StoredChart) : GameLoad

    /** [message] is a string resource, said in place of the game. */
    data class Failed(val message: Int) : GameLoad
}

/**
 * Loads a game off the main thread: the song's last chart from app storage, read back by the runtime's `.osu` parser,
 * and a native streaming stereo [StreamingPcmSource] (with [SongDecoder] fallback for formats the native decoder does
 * not support, such as Opus or HE-AAC). Work nobody waits for stops first ([ChartMaker.quiet]), so the game has the
 * processor. Any pre-opened streaming handle that is never claimed by [io.ensomi.android.audio.GameAudio] is closed in
 * [onCleared].
 */
class GameLoader(
    context: Context,
    private val song: Song,
    private val charts: ChartMaker,
    private val songs: SongLibrary,
    private val cache: PlayAudioCache?,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableLoad = MutableStateFlow<GameLoad>(GameLoad.Loading)
    val load: StateFlow<GameLoad> = mutableLoad.asStateFlow()

    private var retainedAudio: PlayAudio? = null
    private var cleared = false

    init {
        viewModelScope.launch(Dispatchers.IO) { mutableLoad.value = loadGame() }
    }

    private suspend fun loadGame(): GameLoad {
        charts.awaitQuiet()
        val uri = song.uri ?: return GameLoad.Failed(R.string.play_failed_missing)
        val last = charts.lastChart(song.id) ?: return GameLoad.Failed(R.string.play_failed_no_chart)
        val beatmap = try {
            parseOsu(last.osu)
        } catch (failure: OsuException) {
            Log.w(TAG, "The chart of ${song.id} could not be read: ${failure.message}")
            return GameLoad.Failed(R.string.play_failed_chart)
        }

        // Reuse an in-memory fallback decode if this exact song is already cached.
        val cached = cache?.get(song.id, uri)
        if (cached != null) {
            val playAudio = PlayAudio(ArrayPcmSource(cached)) { ArrayPcmSource(cached) }
            val chart = PlayChart.of(song.title, beatmap.chart, ceil(cached.durationMs).toInt())
            return if (retain(playAudio)) GameLoad.Ready(chart, playAudio, last.record) else GameLoad.Loading
        }

        // Drop any previously cached song's PCM before opening or decoding a new song.
        cache?.clear()
        val hintExt = fileExtensionHint(song.fileName)
        val started = System.nanoTime()
        val streamed = try {
            StreamingPcmSource.open(appContext, uri, hintExt)
        } catch (failure: AudioException.Unsupported) {
            Log.i(TAG, "Native streaming unsupported for ${song.fileName}, falling back to MediaCodec: ${failure.message}")
            null
        } catch (failure: DecodeException) {
            if (failure.missing) songs.markMissing(song.id)
            return GameLoad.Failed(if (failure.missing) R.string.play_failed_missing else R.string.play_failed_decode)
        } catch (failure: AudioException) {
            Log.w(TAG, "Native streaming open failed for ${song.fileName}", failure)
            return GameLoad.Failed(R.string.play_failed_decode)
        }

        if (streamed != null) {
            val openedMs = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "Opened ${song.fileName} for streaming play (${streamed.codec}, ${streamed.sampleRate} Hz) in $openedMs ms")
            val durationMs = streamed.durationMs?.takeIf { it > 0.0 }
                ?: song.durationMs.takeIf { it > 0L }?.toDouble()
                ?: beatmap.chart.durationMs.toDouble()
            val playAudio = PlayAudio(streamed) { StreamingPcmSource.open(appContext, uri, hintExt) }
            val chart = PlayChart.of(song.title, beatmap.chart, ceil(durationMs).toInt())
            return if (retain(playAudio)) GameLoad.Ready(chart, playAudio, last.record) else GameLoad.Loading
        }

        // Fallback path for formats not handled by the native decoder (e.g. Opus, HE-AAC).
        val decoded = try {
            SongDecoder.decode(appContext, uri, PcmChannels.STEREO).also {
                Log.i(TAG, "Decoded ${song.fileName} for play (fallback) in ${(System.nanoTime() - started) / 1_000_000} ms")
                cache?.put(song.id, uri, it)
            }
        } catch (failure: OutOfMemoryError) {
            Log.w(TAG, "Decoding ${song.fileName} for play ran out of memory", failure)
            return GameLoad.Failed(R.string.play_failed_memory)
        } catch (failure: DecodeException) {
            if (failure.missing) songs.markMissing(song.id)
            return GameLoad.Failed(if (failure.missing) R.string.play_failed_missing else R.string.play_failed_decode)
        } catch (failure: IllegalStateException) {
            // MediaCodec's own failures.
            Log.w(TAG, "Decoding ${song.fileName} for play failed", failure)
            return GameLoad.Failed(R.string.play_failed_decode)
        }
        val playAudio = PlayAudio(ArrayPcmSource(decoded)) { ArrayPcmSource(decoded) }
        val chart = PlayChart.of(song.title, beatmap.chart, ceil(decoded.durationMs).toInt())
        return if (retain(playAudio)) GameLoad.Ready(chart, playAudio, last.record) else GameLoad.Loading
    }

    @Synchronized
    private fun retain(audio: PlayAudio): Boolean {
        if (cleared) {
            audio.close()
            return false
        }
        retainedAudio?.close()
        retainedAudio = audio
        return true
    }

    override fun onCleared() {
        synchronized(this) {
            cleared = true
            retainedAudio?.close()
            retainedAudio = null
        }
    }

    private companion object {
        const val TAG = "EnsomiPlay"
    }
}
