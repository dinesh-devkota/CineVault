package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.core.ScanExclusions
import com.dineshdevkota.cinevault.smb.NasBrowser
import com.dineshdevkota.cinevault.smb.NasEntry
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.CancellationException

/** A bounded folder picker that can live inside either source screen's existing scroll container. */
@Composable
fun IgnoredFoldersEditor(
    config: SourceConfig,
    ignoredFolders: List<String>,
    ignoredFiles: List<String>,
    onFoldersChange: (List<String>) -> Unit,
    onFilesChange: (List<String>) -> Unit,
    enabled: Boolean,
) {
    // Reset browsing when the connection or selected root changes, but retain it when rules change.
    val connection = config.copy(ignoredFolders = emptyList(), ignoredFiles = emptyList())
    key(connection) { IgnoredFoldersPanel(connection, ignoredFolders, ignoredFiles, onFoldersChange, onFilesChange, enabled) }
}

@Composable
private fun IgnoredFoldersPanel(
    config: SourceConfig,
    ignoredFolders: List<String>,
    ignoredFiles: List<String>,
    onFoldersChange: (List<String>) -> Unit,
    onFilesChange: (List<String>) -> Unit,
    enabled: Boolean,
) {
    val browser = remember { NasBrowser() }
    var manual by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<NasEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun joined(relative: String) = listOf(config.rootPath.replace('\\', '/').trim('/'), relative)
        .filter(String::isNotBlank).joinToString("/")
    fun add(value: String, directory: Boolean) {
        val folder = if (directory) ScanExclusions.normalizeFolder(value) else ScanExclusions.normalizeFile(value)
        if (folder == null) { error = "Enter a relative path inside the media folder, without . or .. segments."; return }
        if (directory && ignoredFolders.none { it.equals(folder, ignoreCase = true) }) onFoldersChange(ignoredFolders + folder)
        if (!directory && ignoredFiles.none { it.equals(folder, ignoreCase = true) }) onFilesChange(ignoredFiles + folder)
        manual = ""
        error = null
    }
    LaunchedEffect(browsing, path, refresh) {
        if (!browsing) return@LaunchedEffect
        busy = true
        error = null
        entries = emptyList()
        page = 0
        try {
            require(config.host.isNotBlank() && config.share.isNotBlank()) { "Enter the NAS address and share first." }
            require(config.port == null || config.port in 1..65535) { "Enter a valid NAS port." }
            entries = browser.listEntries(config.copy(rootPath = joined(path)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = SmbService.describe(failure)
        } finally { busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ignored folders and files", style = MaterialTheme.typography.titleMedium)
        Text("Ignore a folder with all its subfolders, or an individual file. Paths are relative to this media folder. " +
            "Saved exclusions hide existing items from the library and keep their watch history.", color = SecondaryText)
        ignoredFolders.forEach { folder ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Folder: $folder", modifier = Modifier.weight(1f))
                OutlinedButton(enabled = enabled && !busy, onClick = { onFoldersChange(ignoredFolders - folder) }) { Text("Include again") }
            }
        }
        ignoredFiles.forEach { file ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("File: $file", modifier = Modifier.weight(1f))
                OutlinedButton(enabled = enabled && !busy, onClick = { onFilesChange(ignoredFiles - file) }) { Text("Include again") }
            }
        }
        if (ignoredFolders.isEmpty() && ignoredFiles.isEmpty()) Text("Nothing ignored.", color = SecondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = enabled, onClick = { browsing = !browsing }) {
                Text(if (browsing) "Close ignore picker ▴" else "Choose folders or files to ignore ▾")
            }
        }
        if (browsing) {
            Text("Browsing: ${path.ifEmpty { "Media folder" }}", color = SecondaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = enabled && !busy && path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Text("Up") }
                OutlinedButton(enabled = enabled && !busy, onClick = { refresh++ }) { Text("Reload items") }
            }
            if (busy) Text("Loading folders and files…", color = SecondaryText)
            entries.drop(page * 12).take(12).forEach { entry ->
                val relative = listOf(path, entry.name).filter(String::isNotEmpty).joinToString("/")
                val rules = if (entry.isDirectory) ignoredFolders else ignoredFiles
                val direct = rules.any { it.equals(relative, ignoreCase = true) }
                val inherited = !direct && if (entry.isDirectory)
                    ScanExclusions.isDirectoryExcluded(joined(relative), config.rootPath, ignoredFolders)
                    else ScanExclusions.isFileExcluded(joined(relative), config.rootPath, ignoredFolders, emptyList())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.isDirectory) OutlinedButton(enabled = enabled && !busy, modifier = Modifier.weight(1f), onClick = { path = relative }) { Text("Folder: ${entry.name} ▸") }
                    else Text("File: ${entry.name}", modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = enabled && !busy && !inherited, onClick = {
                        if (direct) {
                            val remaining = rules.filterNot { it.equals(relative, ignoreCase = true) }
                            if (entry.isDirectory) onFoldersChange(remaining) else onFilesChange(remaining)
                        } else add(relative, entry.isDirectory)
                    }) { Text(if (inherited) "Ignored by parent" else if (direct) "Include" else if (entry.isDirectory) "Ignore folder" else "Ignore file") }
                }
            }
            if (!busy && error == null && entries.isEmpty()) Text("This folder is empty.", color = SecondaryText)
            if (entries.size > 12) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = enabled && !busy && page > 0, onClick = { page-- }) { Text("Previous items") }
                Text("${page + 1} / ${(entries.size + 11) / 12}")
                OutlinedButton(enabled = enabled && !busy && (page + 1) * 12 < entries.size, onClick = { page++ }) { Text("More items") }
            }
        }
        OutlinedButton(enabled = enabled && !busy, onClick = { showManual = !showManual }) {
            Text(if (showManual) "Hide manual path" else "Enter a path manually")
        }
        if (showManual) {
            TvTextField(manual, { manual = it }, "Relative folder or file path", Modifier.fillMaxWidth(), enabled = enabled && !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = enabled && !busy && manual.isNotBlank(), onClick = { add(manual, true) }) { Text("Ignore folder") }
                OutlinedButton(enabled = enabled && !busy && manual.isNotBlank(), onClick = { add(manual, false) }) { Text("Ignore file") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Including an item again restores it after the next successful scan.", color = SecondaryText)
    }
}
