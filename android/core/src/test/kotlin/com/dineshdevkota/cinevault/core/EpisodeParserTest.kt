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

    @Test fun groupsBracketedEpisodesAcrossPrefixedSeasonFolders() {
        for (season in listOf(1, 5, 9)) {
            val path = "TV Shows/24/24 Season $season/24 - Day $season - [${season}x23].mkv"
            assertEquals(path, EpisodeInfo("24", season, 23), EpisodeParser.parse(path))
        }
        assertEquals(EpisodeInfo("Example", 3, 4, 2022), EpisodeParser.parse(
            "TV Shows/Example (2022)/Example Season 3/Unrelated - [3x04].mkv"))
    }

    @Test fun acceptsBracketParenAndSpacedMarkersWithoutChangingTitle() {
        for (filename in listOf("Show.Name.[S01E02].mkv", "Show Name (1x02).mp4",
            "Show Name [S01 E02].mkv", "Show Name (s01 e02).mp4")) {
            assertEquals(filename, EpisodeInfo("Show Name", 1, 2), EpisodeParser.parse(filename))
        }
    }

    @Test fun seriesFolderWinsOverSeasonSpecificFilenameWithoutSeasonFolder() {
        assertEquals(EpisodeInfo("Example", 3, 4), EpisodeParser.parse(
            "TV Shows/Example/Day Three/Other.Title.[3x04].mkv"))
        assertEquals(EpisodeInfo("Example", 2, 3), EpisodeParser.parse(
            """TV Shows\Example\Example Season 2\03 - Episode title.mp4"""))
        assertEquals(EpisodeInfo("Example", 2, 3), EpisodeParser.parse(
            "Example/Example Season 2/[2x03].mp4"))
    }

    @Test fun bracketedMovieResolutionsAndUnmarkedFilesRemainMovies() {
        for (path in listOf("Movies/Film.[1920x1080].mkv", "Movies/Film.(640x480).mp4",
            "Movies/Film (2024) [1080p].mkv", "TV Shows/Example/Example Season 2/Trailer.mp4",
            "TV Shows/Example/Example Season 2/2024 - Movie.mp4")) {
            assertNull(path, EpisodeParser.parse(path))
        }
    }

    @Test fun groupsAllEightObservedSeasonLayoutsAndLeavesUnnumberedExtrasOut() {
        for (season in 1..8) for (episode in 1..24) {
            val number = episode.toString().padStart(2, '0')
            val relative = when (season) {
                1 -> "24 Season 1/24 - S01xE$number.mp4"
                2, 3, 4 -> "24 - [${season}x$number] - Day $season  8 00 A.M. - 9 00 A.M.mkv"
                5, 6, 7 -> "24 - Day $season - [${season}x$number].mkv"
                else -> "24 - 8x$number - Day 8  4 00 P.M. - 5 00 P.M..mkv"
            }
            val path = "TV Shows/24/24 Season $season/$relative"
            assertEquals(path, EpisodeInfo("24", season, episode), EpisodeParser.parse(path))
        }
        assertEquals(EpisodeInfo("Example", 1, 2, 2022), EpisodeParser.parse(
            "Example (2022)/Example Season 1/Example Season 1/Other - S01xE02.mp4"))
        for (name in listOf("Behind the Scenes", "Cast Interviews")) {
            assertNull(EpisodeParser.parse("TV Shows/24/24 Season 1/24 EXTRAS/$name.mp4"))
        }
    }

    @Test fun explicitSeasonParentKeepsShowsUnderCategoryFoldersSeparate() {
        for (title in listOf("Show A", "Show B")) {
            assertEquals(EpisodeInfo(title, 1, 1), EpisodeParser.parse(
                "TV Shows/Completed/$title/Season 1/${title.replace(' ', '.')}.S01E01.mkv"))
            assertEquals(EpisodeInfo(title, 1, 1), EpisodeParser.parse(
                "TV Shows/Completed/$title/$title Season 1/$title Season 1/Other.S01xE01.mp4"))
        }
    }
}
