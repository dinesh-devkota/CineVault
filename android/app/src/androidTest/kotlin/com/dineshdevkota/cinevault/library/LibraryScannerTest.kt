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
}
