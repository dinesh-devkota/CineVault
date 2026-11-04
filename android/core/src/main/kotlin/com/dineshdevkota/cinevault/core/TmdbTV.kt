package com.dineshdevkota.cinevault.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbTVSummary(
    val id: Int,
    val name: String = "",
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    fun matchingCandidate() = TmdbMovieSummary(id, name, originalName, firstAirDate, posterPath = posterPath)
}

@Serializable
data class TmdbTVSearchResponse(val results: List<TmdbTVSummary> = emptyList())

@Serializable
data class TmdbTVDetails(
    val id: Int,
    val name: String = "",
    val overview: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<TmdbGenre>? = null,
) {
    val year: Int? get() = firstAirDate?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbTVEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    val overview: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
data class TmdbTVSeason(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbTVEpisode> = emptyList(),
)
