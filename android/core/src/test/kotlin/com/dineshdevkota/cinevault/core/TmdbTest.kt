package com.dineshdevkota.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTest {
    @Test fun decodeSearch() {
        val json = """{"page":1,"results":[{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30","poster_path":"/p.jpg","vote_average":8.2,"poster_path_extra":null}]}"""
        val response = Tmdb.json.decodeFromString(TmdbSearchResponse.serializer(), json)
        assertEquals(603, response.results.first().id)
        assertEquals(1999, response.results.first().year)
        assertEquals("/p.jpg", response.results.first().posterPath)
    }

    @Test fun decodeDetailsWithNulls() {
        val json = """{"id":1,"title":"Heat","runtime":null,"genres":[{"id":1,"name":"Crime"}],"release_date":""}"""
        val details = Tmdb.json.decodeFromString(TmdbMovieDetails.serializer(), json)
        assertEquals(listOf("Crime"), details.genres?.map { it.name })
        assertNull(details.year)
    }

    @Test fun requestAuth() {
        val v3 = Tmdb.searchRequest("Heat", 1995, "abc123")
        assertTrue(v3.url.toString().contains("api_key=abc123"))
        assertTrue(v3.url.toString().contains("year=1995"))
        assertNull(v3.header("Authorization"))

        val token = "x".repeat(60)
        val v4 = Tmdb.detailsRequest(1, token)
        assertEquals("Bearer $token", v4.header("Authorization"))
        assertFalse(v4.url.toString().contains("api_key"))
    }

    @Test fun imageUrl() {
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", Tmdb.imageUrl("/p.jpg"))
        assertNull(Tmdb.imageUrl(""))
    }

    @Test fun matcherPrefersYear() {
        val candidates = listOf(
            TmdbMovieSummary(id = 1, title = "Dune", releaseDate = "2021-09-15"),
            TmdbMovieSummary(id = 2, title = "Dune", releaseDate = "1984-12-14"),
        )
        assertEquals(2, TitleMatcher.bestMatch("Dune", 1984, candidates)?.id)
        assertEquals(1, TitleMatcher.bestMatch("Dune", null, candidates)?.id)
    }

    @Test fun matcherRejectsUnrelated() {
        val candidates = listOf(TmdbMovieSummary(id = 1, title = "Completely Different", releaseDate = "2001-01-01"))
        assertNull(TitleMatcher.bestMatch("Heat", 1995, candidates))
    }

    @Test fun matcherRejectsTitlesWithoutSearchableCharacters() {
        val candidates = listOf(TmdbMovieSummary(id = 603, title = "The Matrix", releaseDate = "1999-03-30"))
        for (title in listOf("", "   ", "!!!")) {
            assertNull(TitleMatcher.bestMatch(title, 1999, candidates))
        }
    }

    @Test fun normalize() {
        assertEquals("amelie le fabuleux and co", TitleMatcher.normalize("Amélie: Le Fabuleux & Co."))
        assertEquals(1.0, TitleMatcher.similarity("Spider-Man", "Spider Man"), 0.0)
    }
}
