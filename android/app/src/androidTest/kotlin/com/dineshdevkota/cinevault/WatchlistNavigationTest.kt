package com.dineshdevkota.cinevault

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dineshdevkota.cinevault.core.LibraryFilter
import com.dineshdevkota.cinevault.core.LibrarySort
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.ui.MovieDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses local rows and a refused loopback port; no NAS or metadata account is needed. */
@RunWith(AndroidJUnit4::class)
class WatchlistNavigationTest {
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val sources = mutableListOf<SourceEntity>()

    @Before
    fun prepareFreshLibrary() = runBlocking {
        awaitScannerIdle()
        container.playbackProgress.await { }
        withContext(Dispatchers.IO) { container.database.clearAllTables() }
        resetPreferences()
        container.settings.tmdbApiKey = ""
        container.scanner.clearStatus()
    }

    @After
    fun removeFixtureLibrary() = runBlocking {
        // Every launched MainActivity is awaited before its scenario closes, including relaunches.
        awaitScannerIdle()
        container.playbackProgress.await { }
        withContext(Dispatchers.IO) { container.database.clearAllTables() }
        sources.forEach { container.smb.reset(it.id) }
        resetPreferences()
        container.scanner.clearStatus()
    }

    @Test
    fun detailWatchlistTogglePersistsAndUpdatesTheHomeRail() = runBlocking {
        val source = source("Watchlist A")
        val movie = movie(source, "Saved for Later", 1994)
        launchDetail(movie).use { detail ->
            visible("Add to Watchlist")
            // Detail starts on Play; the first library action is directly below it.
            device.pressDPadDown()
            device.pressDPadCenter()
            visible("Remove from Watchlist")
            assertTrue(container.database.movies().get(movie.id)!!.isWatchlisted)
            detail.recreate()
            visible("Remove from Watchlist")
        }
        launchDetail(movie).use { visible("Remove from Watchlist") }
        launchHomeAfterScan().use { visible("Watchlist") }

        launchDetail(movie).use {
            visible("Remove from Watchlist")
            device.pressDPadDown()
            device.pressDPadCenter()
            visible("Add to Watchlist")
            assertFalse(container.database.movies().get(movie.id)!!.isWatchlisted)
        }
        launchHomeAfterScan().use {
            visible("FEATURED") // Wait for the movie rows, rather than the initial empty state.
            assertFalse("An empty Watchlist rail should disappear", device.hasObject(By.text("Watchlist")))
        }
    }

    @Test
    fun watchlistSourceAndDecadeFiltersPersistAcrossRecreationAndRelaunch() = runBlocking {
        val first = source("Watchlist A")
        val second = source("Watchlist B")
        movie(first, "Alpha Nineties", 1994, watchlisted = true)
        movie(first, "Alpha Recent", 2021, watchlisted = true)
        movie(second, "Beta Nineties", 1996, watchlisted = true)
        movie(first, "Alpha Other", 1995)

        launchHomeAfterScan().use { main ->
            device.pressDPadRight()
            visible("4 titles")
            device.pressDPadDown() // Enter the controls below the Movies tab.
            repeat(2) { device.pressDPadLeft() } // Reach the first control regardless of tab alignment.
            device.pressDPadRight() // Show.
            listOf("Unwatched", "In Progress", "Watched", "Hidden", "Watchlist").forEach { filter ->
                device.pressDPadCenter()
                visible("Show: $filter")
            }
            visible("3 titles")
            device.pressDPadRight() // Genre.
            device.pressDPadCenter()
            visible("Genre: Drama")
            device.pressDPadLeft() // Show.
            device.pressDPadLeft() // Sort.
            device.pressDPadDown() // Source, directly below Sort.
            device.pressDPadCenter()
            visible("Source: Watchlist A")
            visible("2 titles")
            device.pressDPadRight() // Decade.
            device.pressDPadCenter()
            visible("Decade: 2020s")
            visible("Alpha Recent")
            device.pressDPadCenter()
            assertNinetiesSelection()
            main.recreate()
            assertNinetiesSelection()
        }
        launchHomeAfterScan().use {
            device.pressDPadRight()
            assertNinetiesSelection()
            container.database.sources().delete(first.id)
            visible("Source: All sources")
            visible("Beta Nineties")
            visible("1 titles")
            assertFalse(device.hasObject(By.text("Alpha Nineties")))
            withTimeout(10_000) {
                while (container.settings.librarySourceId != null) delay(50)
            }
            assertNull("The deleted source must not remain a saved filter", container.settings.librarySourceId)
        }
    }

    private fun assertNinetiesSelection() {
        visible("Show: Watchlist")
        visible("Genre: Drama")
        visible("Source: Watchlist A")
        visible("Decade: 1990s")
        visible("1 titles")
        visible("Alpha Nineties")
        assertFalse(device.hasObject(By.text("Alpha Recent")))
        assertFalse(device.hasObject(By.text("Beta Nineties")))
        assertFalse(device.hasObject(By.text("Alpha Other")))
    }

    private fun launchDetail(movie: MovieEntity): ActivityScenario<MovieDetailActivity> = ActivityScenario.launch(
        Intent(application, MovieDetailActivity::class.java).putExtra("movie_id", movie.id),
    )

    private suspend fun launchHomeAfterScan(): ActivityScenario<MainActivity> {
        container.scanner.clearStatus()
        val lastSource = container.database.sources().all().last()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            withTimeout(15_000) {
                combine(container.scanner.status, container.scanner.isScanning) { status, scanning ->
                    !scanning && status?.startsWith("Scan failed for ${lastSource.name}:") == true
                }.first { it }
            }
            visible("Welcome to CineVault")
            return scenario
        } catch (error: Throwable) {
            scenario.close()
            throw error
        }
    }

    private suspend fun awaitScannerIdle() {
        withTimeout(15_000) { container.scanner.isScanning.first { !it } }
    }

    private suspend fun source(name: String): SourceEntity = SourceEntity(
        name = name, host = "127.0.0.1", port = 1, share = "synthetic",
    ).also {
        sources += it
        container.database.sources().upsert(it)
    }

    private suspend fun movie(source: SourceEntity, title: String, year: Int, watchlisted: Boolean = false): MovieEntity = MovieEntity(
        sourceId = source.id, filePath = "$title.mp4", fileName = "$title.mp4", fileSize = 60L * 1024 * 1024,
        parsedTitle = title, parsedYear = year, title = title, year = year,
        metadataFetched = true, isWatchlisted = watchlisted, genres = listOf("Drama"),
    ).also { container.database.movies().insert(it) }

    private fun resetPreferences() {
        container.settings.librarySort = LibrarySort.TITLE
        container.settings.libraryFilter = LibraryFilter.ALL
        container.settings.libraryGenre = null
        container.settings.librarySourceId = null
        container.settings.libraryDecade = null
    }

    private fun visible(text: String) {
        val found = device.wait(Until.hasObject(By.text(text)), 10_000)
        val focus = if (found) "" else runCatching {
            device.findObjects(By.focused(true)).joinToString { node ->
                "text=${node.text}, description=${node.contentDescription}, children=${node.children.map { it.text }}"
            }
        }.getOrDefault("unavailable")
        assertTrue("Expected TV screen text: $text; focused=$focus", found)
        device.waitForIdle()
    }
}
