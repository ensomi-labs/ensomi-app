package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartedSongLibraryTest {
    /** A library that records what happens to it, so the order of a removal can be checked. */
    private class RecordingLibrary(songs: List<Song>, private val events: MutableList<String>) : SongLibrary {
        override val songs: StateFlow<List<Song>> = MutableStateFlow(songs)

        override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())

        override suspend fun findAgain(id: String, document: Uri) = false

        override fun remove(id: String) {
            events += "library"
        }

        override fun refresh() = Unit

        override fun markMissing(id: String) = Unit
    }

    @Test
    fun removalStopsTheSongsWorkBeforeItGivesThePermissionBack() {
        val events = mutableListOf<String>()
        val songs = listOf(Song("song-1", "A Song", null, 1_000, "song.flac"))
        val library = ChartedSongLibrary(
            library = RecordingLibrary(songs, events),
            chartIds = MutableStateFlow(setOf("song-1")),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onRemove = { events += "chart" },
        )

        library.remove("song-1")

        // The chart maker and the play sound are forgotten while the document is still readable.
        assertEquals(listOf("chart", "library"), events)
    }
}
