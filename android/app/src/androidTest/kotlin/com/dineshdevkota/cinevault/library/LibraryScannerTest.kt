package com.dineshdevkota.cinevault.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dineshdevkota.cinevault.core.RemoteFile
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SecretBox
import com.dineshdevkota.cinevault.data.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.dineshdevkota.cinevault.smb.ScanProgress
import java.io.IOException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScannerTest {
    private lateinit var database: CineVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CineVaultDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun editedSourceDiscardsOldShareResults() = runBlocking {
        val source = SourceEntity(name = "NAS", host = "localhost", share = "original")
        database.sources().upsert(source)
        val existing = MovieEntity(
            sourceId = source.id, filePath = "Existing.mp4", fileName = "Existing.mp4", fileSize = 10,
            parsedTitle = "Existing", parsedYear = null, title = "Existing", year = null,
        )
        database.movies().insert(existing)
        var metadataRan = false
        val scanner = LibraryScanner(database, SecretBox(), scanVideos = {
            database.sources().upsert(source.copy(share = "replacement"))
            listOf(RemoteFile("Old Share.mp4", 100))
        }, afterScan = { metadataRan = true; null })
        scanner.scan(source.id)
        assertEquals(listOf(existing), database.movies().forSource(source.id))
        assertNull(database.sources().get(source.id)!!.lastScanAt)
        assertFalse(metadataRan)
        assertFalse(scanner.isScanning.value)
    }

    @Test
    fun deletedSourceIsNotRecreatedAfterScan() = runBlocking {
        val source = SourceEntity(name = "NAS", host = "localhost", share = "movies")
        database.sources().upsert(source)
        val scanner = LibraryScanner(database, SecretBox(), scanVideos = {
            database.sources().delete(source.id)
            listOf(RemoteFile("Movie.mp4", 100))
        })
        scanner.scan(source.id)
        assertNull(database.sources().get(source.id))
        assertEquals(emptyList<MovieEntity>(), database.movies().forSource(source.id))
        assertFalse(scanner.isScanning.value)
    }

    @Test fun discoveredItemsAreVisibleBeforeTraversalFinishesAndFailedScansDoNotPrune() = runBlocking {
        val source = SourceEntity(name = "Media", host = "localhost", share = "Media")
        database.sources().upsert(source)
        val old = MovieEntity(sourceId = source.id, filePath = "Movies/Old.mp4", fileName = "Old.mp4", fileSize = 100,
            parsedTitle = "Old", parsedYear = null, title = "My edited title", year = null, isWatched = true)
        database.movies().insert(old)
        val scanner = LibraryScanner(database, SecretBox(), scanVideos = { error("Progress walker must be used") },
            scanWithProgress = { _, report ->
                report(ScanProgress(listOf(RemoteFile("Music/Album/01 - Song.mp3", 100)), 1, 2))
                val partial = database.movies().forSource(source.id)
                assertEquals(2, partial.size)
                assertTrue(partial.single { it.filePath.endsWith("mp3") }.metadataFetched)
                assertEquals("01 - Song", partial.single { it.filePath.endsWith("mp3") }.title)
                assertEquals(old, partial.single { it.id == old.id })
                throw IOException("Fixture denied a later folder")
            })
        scanner.scan(source.id)
        assertEquals(2, database.movies().forSource(source.id).size)
        assertFalse(database.movies().get(old.id)!!.isMissing)
        assertNull(database.sources().get(source.id)!!.lastScanAt)
        assertTrue(scanner.status.value!!.contains("Items already found are kept"))
    }

    @Test fun completedProgressScanPreservesEditsAndPrunesOnlyAfterTheLastBatch() = runBlocking {
        val source = SourceEntity(name = "Media", host = "localhost", share = "Media")
        database.sources().upsert(source)
        val old = MovieEntity(sourceId = source.id, filePath = "Old.mp4", fileName = "Old.mp4", fileSize = 100,
            parsedTitle = "Old", parsedYear = null, title = "Old", year = null)
        database.movies().insert(old)
        val files = listOf(RemoteFile("Movies/New.mp4", 100), RemoteFile("Documentaries/Nature.mp4", 100))
        val scanner = LibraryScanner(database, SecretBox(), scanVideos = { error("Progress walker must be used") },
            scanWithProgress = { _, report ->
                report(ScanProgress(files.take(1), 1, 1))
                assertFalse(database.movies().get(old.id)!!.isMissing)
                report(ScanProgress(files.drop(1), 2, 2))
                assertEquals(3, database.movies().forSource(source.id).size)
                files
            })
        scanner.scan(source.id)
        assertTrue(database.movies().get(old.id)!!.isMissing)
        assertTrue(database.sources().get(source.id)!!.lastScanAt != null)
        assertTrue(scanner.status.value!!.contains("1 movies, 1 documentaries, 0 TV episodes, 0 music items (2 new)"))
    }
}
