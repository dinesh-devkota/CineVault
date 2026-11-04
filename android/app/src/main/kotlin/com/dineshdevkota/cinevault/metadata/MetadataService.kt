package com.dineshdevkota.cinevault.metadata

import androidx.room.withTransaction
import com.dineshdevkota.cinevault.core.MovieEdits
import com.dineshdevkota.cinevault.core.MediaFile
import com.dineshdevkota.cinevault.core.EpisodeInfo
import com.dineshdevkota.cinevault.core.TitleMatcher
import com.dineshdevkota.cinevault.core.WatchRules
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.Converters
import com.dineshdevkota.cinevault.data.MovieEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class MetadataFetchSummary(val attempted: Int, val failed: Int, val firstError: String?) {
    val failureMessage: String?
        get() = if (failed == 0) null else "Metadata failed for $failed of $attempted movies. ${firstError ?: "Try again."}"
}

/** Matches library movies to TMDB and stores posters and details. Batches run one at a time. */
class MetadataService(private val database: CineVaultDatabase, val tmdb: TmdbClient) {
    private val batchLock = Mutex()
    // Keep automatic replies and explicit edits in order across their network suspensions.
    // A batch must re-read after acquiring this lock: an edit may have finished while it waited.
    private val mutationLock = Mutex()
    private val active = AtomicInteger(0)
    private val running = MutableStateFlow(false)
    private val statusFlow = MutableStateFlow<String?>(null)
    private val converters = Converters()

    val isRunning: StateFlow<Boolean> = running.asStateFlow()
    val status: StateFlow<String?> = statusFlow.asStateFlow()

    fun clearStatus() {
        statusFlow.value = null
    }

    /** Matches movies without metadata (runs after every scan). Null when no TMDB key is set. */
    suspend fun fetchMissing(): MetadataFetchSummary? = runBatch(refreshAll = false)

    /** Re-fetches every movie; existing matches and locked titles are kept. */
    suspend fun refreshAll(): MetadataFetchSummary? = runBatch(refreshAll = true)

    private suspend fun runBatch(refreshAll: Boolean): MetadataFetchSummary? {
        val hasKey = try {
            tmdb.hasKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusFlow.value = e.message ?: "Could not read the saved TMDB key. Enter it again in Settings."
            throw e
        }
        if (!hasKey) {
            if (refreshAll) statusFlow.value = "Add a TMDB key first."
            return null
        }
        active.incrementAndGet()
        running.value = true
        try {
            return batchLock.withLock {
                val movies = database.movies()
                if (refreshAll) { movies.clearMetadataFetched(); tmdb.clearTVCache() }
                val pending = movies.available().filter { needsMetadata(it) && !MediaFile.isAudio(it.filePath) }
                var failed = 0
                var firstError: String? = null
                for ((index, queued) in pending.withIndex()) {
                    statusFlow.value = "Fetching metadata ${index + 1} of ${pending.size}…"
                    // Re-read so a fix or reset made on the Edit screen during a long batch is not undone.
                    val movie = movies.get(queued.id)?.takeIf { needsMetadata(it) && !it.isMissing } ?: continue
                    try {
                        match(movie)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        if (firstError == null) firstError = TmdbClient.describe(e)
                        // A rejected key fails every remaining request too.
                        if (e is MetadataException && e.isAuthFailure) {
                            failed += pending.size - index - 1
                            break
                        }
                    }
                }
                MetadataFetchSummary(pending.size, failed, firstError).also { summary ->
                    statusFlow.value = summary.failureMessage ?: when {
                        pending.isEmpty() -> "All movies already have metadata."
                        refreshAll -> "Refreshed ${pending.size} movies."
                        else -> "Metadata updated for ${pending.size} movies."
                    }
                }
            }
        } finally {
            if (active.decrementAndGet() == 0) running.value = false
        }
    }

    /** Uses a {tmdb-123} tag or an earlier match when present, otherwise a title/year search. */
    suspend fun match(movie: MovieEntity) = mutationLock.withLock {
        val current = database.movies().get(movie.id)?.takeIf { needsMetadata(it) && !it.isMissing }
            ?: return@withLock
        matchLocked(current)
    }

    private suspend fun matchLocked(movie: MovieEntity) {
        if (MediaFile.isAudio(movie.filePath)) return
        movie.episodeInfo?.let { return matchTVLocked(movie, it) }
        movie.tmdbId?.let { return applyLocked(it, movie.id) }
        val title = if (movie.isLocked) movie.title else movie.parsedTitle
        val year = if (movie.isLocked) movie.year else movie.parsedYear
        val best = title.takeIf { it.isNotBlank() }?.let { TitleMatcher.bestMatch(it, year, tmdb.search(it, year)) }
        if (best != null) applyLocked(best.id, movie.id) else database.movies().markMetadataFetched(movie.id)
    }

    private fun needsMetadata(movie: MovieEntity): Boolean = !movie.metadataFetched ||
        (!movie.isLocked && movie.seriesTitle == null && movie.episodeInfo != null)

    private suspend fun matchTVLocked(movie: MovieEntity, info: EpisodeInfo) {
        val id = movie.seriesTmdbId ?: TitleMatcher.bestMatch(info.seriesTitle, info.year,
            tmdb.searchTV(info.seriesTitle, info.year))?.id
        if (id != null) {
            applyTVLocked(id, movie, info)
        } else {
            database.withTransaction {
                val current = database.movies().get(movie.id) ?: return@withTransaction
                if (sameMatch(current, movie)) {
                    database.movies().rememberEpisode(movie.id, info.seriesTitle, info.season, info.episode)
                }
            }
        }
    }

    private fun sameMatch(current: MovieEntity, original: MovieEntity): Boolean =
        current.title == original.title && current.year == original.year && current.isLocked == original.isLocked &&
            current.tmdbId == original.tmdbId && current.seriesTmdbId == original.seriesTmdbId &&
            current.episodeInfo == original.episodeInfo && !current.isMissing

    suspend fun applyTV(seriesId: Int, movieId: String, force: Boolean = false) = mutationLock.withLock {
        val movie = database.movies().get(movieId) ?: return@withLock
        val info = movie.episodeInfo ?: return@withLock
        applyTVLocked(seriesId, movie, info, force)
    }

    private suspend fun applyTVLocked(seriesId: Int, movie: MovieEntity, info: EpisodeInfo, force: Boolean = false) {
        val details = tmdb.tvDetails(seriesId)
        val season = tmdb.tvSeason(seriesId, info.season)
        val episode = season.episodes.firstOrNull { it.seasonNumber == info.season && it.episodeNumber == info.episode }
        database.withTransaction {
            val movies = database.movies()
            val current = movies.get(movie.id) ?: return@withTransaction
            if (!sameMatch(current, movie)) return@withTransaction
            movies.applyTVMetadata(
                id = movie.id, seriesTitle = details.name.ifBlank { info.seriesTitle }, seriesTmdbId = details.id,
                seasonNumber = info.season, episodeNumber = info.episode, seasonPosterPath = season.posterPath,
                overview = episode?.overview?.takeIf { it.isNotBlank() } ?: details.overview,
                runtimeMinutes = episode?.runtime ?: details.episodeRunTime.firstOrNull { it > 0 },
                rating = episode?.voteAverage ?: details.voteAverage, posterPath = details.posterPath,
                backdropPath = episode?.stillPath ?: details.backdropPath, genresJson = converters.fromList(details.genres.orEmpty().map { it.name }),
            )
            val title = episode?.name?.takeIf { it.isNotBlank() } ?: movie.title
            movies.applyMatchedTitle(movie.id, title, WatchRules.sortTitle(title), details.year ?: info.year, force)
            if (force) movies.lock(movie.id)
        }
    }

    /** Stores TMDB details; [force] (manual re-match) also replaces a title the user locked. */
    suspend fun apply(tmdbId: Int, movieId: String, force: Boolean = false) = mutationLock.withLock {
        applyLocked(tmdbId, movieId, force)
    }

    /** Reset and optional fresh match are one user operation relative to in-flight automatic matches. */
    suspend fun resetToFile(movieId: String) = mutationLock.withLock {
        val movies = database.movies()
        val movie = movies.get(movieId) ?: return@withLock
        val fromFile = MovieEdits.fromFile(movie.filePath)
        movies.resetToFile(
            movieId, fromFile.title, WatchRules.sortTitle(fromFile.title), fromFile.year,
            fromFile.tmdbId, converters.fromList(emptyList()),
        )
        if (!tmdb.hasKey()) return@withLock
        val reset = movies.get(movieId) ?: return@withLock
        try {
            matchLocked(reset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MetadataException("Reset to the file name; matching will be retried after the next scan. ${TmdbClient.describe(e)}")
        }
    }

    private suspend fun applyLocked(tmdbId: Int, movieId: String, force: Boolean = false) {
        val details = tmdb.details(tmdbId)
        val movies = database.movies()
        database.withTransaction {
            movies.applyMetadata(
                id = movieId,
                tmdbId = details.id,
                overview = details.overview,
                tagline = details.tagline,
                runtimeMinutes = details.runtime,
                rating = details.voteAverage,
                posterPath = details.posterPath,
                backdropPath = details.backdropPath,
                genresJson = converters.fromList(details.genres.orEmpty().map { it.name }.filter { it.isNotBlank() }),
            )
            if (details.title.isNotBlank()) {
                movies.applyMatchedTitle(movieId, details.title, WatchRules.sortTitle(details.title), details.year, force)
            }
            if (force) movies.lock(movieId)
        }
    }
}
