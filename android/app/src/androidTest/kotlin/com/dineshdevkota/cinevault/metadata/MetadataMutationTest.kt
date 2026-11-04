package com.dineshdevkota.cinevault.metadata

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.library.LibraryEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MetadataMutationTest {
    private lateinit var database: CineVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CineVaultDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun movie(): MovieEntity {
        val source = SourceEntity(name = "Fixture", host = "localhost", share = "movies")
        database.sources().upsert(source)
        return MovieEntity(
            sourceId = source.id, filePath = "Original (2000).mp4", fileName = "Original (2000).mp4",
            fileSize = 1, parsedTitle = "Original", parsedYear = 2000, title = "Original", year = 2000,
            tmdbId = 1,
        ).also { database.movies().insert(it) }
    }

    /** Holds the automatic reply while a manual operation is queued. No network is used. */
    private class Replies {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.pathSegments.last().toInt()
            requests += id
            if (requests.size == 1) {
                started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out releasing metadata response" }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"id":$id,"title":"Match $id","overview":"Details $id"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    @Test
    fun manualMatchWinsOverAnInFlightAutomaticReply() = runBlocking {
        withTimeout(15_000) {
            val movie = movie()
            val replies = Replies()
            val service = MetadataService(database, TmdbClient(replies.client) { "key" })
            val editor = LibraryEditor(database, service)
            val automatic = async(Dispatchers.IO) { service.match(movie) }
            replies.started.await()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { editor.fixMatch(movie.id, 2) }
            try {
                replies.release.countDown()
                automatic.await()
                manual.await()
                val saved = database.movies().get(movie.id)!!
                assertEquals(2, saved.tmdbId)
                assertEquals("Match 2", saved.title)
                assertEquals("Details 2", saved.overview)
                assertTrue(saved.isLocked)
                assertTrue(saved.metadataFetched)
            } finally {
                replies.release.countDown()
            }
        }
    }

    @Test
    fun resetWaitsForAnInFlightReplyThenClearsItsMetadata() = runBlocking {
        withTimeout(15_000) {
            val movie = movie()
            val replies = Replies()
            var key = "key"
            val service = MetadataService(database, TmdbClient(replies.client) { key })
            val automatic = async(Dispatchers.IO) { service.match(movie) }
            replies.started.await()
            key = "" // Reset with no replacement lookup makes the stored state unambiguous.
            val reset = async(start = CoroutineStart.UNDISPATCHED) { service.resetToFile(movie.id) }
            try {
                replies.release.countDown()
                automatic.await()
                reset.await()
                val saved = database.movies().get(movie.id)!!
                assertEquals("Original", saved.title)
                assertNull(saved.tmdbId)
                assertNull(saved.overview)
                assertFalse(saved.metadataFetched)
                assertFalse(saved.isLocked)
            } finally {
                replies.release.countDown()
            }
        }
    }

    @Test
    fun queuedAutomaticMatchReReadsMovieAfterManualMatch() = runBlocking {
        withTimeout(15_000) {
            val movie = movie()
            val replies = Replies()
            val service = MetadataService(database, TmdbClient(replies.client) { "key" })
            val manual = async(Dispatchers.IO) { service.apply(2, movie.id, force = true) }
            replies.started.await()
            val staleAutomatic = async(start = CoroutineStart.UNDISPATCHED) { service.match(movie) }
            try {
                replies.release.countDown()
                manual.await()
                staleAutomatic.await()
                assertEquals(listOf(2), replies.requests)
                assertEquals(2, database.movies().get(movie.id)!!.tmdbId)
            } finally {
                replies.release.countDown()
            }
        }
    }
}
