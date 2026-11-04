package com.dineshdevkota.cinevault.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dineshdevkota.cinevault.core.RemoteFile
import com.dineshdevkota.cinevault.library.LibraryScanner
import com.dineshdevkota.cinevault.metadata.MetadataService
import com.dineshdevkota.cinevault.metadata.TmdbClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opens a real v1 file through the production factory, including migration registration. */
@RunWith(AndroidJUnit4::class)
class WatchlistMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: CineVaultDatabase? = null

    private data class LegacyLibrary(val source: SourceEntity, val movie: MovieEntity)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "watchlist-migration-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun versionOneUpgradePreservesLibraryCredentialsAndPlayback() = runBlocking {
        val legacy = createVersionOneDatabase()
        val db = openDatabase()

        // Reading through Room forces migration and validates the entire new schema.
        val source = requireNotNull(db.sources().get(legacy.source.id))
        val movie = requireNotNull(db.movies().get(legacy.movie.id))
        assertEquals(legacy.source, source)
        assertEquals(legacy.movie, movie)
        assertEquals("fixture-only", source.config(SecretBox()).password)
        assertFalse("Existing movies must start outside the watchlist", movie.isWatchlisted)
        assertEquals(3, db.openHelper.readableDatabase.version)
    }

    @Test
    fun versionTwoUpgradePreservesWatchlistAndPlaybackWhileAddingTVColumns() = runBlocking {
        val legacy = createVersionOneDatabase()
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE movies ADD COLUMN isWatchlisted INTEGER NOT NULL DEFAULT 0")
            it.execSQL("UPDATE movies SET isWatchlisted = 1")
            it.version = 2
        }
        val db = openDatabase()
        val movie = requireNotNull(db.movies().get(legacy.movie.id))
        assertEquals(legacy.movie.copy(isWatchlisted = true), movie)
        assertEquals(legacy.source, db.sources().get(legacy.source.id))
        assertEquals(3, db.openHelper.readableDatabase.version)
        db.movies().applyTVMetadata(movie.id, "Example", 42, 0, 1, "/season.jpg", "Episode", 40, 8.0,
            "/series.jpg", "/backdrop.jpg", "[\"Drama\"]")
        val updated = requireNotNull(db.movies().get(movie.id))
        assertEquals(42, updated.seriesTmdbId)
        assertEquals(0, updated.seasonNumber)
        assertEquals(movie.playbackPositionMs, updated.playbackPositionMs)
        assertEquals(movie.isWatched, updated.isWatched)
        assertEquals(movie.isWatchlisted, updated.isWatchlisted)
        assertEquals(movie.isLocked, updated.isLocked)
    }

    @Test
    fun watchlistChangesPersistAcrossDatabaseReopens() = runBlocking {
        val legacy = createVersionOneDatabase()
        var db = openDatabase()
        db.movies().setWatchlisted(legacy.movie.id, true)

        db = openDatabase()
        assertTrue(requireNotNull(db.movies().get(legacy.movie.id)).isWatchlisted)
        db.movies().setWatchlisted(legacy.movie.id, false)

        db = openDatabase()
        assertEquals(legacy.movie, db.movies().get(legacy.movie.id))
        assertEquals(legacy.source, db.sources().get(legacy.source.id))
    }

    @Test
    fun rescansMetadataAndPlaybackEditsKeepWatchlistMembership() = runBlocking {
        val legacy = createVersionOneDatabase()
        val db = openDatabase()
        val movies = db.movies()
        val id = legacy.movie.id
        movies.setWatchlisted(id, true)
        movies.rename(id, "My new title", "my new title", 2005)
        movies.saveProgress(id, 120_000, 900_000, 100)
        movies.markFinished(id, 900_000, 101)
        movies.resetProgress(id)
        movies.setHidden(id, false)
        movies.markPresent(id, legacy.movie.fileSize)
        movies.applyMetadata(id, 604, "New overview", null, 100, 7.0, "/new.jpg", null, "[\"Action\"]")
        movies.applyMatchedTitle(id, "New matched title", "new matched title", 2020, true)
        val edited = requireNotNull(movies.get(id))
        assertEquals("New matched title", edited.title)
        assertFalse(edited.isMissing)
        assertTrue(edited.isWatchlisted)

        // Exercise the actual reset service with no API key, so this test never makes a TMDB call.
        MetadataService(db, TmdbClient(OkHttpClient()) { "" }).resetToFile(id)
        assertTrue(requireNotNull(movies.get(id)).isWatchlisted)
        assertFalse(requireNotNull(movies.get(id)).metadataFetched)

        var remoteFiles = emptyList<RemoteFile>()
        val scanner = LibraryScanner(db, SecretBox(), scanVideos = { remoteFiles })
        scanner.scan(legacy.source.id)
        assertTrue(requireNotNull(movies.get(id)).isMissing)
        assertTrue(requireNotNull(movies.get(id)).isWatchlisted)

        remoteFiles = listOf(
            RemoteFile(legacy.movie.filePath, 1_024),
            RemoteFile("Movies/New (2024).mp4", 2_048),
        )
        scanner.scan(legacy.source.id)
        val restored = requireNotNull(movies.get(id))
        assertFalse(restored.isMissing)
        assertTrue(restored.isWatchlisted)
        assertEquals(1_024L, restored.fileSize)
        val newMovie = movies.forSource(legacy.source.id).single { it.id != id }
        assertFalse("A scan must not add new movies to the watchlist", newMovie.isWatchlisted)
        val updatedSource = requireNotNull(db.sources().get(legacy.source.id))
        assertTrue(requireNotNull(updatedSource.lastScanAt) > requireNotNull(legacy.source.lastScanAt))

        val reopened = openDatabase()
        assertTrue(requireNotNull(reopened.movies().get(id)).isWatchlisted)
    }

    private fun openDatabase(): CineVaultDatabase {
        database?.close()
        return CineVaultDatabase.create(context, databaseName).also { database = it }
    }

    private fun createVersionOneDatabase(): LegacyLibrary {
        val legacy = LegacyLibrary(
            source = SourceEntity(
                id = "legacy-source", name = "Original NAS", host = "fixture.invalid", port = 1445,
                share = "CINEVAULT", rootPath = "Movies", username = "cinevault",
                encryptedPassword = SecretBox().encrypt("fixture-only"), lastScanAt = 1_234,
            ),
            movie = MovieEntity(
                id = "legacy-movie", sourceId = "legacy-source", filePath = "Movies/Feature (2020).mp4",
                fileName = "Feature (2020).mp4", fileSize = 512, parsedTitle = "Feature", parsedYear = 2020,
                title = "Family pick", sortTitle = "family pick", year = 2001, overview = "Saved overview",
                tagline = "Saved tagline", tmdbId = 603, posterPath = "/poster.jpg", backdropPath = "/backdrop.jpg",
                genres = listOf("Drama", "Action"), runtimeMinutes = 120, rating = 8.75,
                metadataFetched = true, isLocked = true, addedAt = 5_678, isWatched = true,
                playbackPositionMs = 90_000, durationMs = 600_000, lastPlayedAt = 9_012,
                isHidden = true, isMissing = true,
            ),
        )
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("schemas/cinevault-v1.sql").bufferedReader().use { it.readText() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val sql = schema.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { old.execSQL(it) }
            assertEquals("Fixture must be the old version", 1, old.version)
            old.rawQuery("PRAGMA table_info(movies)", null).use { columns ->
                while (columns.moveToNext()) {
                    assertFalse(columns.getString(columns.getColumnIndexOrThrow("name")) == "isWatchlisted")
                }
            }
            old.insertOrThrow("sources", null, sourceValues(legacy.source))
            old.insertOrThrow("movies", null, movieValues(legacy.movie))
        }
        return legacy
    }

    /** Write only v1 columns; current entities must never be used to create the historical schema. */
    private fun sourceValues(source: SourceEntity) = ContentValues().apply {
        put("id", source.id)
        put("name", source.name)
        put("host", source.host)
        put("port", source.port)
        put("share", source.share)
        put("rootPath", source.rootPath)
        put("username", source.username)
        put("encryptedPassword", source.encryptedPassword)
        put("lastScanAt", source.lastScanAt)
    }

    private fun movieValues(movie: MovieEntity) = ContentValues().apply {
        put("id", movie.id)
        put("sourceId", movie.sourceId)
        put("filePath", movie.filePath)
        put("fileName", movie.fileName)
        put("fileSize", movie.fileSize)
        put("parsedTitle", movie.parsedTitle)
        put("parsedYear", movie.parsedYear)
        put("title", movie.title)
        put("sortTitle", movie.sortTitle)
        put("year", movie.year)
        put("overview", movie.overview)
        put("tagline", movie.tagline)
        put("tmdbId", movie.tmdbId)
        put("posterPath", movie.posterPath)
        put("backdropPath", movie.backdropPath)
        put("genres", Converters().fromList(movie.genres))
        put("runtimeMinutes", movie.runtimeMinutes)
        put("rating", movie.rating)
        put("metadataFetched", movie.metadataFetched)
        put("isLocked", movie.isLocked)
        put("addedAt", movie.addedAt)
        put("isWatched", movie.isWatched)
        put("playbackPositionMs", movie.playbackPositionMs)
        put("durationMs", movie.durationMs)
        put("lastPlayedAt", movie.lastPlayedAt)
        put("isHidden", movie.isHidden)
        put("isMissing", movie.isMissing)
    }
}
