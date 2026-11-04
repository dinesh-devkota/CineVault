package com.dineshdevkota.cinevault.ui

import com.dineshdevkota.cinevault.core.EpisodeParser
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
