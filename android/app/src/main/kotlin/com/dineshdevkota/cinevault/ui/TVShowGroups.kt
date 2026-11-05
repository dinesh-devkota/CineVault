package com.dineshdevkota.cinevault.ui

import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.core.EpisodeParser
import com.dineshdevkota.cinevault.core.MediaKind
import java.util.Locale

internal data class TVShowGroup(val id: String, val title: String, val episodes: List<MovieEntity>) {
    val posterPath: String? get() = episodes.firstNotNullOfOrNull { it.posterPath }
    val seasons: List<Int> get() = episodes.map(::tvSeason).distinct().sortedWith(compareBy<Int> { it < 0 }.thenBy { it })
}

internal sealed interface CollectionItem {
    val id: String
    data class Movie(val movie: MovieEntity) : CollectionItem {
        override val id: String get() = "movie:${movie.id}"
    }
    data class Show(val show: TVShowGroup) : CollectionItem {
        override val id: String get() = "show:${show.id}"
    }
}

/** Collapse a series at its first episode's position, after caller filtering and before the tile limit. */
internal fun collectionItems(movies: List<MovieEntity>, limit: Int = Int.MAX_VALUE): List<CollectionItem> {
    if (limit <= 0) return emptyList()
    val shows = groupTVShows(movies.filter { it.mediaKind == MediaKind.TV_SHOW })
    val showByEpisode = shows.flatMap { show -> show.episodes.map { it.id to show } }.toMap()
    val emitted = mutableSetOf<String>()
    return buildList<CollectionItem>(minOf(movies.size, limit)) {
        for (movie in movies) {
            val show = showByEpisode[movie.id]
            if (show == null) add(CollectionItem.Movie(movie))
            else if (emitted.add(show.id)) add(CollectionItem.Show(show))
            if (size == limit) break
        }
    }
}

internal fun tvSeason(movie: MovieEntity): Int = movie.seasonNumber ?: movie.episodeInfo?.season ?: -1
internal fun tvEpisode(movie: MovieEntity): Int = movie.episodeNumber ?: movie.episodeInfo?.episode ?: Int.MAX_VALUE
internal fun tvSeasonTitle(season: Int): String = when {
    season == 0 -> "Specials"
    season < 0 -> "Other episodes"
    else -> "Season $season"
}

/** Preserve caller ordering and filters, and keep remakes with distinct filename years apart. */
internal fun groupTVShows(episodes: List<MovieEntity>): List<TVShowGroup> {
    fun aliases(movie: MovieEntity): List<String> {
        val info = movie.episodeInfo
        val local = EpisodeParser.parse(movie.filePath)
        return listOf(info?.seriesTitle to info?.year, local?.seriesTitle to local?.year)
            .filter { !it.first.isNullOrBlank() }.map { (title, year) ->
            title!!.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") + "|${year ?: "unknown"}"
        }.distinct()
    }
    val prepared = episodes.map { it to aliases(it) }
    val known = mutableMapOf<String, MutableSet<Int>>()
    prepared.forEach { (movie, keys) -> movie.seriesTmdbId?.let { id ->
        keys.forEach { known.getOrPut(it) { mutableSetOf() }.add(id) }
    } }
    return prepared.groupBy { (movie, keys) ->
        val inferred = keys.flatMap { known[it].orEmpty() }.toSet().singleOrNull()
        val id = movie.seriesTmdbId ?: inferred
        if (id != null) "tmdb:$id" else keys.firstOrNull()?.let { "title:$it" } ?: "file:${movie.id}"
    }.map { (id, entries) ->
        val items = entries.map { it.first }
        val title = items.firstNotNullOfOrNull { it.seriesTitle?.takeIf(String::isNotBlank) }
            ?: items.firstNotNullOfOrNull { it.episodeInfo?.seriesTitle?.takeIf(String::isNotBlank) }
            ?: "Unidentified show"
        TVShowGroup(id, title, items)
    }
}
