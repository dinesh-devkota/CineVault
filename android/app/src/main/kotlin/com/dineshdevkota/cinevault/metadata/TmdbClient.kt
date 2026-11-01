package com.dineshdevkota.cinevault.metadata

import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.core.TmdbMovieDetails
import com.dineshdevkota.cinevault.core.TmdbMovieSummary
import com.dineshdevkota.cinevault.core.TmdbSearchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resumeWithException

class MetadataException(message: String, val isAuthFailure: Boolean = false) : Exception(message)

/** TMDB search and details with the user's key. Calls are cancellable; rate limits are retried briefly. */
class TmdbClient(private val http: OkHttpClient, private val apiKey: () -> String) {
    /** Reads the sealed key; call off the main thread. */
    fun hasKey(): Boolean = apiKey().isNotEmpty()

    /** Searches with the year first, then without it (tvOS parity). */
    suspend fun search(title: String, year: Int?): List<TmdbMovieSummary> {
        val key = requireKey()
        val results = load(Tmdb.searchRequest(title, year, key), TmdbSearchResponse.serializer()).results
        if (results.isNotEmpty() || year == null) return results
        return load(Tmdb.searchRequest(title, null, key), TmdbSearchResponse.serializer()).results
    }

    suspend fun details(id: Int): TmdbMovieDetails =
        load(Tmdb.detailsRequest(id, requireKey()), TmdbMovieDetails.serializer())

    private fun requireKey(): String =
        apiKey().ifEmpty { throw MetadataException("Add a TMDB key in Settings first.", isAuthFailure = true) }

    private suspend fun <T> load(request: Request, strategy: DeserializationStrategy<T>): T {
        repeat(RATE_LIMIT_RETRIES) { attempt ->
            val reply = http.newCall(request).fetch()
            if (reply.code != HTTP_TOO_MANY_REQUESTS) return decode(reply, strategy)
            delay(reply.retryAfterMs ?: ((attempt + 1) * 1_000L))
        }
        return decode(http.newCall(request).fetch(), strategy)
    }

    private fun <T> decode(reply: Reply, strategy: DeserializationStrategy<T>): T = when (reply.code) {
        401, 403 -> throw MetadataException("TMDB rejected the API key. Check it in Settings.", isAuthFailure = true)
        !in 200..299 -> throw MetadataException("TMDB request failed (HTTP ${reply.code}).")
        else -> try {
            Tmdb.json.decodeFromString(strategy, reply.body)
        } catch (e: IllegalArgumentException) {
            throw MetadataException("TMDB returned an invalid response.")
        }
    }

    private class Reply(val code: Int, val body: String, val retryAfterMs: Long?)

    private suspend fun Call.fetch(): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(
                    runCatching {
                        response.use {
                            val retryAfter = it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)
                            Reply(it.code, it.body?.string().orEmpty(), retryAfter?.times(1_000))
                        }
                    },
                )
            }
        })
    }

    companion object {
        private const val RATE_LIMIT_RETRIES = 2
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_RETRY_AFTER_SECONDS = 10L

        /** User-facing message for metadata failures. */
        fun describe(error: Throwable): String = when {
            error is MetadataException -> error.message.orEmpty()
            generateSequence(error) { it.cause }.take(8)
                .any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException } ->
                "Could not reach TMDB. Check the TV's internet connection."
            else -> error.message ?: error.javaClass.simpleName
        }
    }
}
