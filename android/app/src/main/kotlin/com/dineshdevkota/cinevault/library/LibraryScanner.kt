package com.dineshdevkota.cinevault.library

import androidx.room.withTransaction
import com.dineshdevkota.cinevault.core.FilenameParser
import com.dineshdevkota.cinevault.core.MediaFile
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.core.LibrarySync
import com.dineshdevkota.cinevault.core.RemoteFile
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SecretBox
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import com.dineshdevkota.cinevault.smb.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Walks a NAS source and syncs movie rows with the files found. Scans run one at a time, in order. */
class LibraryScanner(
    private val database: CineVaultDatabase,
    private val secrets: SecretBox,
    private val scanVideos: suspend (SourceConfig) -> List<RemoteFile>,
    /** Runs after a successful scan (metadata matching); returns a failure message to append, if any. */
    var afterScan: (suspend () -> String?)? = null,
    private val scanWithProgress: (suspend (SourceConfig, suspend (ScanProgress) -> Unit) -> List<RemoteFile>)? = null,
) {
    private val mutex = Mutex()
    private val queued = AtomicInteger(0)
    private val scanning = MutableStateFlow(false)
    private val statusFlow = MutableStateFlow<String?>(null)

    val isScanning: StateFlow<Boolean> = scanning.asStateFlow()
    val status: StateFlow<String?> = statusFlow.asStateFlow()

    fun clearStatus() {
        statusFlow.value = null
    }

    suspend fun scan(sourceId: String) {
        queued.incrementAndGet()
        scanning.value = true
        try {
            mutex.withLock { scanLocked(sourceId) }
        } finally {
            if (queued.decrementAndGet() == 0) scanning.value = false
        }
    }

    suspend fun scanAll() {
        val sources = try {
            database.sources().all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusFlow.value = "Could not load NAS sources: ${e.message}"
            return
        }
        for (source in sources) scan(source.id)
    }

    private suspend fun scanLocked(sourceId: String) {
        // Re-read after waiting for the lock: the source may have been edited or deleted meanwhile.
        val source = database.sources().get(sourceId) ?: return
        statusFlow.value = "Scanning ${source.name}…"
        var partialAdded = 0
        try {
            val movies = database.movies()
            val config = source.config(secrets)
            val files = if (scanWithProgress == null) scanVideos(config) else scanWithProgress(config) { progress ->
                database.withTransaction {
                    check(database.sources().get(source.id) == source) { "The NAS source changed during the scan. Scan again." }
                    val existing = if (progress.files.isEmpty()) emptyMap() else
                        movies.forPaths(source.id, progress.files.map { it.path }).associateBy { it.filePath }
                    for (file in progress.files) {
                        val current = existing[file.path]
                        if (current == null) { movies.insert(newEntry(source.id, file)); partialAdded++ }
                        else movies.markPresent(current.id, file.size)
                    }
                }
                statusFlow.value = "Scanning ${source.name}: ${progress.found} items in ${progress.folders} folders…"
            }
            val added = database.withTransaction {
                // An edit/delete can finish while the NAS operation is suspended. Never apply
                // the old share's results to the replacement source (or recreate deleted rows).
                if (database.sources().get(source.id) != source) return@withTransaction null
                val diff = LibrarySync.diff(movies.forSource(source.id), files) { it.filePath }
                diff.present.forEach { (movie, file) -> movies.markPresent(movie.id, file.size) }
                diff.missing.forEach { movies.markMissing(it.id) }
                diff.added.forEach { file ->
                    movies.insert(newEntry(source.id, file))
                }
                database.sources().setLastScan(source.id, System.currentTimeMillis())
                diff.added.size + partialAdded
            }
            if (added == null) {
                statusFlow.value = "${source.name} changed during the scan. Scan the source again."
                return
            }
            val scanStatus = "${source.name}: ${files.size} items ($added new). Updating categories and artwork…"
            statusFlow.value = scanStatus
            try {
                val metadataFailure = afterScan?.invoke()
                val currentCounts = movies.forSource(source.id).filter { !it.isMissing }.groupingBy { it.mediaKind }.eachCount()
                val complete = "${source.name}: ${currentCounts[MediaKind.MOVIE] ?: 0} movies, ${currentCounts[MediaKind.DOCUMENTARY] ?: 0} documentaries, ${currentCounts[MediaKind.TV_SHOW] ?: 0} TV episodes, ${currentCounts[MediaKind.MUSIC] ?: 0} music items ($added new)"
                statusFlow.value = if (metadataFailure == null) complete else "$complete. $metadataFailure"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusFlow.value = "$scanStatus. Metadata update failed: ${e.message}"
            }
        } catch (e: CancellationException) {
            statusFlow.value = "Scan cancelled for ${source.name}. Items already found are kept."
            throw e
        } catch (e: Exception) {
            statusFlow.value = "Scan failed for ${source.name}: ${SmbService.describe(e)}. Items already found are kept; retry the scan."
        }
    }

    private fun newEntry(sourceId: String, file: RemoteFile): MovieEntity {
        val parsed = FilenameParser.parse(file.path)
        val music = MediaFile.kind(file.path) == MediaKind.MUSIC
        val title = if (music) file.fileName.substringBeforeLast('.') else parsed.title
        return MovieEntity(sourceId = sourceId, filePath = file.path, fileName = file.fileName, fileSize = file.size,
            parsedTitle = title, parsedYear = if (music) null else parsed.year, tmdbId = if (music) null else parsed.tmdbId,
            year = if (music) null else parsed.year, title = if (music) title else LibrarySync.initialTitle(file, parsed),
            metadataFetched = music)
    }
}
