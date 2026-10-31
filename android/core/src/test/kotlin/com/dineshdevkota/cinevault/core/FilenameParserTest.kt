package com.dineshdevkota.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {
    @Test fun releaseName() {
        assertEquals(ParsedMovieName("The Matrix", 1999), FilenameParser.parse("Movies/The.Matrix.1999.1080p.BluRay.x264-GRP.mkv"))
    }

    @Test fun parenthesizedYear() {
        assertEquals(ParsedMovieName("Inception", 2010), FilenameParser.parse("Inception (2010).mp4"))
    }

    @Test fun titleThatIsAYear() {
        assertEquals(ParsedMovieName("1917", 2019), FilenameParser.parse("1917 (2019) [2160p].mkv"))
        assertEquals(ParsedMovieName("2001 A Space Odyssey", 1968), FilenameParser.parse("2001.A.Space.Odyssey.1968.mkv"))
    }

    @Test fun folderFallback() {
        assertEquals(ParsedMovieName("Heat", 1995), FilenameParser.parse("Movies/Heat (1995)/movie.mkv"))
        assertEquals(ParsedMovieName("Heat", 1995), FilenameParser.parse("Movies/Heat (1995)/CD1.avi"))
    }

    @Test fun noYearStripsJunk() {
        assertEquals(ParsedMovieName("Some Movie", null), FilenameParser.parse("Some_Movie_720p_WEB-DL.mp4"))
    }

    @Test fun tmdbTag() {
        assertEquals(ParsedMovieName("Alien", 1979, 348), FilenameParser.parse("Alien (1979) {tmdb-348}.mkv"))
    }

    @Test fun bracketTags() {
        assertEquals(ParsedMovieName("Parasite", 2019), FilenameParser.parse("[YTS] Parasite.2019.mp4"))
    }

    @Test fun nonAsciiDigitsAreNotYears() {
        assertEquals(ParsedMovieName("Heat ١٩٩٥", null), FilenameParser.parse("Heat ١٩٩٥.mkv"))
    }
}

class VideoFileTest {
    private val big = 700L * 1024 * 1024

    @Test fun indexing() {
        assertTrue(VideoFile.shouldIndex("Movies/Heat (1995)/Heat.mkv", big))
        assertFalse(VideoFile.shouldIndex("Movies/Heat/Heat-sample.mkv", big))
        assertFalse(VideoFile.shouldIndex("Movies/Heat/Sample/x.mkv", big))
        assertFalse(VideoFile.shouldIndex("Movies/@eaDir/x.mp4", big))
        assertFalse(VideoFile.shouldIndex("Movies/._Heat.mp4", big))
        assertFalse(VideoFile.shouldIndex("Movies/Heat.mp4", 1024))
        assertFalse(VideoFile.shouldIndex("Movies/Heat.srt", big))
    }

    @Test fun playability() {
        assertTrue(VideoFile.isPlayable("a.MP4"))
        assertTrue(VideoFile.isPlayable("Movies/a.mkv"))
        assertFalse(VideoFile.isPlayable("a.wmv"))
        assertTrue(VideoFile.isVideo("a.iso"))
        assertFalse(VideoFile.isVideo(".mkv"))
    }
}
