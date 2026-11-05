package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.smb.NasBrowser
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.CancellationException

@Composable
fun NasRootFolderPicker(config: SourceConfig, enabled: Boolean, onSelect: (String) -> Unit) {
    key(config.copy(ignoredFolders = emptyList(), ignoredFiles = emptyList())) {
        var expanded by remember { mutableStateOf(false) }
        var path by remember { mutableStateOf(config.rootPath.replace('\\', '/').trim('/')) }
        var folders by remember { mutableStateOf(emptyList<String>()) }
        var page by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(expanded, path) {
            if (!expanded) return@LaunchedEffect
            loading = true; ready = false; error = null; folders = emptyList(); page = 0
            try {
                require(config.host.isNotBlank() && config.share.isNotBlank()) { "Enter the NAS address and share first." }
                folders = NasBrowser().listFolders(config.copy(rootPath = path))
                ready = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = SmbService.describe(failure) }
            finally { loading = false }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan folder: ${config.rootPath.ifBlank { "Share root (all folders)" }}")
            OutlinedButton(enabled = enabled, onClick = { expanded = !expanded }) {
                Text(if (expanded) "Close folder dropdown ▴" else "Choose scan folder ▾")
            }
            if (expanded) {
                Text("Browsing: ${path.ifBlank { "Share root" }}", color = SecondaryText)
                Text("Changing the scan folder clears its old ignore rules. Choose new rules before saving.", color = SecondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = enabled && !loading && path.isNotEmpty(), onClick = { path = "" }) { Text("Share root") }
                    OutlinedButton(enabled = enabled && !loading && path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Text("Up") }
                }
                Button(enabled = enabled && !loading && ready, onClick = { expanded = false; onSelect(path) }) { Text("Use this folder") }
                if (loading) Text("Loading folders…", color = SecondaryText)
                folders.drop(page * 10).take(10).forEach { folder ->
                    OutlinedButton(enabled = enabled && !loading, onClick = {
                        path = listOf(path, folder).filter(String::isNotEmpty).joinToString("/")
                    }) { Text("$folder ▸") }
                }
                if (ready && folders.isEmpty()) Text("No subfolders.", color = SecondaryText)
                if (folders.size > 10) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = enabled && page > 0, onClick = { page-- }) { Text("Previous") }
                    Text("${page + 1} / ${(folders.size + 9) / 10}")
                    OutlinedButton(enabled = enabled && (page + 1) * 10 < folders.size, onClick = { page++ }) { Text("More") }
                }
                error?.let { Text(it, color = SecondaryText) }
            }
        }
    }
}
