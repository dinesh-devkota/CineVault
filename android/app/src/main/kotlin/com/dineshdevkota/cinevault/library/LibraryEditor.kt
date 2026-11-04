package com.dineshdevkota.cinevault.library

import com.dineshdevkota.cinevault.core.TitleEdit
import com.dineshdevkota.cinevault.core.TmdbMovieSummary
import com.dineshdevkota.cinevault.core.WatchRules
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.metadata.MetadataService
import com.dineshdevkota.cinevault.playback.PlaybackProgressWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual library management from the Edit screen: rename, re-match, and resets (tvOS parity).
 * TMDB calls run on [Dispatchers.IO] because reading the sealed API key blocks.
 */
class LibraryEditor(
    private val database: CineVaultDatabase,
    private val metadata: MetadataService,
    private val progressWriter: PlaybackProgressWriter,
) {
    suspend fun rename(movieId: String, edit: TitleEdit.Valid) {
        database.movies().rename(movieId, edit.title, WatchRules.sortTitle(edit.title), edit.year)
    }

    suspend fun search(query: String): List<TmdbMovieSummary> = withContext(Dispatchers.IO) {
        metadata.tmdb.search(query.trim(), null)
    }

    suspend fun searchTV(query: String): List<TmdbMovieSummary> = withContext(Dispatchers.IO) {
        metadata.tmdb.searchTV(query.trim(), null)
    }

    suspend fun fixTVMatch(movieId: String, seriesId: Int) = withContext(Dispatchers.IO) {
        metadata.applyTV(seriesId, movieId, force = true)
    }

    /** Takes TMDB's title and details for the chosen result and locks them against automatic matching. */
    suspend fun fixMatch(movieId: String, tmdbId: Int) = withContext(Dispatchers.IO) {
        metadata.apply(tmdbId, movieId, force = true)
    }

    suspend fun resetProgress(movieId: String) = setWatched(movieId, false)

    /** Queue behind the player's final save so it cannot undo this later user action. */
    suspend fun setWatched(movieId: String, watched: Boolean) {
        progressWriter.await { database.movies().setWatched(movieId, watched) }
    }

    /**
     * Back to what a fresh scan shows, then matched straight away when a TMDB key is set. If matching fails the
     * movie stays reset and is matched again after the next scan; the thrown message says so.
     */
    suspend fun resetToFile(movieId: String) = withContext(Dispatchers.IO) {
        metadata.resetToFile(movieId)
    }
}
