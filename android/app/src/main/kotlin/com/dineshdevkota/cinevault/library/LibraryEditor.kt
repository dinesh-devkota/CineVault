package com.dineshdevkota.cinevault.library

import com.dineshdevkota.cinevault.core.MovieEdits
import com.dineshdevkota.cinevault.core.TitleEdit
import com.dineshdevkota.cinevault.core.TmdbMovieSummary
import com.dineshdevkota.cinevault.core.WatchRules
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.Converters
import com.dineshdevkota.cinevault.metadata.MetadataException
import com.dineshdevkota.cinevault.metadata.MetadataService
import com.dineshdevkota.cinevault.metadata.TmdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual library management from the Edit screen: rename, re-match, and resets (tvOS parity).
 * TMDB calls run on [Dispatchers.IO] because reading the sealed API key blocks.
 */
class LibraryEditor(private val database: CineVaultDatabase, private val metadata: MetadataService) {
    private val noGenresJson = Converters().fromList(emptyList())

    suspend fun rename(movieId: String, edit: TitleEdit.Valid) {
        database.movies().rename(movieId, edit.title, WatchRules.sortTitle(edit.title), edit.year)
    }

    suspend fun search(query: String): List<TmdbMovieSummary> = withContext(Dispatchers.IO) {
        metadata.tmdb.search(query.trim(), null)
    }

    /** Takes TMDB's title and details for the chosen result and locks them against automatic matching. */
    suspend fun fixMatch(movieId: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        metadata.apply(tmdbId, movieId, force = true)
        database.movies().lock(movieId)
    }

    suspend fun resetProgress(movieId: String) {
        database.movies().setWatched(movieId, false)
    }

    /**
     * Back to what a fresh scan shows, then matched straight away when a TMDB key is set. If matching fails the
     * movie stays reset and is matched again after the next scan; the thrown message says so.
     */
    suspend fun resetToFile(movieId: String) = withContext(Dispatchers.IO) {
        val movies = database.movies()
        val movie = movies.get(movieId) ?: return@withContext
        val fromFile = MovieEdits.fromFile(movie.filePath)
        movies.resetToFile(
            id = movieId,
            title = fromFile.title,
            sortTitle = WatchRules.sortTitle(fromFile.title),
            year = fromFile.year,
            tmdbId = fromFile.tmdbId,
            noGenresJson = noGenresJson,
        )
        if (!metadata.tmdb.hasKey()) return@withContext
        val reset = movies.get(movieId) ?: return@withContext
        try {
            metadata.match(reset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MetadataException("Reset to the file name; matching will be retried after the next scan. ${TmdbClient.describe(e)}")
        }
    }
}
