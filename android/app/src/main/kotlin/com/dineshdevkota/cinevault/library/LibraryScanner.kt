package com.dineshdevkota.cinevault.library

import androidx.room.withTransaction
import com.dineshdevkota.cinevault.core.FilenameParser
import com.dineshdevkota.cinevault.core.LibrarySync
import com.dineshdevkota.cinevault.core.RemoteFile
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SecretBox
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
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
        try {
            val files = scanVideos(source.config(secrets))
            val movies = database.movies()
            val added = database.withTransaction {
                val diff = LibrarySync.diff(movies.forSource(source.id), files) { it.filePath }
                diff.present.forEach { (movie, file) -> movies.markPresent(movie.id, file.size) }
                diff.missing.forEach { movies.markMissing(it.id) }
                diff.added.forEach { file ->
                    val parsed = FilenameParser.parse(file.path)
                    movies.insert(
                        MovieEntity(
                            sourceId = source.id,
                            filePath = file.path,
                            fileName = file.fileName,
                            fileSize = file.size,
                            parsedTitle = parsed.title,
                            parsedYear = parsed.year,
                            tmdbId = parsed.tmdbId,
                            year = parsed.year,
                            title = LibrarySync.initialTitle(file, parsed),
                        ),
                    )
                }
                database.sources().setLastScan(source.id, System.currentTimeMillis())
                diff.added.size
            }
            val scanStatus = "${source.name}: ${files.size} movies ($added new)"
            statusFlow.value = scanStatus
            try {
                afterScan?.invoke()?.let { statusFlow.value = "$scanStatus. $it" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusFlow.value = "$scanStatus. Metadata update failed: ${e.message}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusFlow.value = "Scan failed for ${source.name}: ${SmbService.describe(e)}"
        }
    }
}
