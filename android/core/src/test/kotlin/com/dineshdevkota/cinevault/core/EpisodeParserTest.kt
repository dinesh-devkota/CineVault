package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class EpisodeParserTest {
    @Test fun prefersSeriesFolderAndPreservesItsYear() {
        assertEquals(EpisodeInfo("The Example", 1, 2, 2022), EpisodeParser.parse(
            "TV Shows/The Example (2022)/Season 01/Ambiguous.S01E02.1080p.mkv"))
    }
    @Test fun parsesFilenameMarkersAndSpecials() {
        assertEquals(EpisodeInfo("Show Name", 1, 2), EpisodeParser.parse("Show.Name.S01E02.Title.mkv"))
        assertEquals(EpisodeInfo("Show Name", 1, 2), EpisodeParser.parse("Show Name 1x02 Title.mp4"))
        assertEquals(EpisodeInfo("Show Name", 0, 3), EpisodeParser.parse("Show.Name.S00E03.Special.mkv"))
        assertEquals(EpisodeInfo("Show Name", 999, 999), EpisodeParser.parse("Show.Name.S999E999.mkv"))
    }
    @Test fun supportsNumberedFilesOnlyWithinSeasonFolders() {
        assertEquals(EpisodeInfo("Show Name", 2, 2), EpisodeParser.parse(
            """TV Shows\Show Name\Season02\02 - Episode title.mkv"""))
        assertNull(EpisodeParser.parse("Movies/02 - Episode title.mkv"))
        assertNull(EpisodeParser.parse("TV Shows/Season02/02 - Episode title.mkv"))
    }
    @Test fun requiresMeaningfulSeriesTitle() {
        assertEquals(EpisodeInfo("Show Name", 1, 2), EpisodeParser.parse("TV Shows/Show Name/S01E02.mkv"))
        for (path in listOf("TV Shows/S01E02.mkv", "Movies/S01E02.mkv", "Documentaries/S01E02.mkv", "S01E02.mkv")) {
            assertNull(path, EpisodeParser.parse(path))
        }
    }
    @Test fun rejectsYearsResolutionsAndOutOfRangeNumbers() {
        for (path in listOf("Movies/Film.2024.1080p.mkv", "Show/Season02/2024 - Film.mkv", "Show/Season02/1080p.mkv",
            "Show.1920x1080.mkv", "Show.S1000E01.mkv", "Show.S01E1000.mkv", "Show/Season1000/02 - Title.mkv")) {
            assertNull(path, EpisodeParser.parse(path))
        }
    }
    @Test fun televisionPrecedesGenresAndMatchedGenresPrecedeFolders() {
        assertEquals(MediaKind.TV_SHOW, MediaFile.kind("Documentaries/Show.S01E02.mp4", listOf("Documentary"), true))
        assertEquals(MediaKind.DOCUMENTARY, MediaFile.kind("Movies/Film.mp4", listOf("Documentary"), true))
        assertEquals(MediaKind.MUSIC, MediaFile.kind("Movies/Concert.mp4", listOf("Music"), true))
        assertEquals(MediaKind.MOVIE, MediaFile.kind("Documentaries/Film.mp4", listOf("Drama"), true))
        assertEquals(MediaKind.DOCUMENTARY, MediaFile.kind("Documentaries/Film.mp4"))
        assertEquals(MediaKind.MUSIC, MediaFile.kind("Music/Show.S01E02.mp3"))
    }
}
