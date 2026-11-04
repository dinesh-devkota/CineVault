package com.dineshdevkota.cinevault.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private lateinit var db: CineVaultDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CineVaultDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() = db.close()

    @Test
    fun scanAndMetadataUpdatesPreservePlaybackAndManualTitle() = runBlocking {
        insertMovie()
        db.movies().rename("movie", "My title", "my title", 2001)
        db.movies().saveProgress("movie", 60_000, 120_000, 1)
        db.movies().markMissing("movie")
        db.movies().markPresent("movie", 512)
        db.movies().applyMetadata("movie", 603, "Overview", null, 120, 8.5, "/p.jpg", null, "[\"Drama\"]")
        db.movies().applyMatchedTitle("movie", "Automatic title", "automatic title", 1999, false)
        val movie = requireNotNull(db.movies().get("movie"))
        assertEquals("My title", movie.title)
        assertEquals(2001, movie.year)
        assertEquals(60_000L, movie.playbackPositionMs)
        assertEquals(120_000L, movie.durationMs)
        assertFalse(movie.isMissing)
        assertTrue(movie.metadataFetched)
        assertEquals(listOf("Drama"), movie.genres)
    }

    @Test
    fun deletingSourceCascadesToItsMovies() = runBlocking {
        insertMovie()
        db.sources().delete("source")
        assertNull(db.movies().get("movie"))
    }

    private suspend fun insertMovie() {
        db.sources().upsert(SourceEntity(id = "source", name = "Test NAS", host = "localhost", share = "fixture"))
        db.movies().insert(
            MovieEntity(
                id = "movie", sourceId = "source", filePath = "Movie.mp4", fileName = "Movie.mp4",
                fileSize = 256, parsedTitle = "Movie", parsedYear = 1999, title = "Movie", year = 1999,
            ),
        )
    }
}
