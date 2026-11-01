package com.dineshdevkota.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieEditsTest {
    @Test
    fun trimsTitleAndAllowsAnEmptyYear() {
        assertEquals(TitleEdit.Valid("Heat", null), MovieEdits.titleEdit("  Heat ", " "))
        assertEquals(TitleEdit.Valid("Heat", 1995), MovieEdits.titleEdit("Heat", "1995"))
    }

    @Test
    fun rejectsBlankTitlesAndImplausibleYears() {
        assertEquals(TitleEdit.Invalid("Enter a title."), MovieEdits.titleEdit("   ", "1995"))
        val badYear = TitleEdit.Invalid("Year must be from 1870 to 2100, or left empty.")
        for (year in listOf("95", "19x5", "2101", "-1995")) {
            assertEquals(year, badYear, MovieEdits.titleEdit("Heat", year))
        }
    }

    @Test
    fun resetsToWhatAScanWouldShow() {
        assertEquals(ParsedMovieName("The Matrix", 1999), MovieEdits.fromFile("Movies/The.Matrix.1999.1080p.BluRay.x264-GRP.mkv"))
        assertEquals(ParsedMovieName("Heat", 1995), MovieEdits.fromFile("Movies/Heat (1995)/movie.mkv"))
        assertEquals(ParsedMovieName("Alien", 1979, 348), MovieEdits.fromFile("Alien (1979) {tmdb-348}.mkv"))
    }
}
