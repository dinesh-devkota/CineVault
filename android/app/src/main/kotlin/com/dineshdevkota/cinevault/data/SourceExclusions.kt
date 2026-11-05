package com.dineshdevkota.cinevault.data

import androidx.room.withTransaction
import com.dineshdevkota.cinevault.core.ScanExclusions
import java.util.Locale

/** Save the rule change and hide indexed exclusions together, without deleting history. */
suspend fun CineVaultDatabase.saveSourceWithExclusions(source: SourceEntity) {
    val folders = source.ignoredFolders.map {
        requireNotNull(ScanExclusions.normalizeFolder(it)) { "Enter a folder path relative to the media folder." }
    }.distinctBy { it.lowercase(Locale.ROOT) }
    val files = source.ignoredFiles.map {
        requireNotNull(ScanExclusions.normalizeFile(it)) { "Enter a file path relative to the media folder." }
    }.distinctBy { it.lowercase(Locale.ROOT) }
    val saved = source.copy(ignoredFolders = folders, ignoredFiles = files)
    withTransaction {
        sources().upsert(saved)
        if (folders.isNotEmpty() || files.isNotEmpty()) {
            movies().forSource(saved.id).filter {
                ScanExclusions.isFileExcluded(it.filePath, saved.rootPath, folders, files)
            }.forEach { movies().markMissing(it.id) }
        }
    }
}
