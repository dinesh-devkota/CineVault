package com.dineshdevkota.cinevault.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.Normalizer
import kotlin.math.abs

@Serializable
data class TmdbMovieSummary(
    val id: Int,
    val title: String = "",
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
) {
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbSearchResponse(val results: List<TmdbMovieSummary> = emptyList())

@Serializable
data class TmdbGenre(val name: String = "")

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String = "",
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenre>? = null,
) {
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

object Tmdb {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private const val BASE_URL = "https://api.themoviedb.org/3"

    /** v4 read access tokens are long JWTs; v3 API keys are 32 hex characters. */
    fun isBearerToken(apiKey: String): Boolean = apiKey.length > 40

    fun request(path: String, query: Map<String, String> = emptyMap(), apiKey: String): Request {
        val url = (BASE_URL + path).toHttpUrl().newBuilder()
        query.toSortedMap().forEach { (name, value) -> url.addQueryParameter(name, value) }
        val bearer = isBearerToken(apiKey)
        if (!bearer) url.addQueryParameter("api_key", apiKey)
        val builder = Request.Builder().url(url.build()).header("Accept", "application/json")
        if (bearer) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    fun searchRequest(title: String, year: Int?, apiKey: String): Request {
        val query = mutableMapOf("query" to title, "include_adult" to "false")
        if (year != null) query["year"] = year.toString()
        return request("/search/movie", query, apiKey)
    }

    fun detailsRequest(id: Int, apiKey: String): Request = request("/movie/$id", apiKey = apiKey)

    fun tvSearchRequest(title: String, year: Int?, apiKey: String): Request {
        val query = mutableMapOf("query" to title, "include_adult" to "false")
        if (year != null) query["first_air_date_year"] = year.toString()
        return request("/search/tv", query, apiKey)
    }

    fun tvDetailsRequest(id: Int, apiKey: String): Request = request("/tv/$id", apiKey = apiKey)
    fun tvSeasonRequest(id: Int, season: Int, apiKey: String): Request = request("/tv/$id/season/$season", apiKey = apiKey)

    fun imageUrl(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}

/** Picks the TMDB search result that best matches a parsed file name. */
object TitleMatcher {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(title: String): String {
        val folded = combiningMarks.replace(Normalizer.normalize(title, Normalizer.Form.NFD), "")
            .lowercase()
            .replace("&", " and ")
        val cleaned = buildString { folded.forEach { append(if (it.isLetterOrDigit()) it else ' ') } }
        return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** 0..1, based on Levenshtein distance of normalized titles. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return if (x == y) 1.0 else 0.0
        var previous = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            val current = IntArray(y.length + 1)
            current[0] = i
            for (j in 1..y.length) {
                val substitution = previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return 1.0 - previous[y.length].toDouble() / maxOf(x.length, y.length)
    }

    fun score(candidate: TmdbMovieSummary, title: String, year: Int?): Double {
        var score = maxOf(similarity(candidate.title, title), similarity(candidate.originalTitle ?: "", title))
        val candidateYear = candidate.year
        if (year != null && candidateYear != null) {
            score += when (abs(year - candidateYear)) {
                0 -> 0.3
                1 -> 0.15
                else -> -0.3
            }
        }
        return score
    }

    fun bestMatch(
        title: String,
        year: Int?,
        candidates: List<TmdbMovieSummary>,
        threshold: Double = 0.5,
    ): TmdbMovieSummary? {
        if (normalize(title).isEmpty()) return null
        // Earlier results win ties because TMDB orders by popularity.
        var best: TmdbMovieSummary? = null
        var bestScore = -1.0
        for (candidate in candidates) {
            val value = score(candidate, title, year)
            if (value > bestScore) {
                best = candidate
                bestScore = value
            }
        }
        return best?.takeIf { bestScore >= threshold }
    }
}
