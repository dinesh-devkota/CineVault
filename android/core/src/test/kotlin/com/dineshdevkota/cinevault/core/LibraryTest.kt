package com.dineshdevkota.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Entry(
    override val title: String,
    override val year: Int? = null,
    override val rating: Double? = null,
    override val genres: List<String> = emptyList(),
    override val addedAt: Long = 0,
    override val lastPlayedAt: Long? = null,
    override val isWatched: Boolean = false,
    override val isWatchlisted: Boolean = false,
    override val isHidden: Boolean = false,
    override val isMissing: Boolean = false,
    override val playbackPositionMs: Long = 0,
    override val fileName: String = "$title.mkv",
    override val sourceId: String? = null,
) : LibraryEntry {
    override val sortTitle: String get() = WatchRules.sortTitle(title)
}

class LibraryTest {
    private val heat = Entry("Heat", year = 1995, rating = 8.3, genres = listOf("Crime"), addedAt = 1)
    private val matrix = Entry("The Matrix", year = 1999, rating = 8.7, addedAt = 3, isWatched = true, lastPlayedAt = 10)
    private val alien = Entry("Alien", year = 1979, rating = 8.5, addedAt = 2, playbackPositionMs = 60_000, lastPlayedAt = 20)
    private val hidden = Entry("A Hidden Gem", isHidden = true)
    private val missing = Entry("Gone", isMissing = true)
    private val all = listOf(heat, matrix, alien, hidden, missing)

    @Test fun sortTitleDropsArticles() {
        assertEquals("matrix", WatchRules.sortTitle("The Matrix"))
        assertEquals("hidden gem", WatchRules.sortTitle("A Hidden Gem"))
        assertEquals("american", WatchRules.sortTitle("An American"))
        assertEquals("theory", WatchRules.sortTitle("Theory"))
    }

    @Test fun sorts() {
        val visible = listOf(heat, matrix, alien)
        assertEquals(listOf(alien, heat, matrix), LibrarySort.TITLE.sort(visible))
        assertEquals(listOf(matrix, alien, heat), LibrarySort.RECENTLY_ADDED.sort(visible))
        assertEquals(listOf(matrix, heat, alien), LibrarySort.YEAR.sort(visible))
        assertEquals(listOf(matrix, alien, heat), LibrarySort.RATING.sort(visible))
        assertEquals(listOf(alien, matrix, heat), LibrarySort.LAST_PLAYED.sort(visible))
    }

    @Test fun filtersExcludeMissing() {
        assertEquals(listOf(alien, heat, matrix), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.ALL, null))
        assertEquals(listOf(alien, heat), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.UNWATCHED, null))
        assertEquals(listOf(alien), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.IN_PROGRESS, null))
        assertEquals(listOf(matrix), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.WATCHED, null))
        assertEquals(listOf(hidden), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.HIDDEN, null))
        assertEquals(listOf(heat), LibraryQuery.visible(all, LibrarySort.TITLE, LibraryFilter.ALL, "Crime"))
    }

    @Test fun search() {
        assertTrue(LibraryQuery.searchMatches(matrix, "matrix"))
        assertTrue(LibraryQuery.searchMatches(heat, "crime"))
        assertFalse(LibraryQuery.searchMatches(heat, ""))
        assertFalse(LibraryQuery.searchMatches(heat, "  "))
        assertFalse(LibraryQuery.searchMatches(hidden, "hidden"))
        assertFalse(LibraryQuery.searchMatches(missing, "gone"))
    }

    @Test fun watchlistCombinesWithSourceDecadeGenreAndSelectedSort() {
        val first = heat.copy(isWatchlisted = true, sourceId = "living-room")
        val second = Entry(
            "The Arrival", year = 1996, genres = listOf("Crime"), addedAt = 2,
            isWatchlisted = true, sourceId = "living-room",
        )
        val items = listOf(
            first, second,
            first.copy(title = "Other NAS", sourceId = "office"),
            first.copy(title = "Other decade", year = 1989),
            first.copy(title = "Other genre", genres = listOf("Comedy")),
            first.copy(title = "Not saved", isWatchlisted = false),
            first.copy(title = "Unknown year", year = null),
            first.copy(title = "Unknown source", sourceId = null),
            first.copy(title = "Hidden", isHidden = true),
            first.copy(title = "Missing", isMissing = true),
        )

        assertEquals(
            listOf(second, first),
            LibraryQuery.visible(
                items, LibrarySort.RECENTLY_ADDED, LibraryFilter.WATCHLIST, "Crime",
                sourceId = "living-room", decade = 1990,
            ),
        )
        assertTrue(LibraryQuery.visible(items, LibrarySort.TITLE, LibraryFilter.ALL, null, sourceId = "deleted").isEmpty())
    }

    @Test fun decadeIncludesBothBoundariesAndExcludesUnknownYears() {
        val start = Entry("Start", year = 1990)
        val end = Entry("End", year = 1999)
        val items = listOf(start, end, Entry("Before", year = 1989), Entry("After", year = 2000), Entry("Unknown"))
        assertEquals(
            listOf(end, start),
            LibraryQuery.visible(items, LibrarySort.TITLE, LibraryFilter.ALL, null, decade = 1990),
        )
        assertEquals(5, LibraryQuery.visible(items, LibrarySort.TITLE, LibraryFilter.ALL, null).size)
    }

    @Test fun hiddenFilterStillHonorsSourceAndDecadeAndExcludesMissing() {
        val selected = Entry("Hidden", year = 1995, sourceId = "nas", isHidden = true, isWatchlisted = true)
        val items = listOf(
            selected, selected.copy(title = "Other source", sourceId = "other"),
            selected.copy(title = "Other decade", year = 2001), selected.copy(title = "Missing", isMissing = true),
        )
        assertEquals(
            listOf(selected),
            LibraryQuery.visible(items, LibrarySort.TITLE, LibraryFilter.HIDDEN, null, sourceId = "nas", decade = 1990),
        )
        assertTrue(LibraryQuery.watchlist(items).isEmpty())
        assertTrue(selected.isWatchlisted)
    }

    @Test fun watchlistKeepsWatchedAndInProgressMoviesAndSortsByTitle() {
        val watched = matrix.copy(isWatchlisted = true)
        val inProgress = alien.copy(isWatchlisted = true)
        val unplayed = heat.copy(isWatchlisted = true)
        val items = listOf(
            watched, inProgress, unplayed,
            Entry("Unlisted"), hidden.copy(isWatchlisted = true), missing.copy(isWatchlisted = true),
        )
        assertEquals(listOf(inProgress, unplayed, watched), LibraryQuery.watchlist(items))
        assertEquals(listOf(inProgress), LibraryQuery.continueWatching(items))
        assertTrue(watched.isWatched)
        assertEquals(60_000L, inProgress.playbackPositionMs)
    }

    @Test fun decadesAreUniqueNewestFirstAndExcludeHiddenMissingAndUnknownYears() {
        val items = listOf(
            Entry("New", year = 2024), Entry("Same decade", year = 2020),
            heat, matrix, Entry("Early cinema", year = 1888), Entry("Unknown"),
            hidden.copy(year = 1935), missing.copy(year = 1985),
        )
        assertEquals(listOf(2020, 1990, 1880), LibraryQuery.decades(items))
        assertEquals(emptyList<Int>(), LibraryQuery.decades(emptyList()))
    }

    @Test fun watchRules() {
        assertTrue(WatchRules.reachedEnd(93_000, 100_000))
        assertFalse(WatchRules.reachedEnd(92_000, 100_000))
        assertFalse(WatchRules.reachedEnd(10, 0))
        assertFalse(WatchRules.isInProgress(false, 30_000))
        assertTrue(WatchRules.isInProgress(false, 30_001))
        assertFalse(WatchRules.isInProgress(true, 60_000))
        assertEquals(listOf(alien), LibraryQuery.continueWatching(all))
        assertEquals(listOf("Crime"), LibraryQuery.genres(all))
    }
}
