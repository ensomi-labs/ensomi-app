package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A song the player added. [durationMs] is the file's length; [fileName] is what the document provider calls it. */
data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    /** Charts made from this song and kept on the device. */
    val chartCount: Int = 0,
    /** The document, readable through the persistable permission taken when it was picked; null for a sample. */
    val uri: Uri? = null,
    /** The file has gone: moved, deleted, or its permission revoked. The song stays until it is found or removed. */
    val missing: Boolean = false,
)

/** What adding documents did: how many became songs, how many were in the library already, which could not be read. */
data class AddResult(val added: Int, val alreadyThere: Int, val unreadable: List<String>)

/**
 * The songs Ensomi can make charts from: files the player picked with the system's document picker
 * (`ACTION_OPEN_DOCUMENT`), kept with a persistable read permission. No storage permission is needed.
 */
interface SongLibrary {
    val songs: StateFlow<List<Song>>

    fun song(id: String): Song? = songs.value.firstOrNull { it.id == id }

    /** Adds the documents the picker returned. */
    suspend fun add(documents: List<Uri>): AddResult

    /** Points the song [id] at [document], picked anew, keeping the song and its charts. False if it cannot be read. */
    suspend fun findAgain(id: String, document: Uri): Boolean

    /** Forgets a song; the file itself stays where it is. */
    fun remove(id: String)

    /** Looks again whether each song's file is still there. */
    fun refresh()

    /** Shows the song [id] as missing: its file failed to open (`DecodeException.missing`). */
    fun markMissing(id: String)
}

/**
 * A library whose songs say whether they have a chart (the last chart made for each is kept, [chartIds]), and whose
 * [remove] forgets a song's chart and decoded sound with it.
 */
class ChartedSongLibrary(
    private val library: SongLibrary,
    chartIds: StateFlow<Set<String>>,
    scope: CoroutineScope,
    private val onRemove: (String) -> Unit,
) : SongLibrary by library {
    override val songs: StateFlow<List<Song>> = combine(library.songs, chartIds) { songs, ids ->
        songs.map { if (it.id in ids) it.copy(chartCount = 1) else it }
    }.stateIn(scope, SharingStarted.Eagerly, library.songs.value)

    override fun song(id: String): Song? = songs.value.firstOrNull { it.id == id } ?: library.song(id)

    override fun remove(id: String) {
        // Stop the work that reads the document, and forget the chart and sound, before its permission is given back.
        onRemove(id)
        library.remove(id)
    }
}

/** Filters songs in memory by title, artist and file name, matching every whitespace-separated word of a query. */
object SongSearch {
    fun filter(songs: List<Song>, query: String): List<Song> {
        val terms = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return songs
        return songs.filter { song ->
            terms.all { term ->
                song.title.contains(term, ignoreCase = true) ||
                    (song.artist?.contains(term, ignoreCase = true) == true) ||
                    song.fileName.contains(term, ignoreCase = true)
            }
        }
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Tracks a single song hidden from the visible library while its Undo snackbar is shown. Starting a second removal
 * commits the earlier one first; Undo restores the song without touching its chart or document permission; [flush]
 * commits any waiting removal when the app leaves the foreground.
 */
class PendingSongRemoval(private val commitRemoval: (String) -> Unit) {
    var pendingSongId: String? = null
        private set

    fun stage(songId: String) {
        val previous = pendingSongId
        if (previous != null && previous != songId) {
            pendingSongId = null
            commitRemoval(previous)
        }
        pendingSongId = songId
    }

    fun undo(songId: String): Boolean {
        if (pendingSongId != songId) return false
        pendingSongId = null
        return true
    }

    fun confirm(songId: String): Boolean {
        if (pendingSongId != songId) return false
        pendingSongId = null
        commitRemoval(songId)
        return true
    }

    fun flush() {
        val current = pendingSongId ?: return
        pendingSongId = null
        commitRemoval(current)
    }
}
