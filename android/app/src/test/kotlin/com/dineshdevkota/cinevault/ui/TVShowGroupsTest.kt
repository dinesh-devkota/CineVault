package com.dineshdevkota.cinevault.ui

import com.dineshdevkota.cinevault.core.EpisodeParser
import com.dineshdevkota.cinevault.core.LibraryQuery
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.data.MovieEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TVShowGroupsTest {
    private fun episode(path: String): MovieEntity {
        val info = requireNotNull(EpisodeParser.parse(path))
        return MovieEntity(sourceId = "nas", filePath = path, fileName = path.substringAfterLast('/'),
            fileSize = 10, parsedTitle = info.seriesTitle, parsedYear = info.year,
            title = "Episode ${info.episode}", year = info.year)
    }

    private fun movie(path: String): MovieEntity = MovieEntity(
        sourceId = "nas", filePath = path, fileName = path.substringAfterLast('/'),
        fileSize = 10, parsedTitle = path.substringAfterLast('/'), parsedYear = null,
        title = path.substringAfterLast('/'), year = null,
    )

    private fun twentyFourEpisodes(): List<MovieEntity> = (1..8).flatMap { season ->
        (1..24).map { number ->
            val episodeNumber = number.toString().padStart(2, '0')
            val path = when (season) {
                1 -> "TV Shows/24/24 Season 1/24 Season 1/24 - S01xE$episodeNumber.mp4"
                in 2..4 -> "TV Shows/24/24 Season $season/24 - [${season}x$episodeNumber] - Day $season  8 00 A.M. - 9 00 A.M.mkv"
                in 5..7 -> "TV Shows/24/24 Season $season/24 - Day $season - [${season}x$episodeNumber].mkv"
                else -> "TV Shows/24/24 Season 8/24 - 8x$episodeNumber - Day 8  4 00 P.M. - 5 00 P.M..mkv"
            }
            episode(path)
        }
    }

    @Test fun allEightTwentyFourSeasonsGroupWithoutStoredTVMetadata() {
        val episodes = twentyFourEpisodes()
        assertTrue(episodes.all { it.seriesTitle == null && it.seriesTmdbId == null && it.seasonNumber == null })
        assertTrue(episodes.all { it.mediaKind == MediaKind.TV_SHOW })
        val show = groupTVShows(episodes).single()
        assertEquals("24", show.title)
        assertEquals((1..8).toList(), show.seasons)
        assertEquals(192, show.episodes.size)
        for (season in show.seasons) {
            assertEquals((1..24).toList(), show.episodes.filter { tvSeason(it) == season }.map(::tvEpisode).sorted())
        }
        val extras = listOf(movie("TV Shows/24/24 Season 1/24 EXTRAS/Behind the Scenes.mp4"),
            movie("TV Shows/24/24 Season 1/24 EXTRAS/Cast Interviews.mp4"))
        val items = collectionItems(episodes + extras)
        assertEquals(3, items.size)
        assertEquals(extras, items.filterIsInstance<CollectionItem.Movie>().map { it.movie })
        assertEquals(192, items.filterIsInstance<CollectionItem.Show>().single().show.episodes.size)
    }

    @Test fun collectionRowsGroupBeforeTheirLimitAndPreserveMixedOrdering() {
        val episodes = twentyFourEpisodes()
        val firstMovie = movie("Movies/First.mp4")
        val secondMovie = movie("Movies/Second.mp4")
        val moreMovies = (1..25).map { movie("Movies/More $it.mp4") }
        val items = collectionItems(listOf(firstMovie) + episodes.take(80) + secondMovie + episodes.drop(80) + moreMovies, limit = 20)
        assertEquals(20, items.size)
        assertEquals(firstMovie, (items[0] as CollectionItem.Movie).movie)
        assertEquals("24", (items[1] as CollectionItem.Show).show.title)
        assertEquals(192, (items[1] as CollectionItem.Show).show.episodes.size)
        assertEquals(secondMovie, (items[2] as CollectionItem.Movie).movie)
        assertTrue(collectionItems(episodes, limit = 0).isEmpty())
        assertTrue(collectionItems(episodes, limit = -1).isEmpty())
    }

    @Test fun collectionRowsKeepWatchlistAndUnwatchedEpisodeFilters() {
        val watched = episode("TV/Example/Season 1/Example.S01E01.mkv").copy(isWatched = true, isWatchlisted = true)
        val watchlisted = episode("TV/Example/Season 1/Example.S01E02.mkv").copy(isWatchlisted = true)
        val other = episode("TV/Example/Season 2/Example.S02E01.mkv")
        val movies = listOf(watched, watchlisted, other)
        val watchlist = collectionItems(LibraryQuery.watchlist(movies)).single() as CollectionItem.Show
        assertEquals(setOf(watched.id, watchlisted.id), watchlist.show.episodes.map { it.id }.toSet())
        val unwatched = collectionItems(movies.filter { !it.isWatched }).single() as CollectionItem.Show
        assertEquals(listOf(watchlisted, other), unwatched.show.episodes)
        assertEquals(listOf(1, 2), unwatched.show.seasons)
    }

    @Test fun localFilenamesGroupIntoShowsAndNumericallyOrderedSeasons() {
        val items = listOf(episode("TV/Example/Season 10/Example.S10E02.mkv"),
            episode("TV/Example/Season 2/Example.S02E01.mkv"),
            episode("TV/Example/Season 0/Example.S00E01.mkv"))
        val show = groupTVShows(items).single()
        assertEquals("Example", show.title)
        assertEquals(listOf(0, 2, 10), show.seasons)
        assertEquals(items, show.episodes)
    }

    @Test fun remakeYearsStaySeparateWithoutMetadata() {
        val items = listOf(episode("TV/Example (1990)/Season 1/Example.S01E01.mkv"),
            episode("TV/Example (2020)/Season 1/Example.S01E01.mkv"))
        assertEquals(2, groupTVShows(items).size)
    }

    @Test fun partialMetadataDoesNotSplitARecognizableLocalShow() {
        val fetched = episode("TV/Local Name/Season 1/Local.Name.S01E01.mkv")
            .copy(seriesTmdbId = 42, seriesTitle = "Canonical Title", year = 2020, posterPath = "/show.jpg")
        val pending = episode("TV/Local Name/Season 1/Local.Name.S01E02.mkv")
        val show = groupTVShows(listOf(pending, fetched)).single()
        assertEquals("tmdb:42", show.id)
        assertEquals("Canonical Title", show.title)
        assertEquals("/show.jpg", show.posterPath)
        assertEquals(listOf(pending, fetched), show.episodes)
    }

    @Test fun ambiguousMetadataDoesNotAbsorbUnmatchedEpisodes() {
        val first = episode("TV/Example/Season 1/Example.S01E01.mkv").copy(seriesTmdbId = 1)
        val second = episode("TV/Example/Season 1/Example.S01E02.mkv").copy(seriesTmdbId = 2)
        val pending = episode("TV/Example/Season 1/Example.S01E03.mkv")
        val shows = groupTVShows(listOf(first, second, pending))
        assertEquals(3, shows.size)
        assertTrue(shows.any { it.episodes == listOf(pending) })
    }

    @Test fun callerFiltersArePreservedIncludingHiddenView() {
        val hidden = episode("TV/Example/Season 1/Example.S01E02.mkv").copy(isHidden = true)
        assertEquals(listOf(hidden), groupTVShows(listOf(hidden)).single().episodes)
    }
}
