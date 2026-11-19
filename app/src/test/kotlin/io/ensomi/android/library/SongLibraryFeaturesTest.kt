package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLibraryFeaturesTest {
    private class RecordingLibrary(songs: List<Song>, private val events: MutableList<String>) : SongLibrary {
        override val songs: StateFlow<List<Song>> = MutableStateFlow(songs)
        override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())
        override suspend fun findAgain(id: String, document: Uri) = false
        override fun remove(id: String) {
            events += "library:$id"
        }
        override fun refresh() = Unit
        override fun markMissing(id: String) = Unit
    }

    @Test
    fun removalStopsChartAndAudioWorkBeforeReleasingDocumentPermission() {
        val events = mutableListOf<String>()
        val songs = listOf(Song("song-1", "A Song", null, 1_000, "song.flac"))
        val library = ChartedSongLibrary(
            library = RecordingLibrary(songs, events),
            chartIds = MutableStateFlow(setOf("song-1")),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onRemove = { events += "cleanup:$it" },
        )

        library.remove("song-1")

        assertEquals(listOf("cleanup:song-1", "library:song-1"), events)
    }

    @Test
    fun searchMatchesTitleArtistAndFileNameAcrossMultipleTerms() {
        val songs = listOf(
            Song("1", "Hesitation Snow", "fripSide", 240_000, "01_hesitation_snow.flac"),
            Song("2", "泡", "King Gnu", 273_000, "king_gnu_abuku.mp3"),
            Song("3", "Untitled Demo", null, 120_000, "mafumafu_onna.FLAC"),
        )

        assertEquals(songs, SongSearch.filter(songs, ""))
        assertEquals(songs, SongSearch.filter(songs, "   "))
        assertEquals(listOf(songs[0]), SongSearch.filter(songs, "fripside"))
        assertEquals(listOf(songs[1]), SongSearch.filter(songs, "king 泡"))
        assertEquals(listOf(songs[2]), SongSearch.filter(songs, "mafumafu"))
        assertTrue(SongSearch.filter(songs, "king snow").isEmpty())
    }

    @Test
    fun pendingRemovalSupportsUndoSupersedingAndFlush() {
        val committed = mutableListOf<String>()
        val removal = PendingSongRemoval { committed += it }

        removal.stage("song-a")
        assertEquals("song-a", removal.pendingSongId)
        assertTrue(committed.isEmpty())

        // Undo restores the song without committing removal.
        assertTrue(removal.undo("song-a"))
        assertNull(removal.pendingSongId)
        assertTrue(committed.isEmpty())
        assertFalse(removal.confirm("song-a"))

        // Staging a second song while one is pending commits the first immediately.
        removal.stage("song-a")
        removal.stage("song-b")
        assertEquals(listOf("song-a"), committed)
        assertEquals("song-b", removal.pendingSongId)

        // Confirming the superseded song does nothing; flush commits the active one once.
        assertFalse(removal.confirm("song-a"))
        removal.flush()
        removal.flush()
        assertEquals(listOf("song-a", "song-b"), committed)
        assertNull(removal.pendingSongId)
    }
}
