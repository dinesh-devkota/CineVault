package com.dineshdevkota.cinevault.metadata

import androidx.room.withTransaction
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
        if (!tmdb.hasKey()) {
            if (refreshAll) statusFlow.value = "Add a TMDB key first."
            return null
        }
        active.incrementAndGet()
        running.value = true
        try {
            return batchLock.withLock {
                val movies = database.movies()
                if (refreshAll) movies.clearMetadataFetched()
                val pending = movies.needingMetadata()
                var failed = 0
                var firstError: String? = null
                for ((index, movie) in pending.withIndex()) {
                    statusFlow.value = "Fetching metadata ${index + 1} of ${pending.size}…"
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
    suspend fun match(movie: MovieEntity) {
        movie.tmdbId?.let { return apply(it, movie.id) }
        val title = if (movie.isLocked) movie.title else movie.parsedTitle
        val year = if (movie.isLocked) movie.year else movie.parsedYear
        val best = title.takeIf { it.isNotBlank() }?.let { TitleMatcher.bestMatch(it, year, tmdb.search(it, year)) }
        if (best != null) apply(best.id, movie.id) else database.movies().markMetadataFetched(movie.id)
    }

    /** Stores TMDB details; [force] (manual re-match) also replaces a title the user locked. */
    suspend fun apply(tmdbId: Int, movieId: String, force: Boolean = false) {
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
        }
    }
}
