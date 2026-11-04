package com.dineshdevkota.cinevault.metadata

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

class TmdbClientTest {
    private class Reply(val code: Int, val body: String = "{}", val retryAfter: String? = null)

    /** Answers requests in order without touching the network. */
    private class FakeTmdb(vararg replies: Reply) : Interceptor {
        private val queue = replies.toList()
        val urls = CopyOnWriteArrayList<HttpUrl>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            urls += request.url
            val reply = queue[urls.size - 1]
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("fake")
                .apply { reply.retryAfter?.let { header("Retry-After", it) } }
                .body(reply.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(fake: FakeTmdb, key: String = "0123456789abcdef0123456789abcdef") =
        TmdbClient(OkHttpClient.Builder().addInterceptor(fake).build()) { key }

    private fun failure(block: suspend () -> Unit): MetadataException = runBlocking {
        try {
            block()
            fail("Expected MetadataException")
            throw AssertionError()
        } catch (e: MetadataException) {
            e
        }
    }

    @Test
    fun searchRetriesWithoutTheYearWhenNothingMatches() = runBlocking {
        val fake = FakeTmdb(
            Reply(200, """{"results":[]}"""),
            Reply(200, """{"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-30","extra":1}]}"""),
        )
        val results = client(fake).search("The Matrix", 1998)
        assertEquals(listOf(603), results.map { it.id })
        assertEquals(1999, results.single().year)
        assertEquals("1998", fake.urls[0].queryParameter("year"))
        assertNull(fake.urls[1].queryParameter("year"))
        assertEquals("The Matrix", fake.urls[1].queryParameter("query"))
    }

    @Test
    fun searchWithoutAYearMakesOneRequest() = runBlocking {
        val fake = FakeTmdb(Reply(200, """{"results":[]}"""))
        assertTrue(client(fake).search("Unknown", null).isEmpty())
        assertEquals(1, fake.urls.size)
    }

    @Test
    fun retriesRateLimitedRequests() = runBlocking {
        val fake = FakeTmdb(
            Reply(429, retryAfter = "0"),
            Reply(200, """{"id":603,"title":"The Matrix","runtime":136,"genres":[{"name":"Action"}]}"""),
        )
        val details = client(fake).details(603)
        assertEquals(136, details.runtime)
        assertEquals(listOf("Action"), details.genres?.map { it.name })
        assertEquals(2, fake.urls.size)
        assertEquals("/3/movie/603", fake.urls[1].encodedPath)
    }

    @Test
    fun rejectedKeyIsAnAuthFailure() {
        val error = failure { client(FakeTmdb(Reply(401))).details(1) }
        assertTrue(error.isAuthFailure)
        assertEquals("TMDB rejected the API key. Check it in Settings.", error.message)
    }

    @Test
    fun reportsServerErrorsAndInvalidResponses() {
        assertEquals("TMDB request failed (HTTP 500).", failure { client(FakeTmdb(Reply(500))).details(1) }.message)
        assertEquals("TMDB returned an invalid response.", failure { client(FakeTmdb(Reply(200, "<html>"))).details(1) }.message)
    }

    @Test
    fun missingKeyFailsWithoutCallingTmdb() {
        val fake = FakeTmdb()
        val error = failure { client(fake, key = "").search("Alien", 1979) }
        assertEquals("Add a TMDB key in Settings first.", error.message)
        assertEquals(0, fake.urls.size)
    }

    @Test
    fun describesNetworkFailures() {
        assertEquals(
            "Could not reach TMDB. Check the TV's internet connection.",
            TmdbClient.describe(IOException("failed", UnknownHostException("api.themoviedb.org"))),
        )
    }

    @Test
    fun tvSearchUsesFirstAirYearAndCachesSeriesAndSeasonRequests() = runBlocking {
        val fake = FakeTmdb(
            Reply(200, """{"results":[]}"""),
            Reply(200, """{"results":[{"id":42,"name":"Example","first_air_date":"2022-01-01","poster_path":"/show.jpg"}]}"""),
            Reply(200, """{"id":42,"name":"Example","first_air_date":"2022-01-01","episode_run_time":[45],"genres":[{"name":"Drama"}]}"""),
            Reply(200, """{"season_number":0,"poster_path":"/season.jpg","episodes":[{"season_number":0,"episode_number":1,"name":"Special","runtime":30}]}"""),
        )
        val client = client(fake)
        assertEquals(2022, client.searchTV("Example", 2021).single().year)
        assertEquals(42, client.searchTV("Example", 2021).single().id)
        assertEquals("Example", client.tvDetails(42).name)
        assertEquals(2022, client.tvDetails(42).year)
        assertEquals("Special", client.tvSeason(42, 0).episodes.single().name)
        assertEquals("/season.jpg", client.tvSeason(42, 0).posterPath)
        assertEquals(4, fake.urls.size)
        assertEquals("/3/search/tv", fake.urls[0].encodedPath)
        assertEquals("2021", fake.urls[0].queryParameter("first_air_date_year"))
        assertNull(fake.urls[1].queryParameter("first_air_date_year"))
        assertEquals("/3/tv/42", fake.urls[2].encodedPath)
        assertEquals("/3/tv/42/season/0", fake.urls[3].encodedPath)
    }

    @Test
    fun clearingTVCachesFetchesFreshDetails() = runBlocking {
        val fake = FakeTmdb(
            Reply(200, """{"id":42,"name":"Before"}"""),
            Reply(200, """{"id":42,"name":"After"}"""),
        )
        val client = client(fake)
        assertEquals("Before", client.tvDetails(42).name)
        client.clearTVCache()
        assertEquals("After", client.tvDetails(42).name)
        assertEquals(2, fake.urls.size)
    }
}
