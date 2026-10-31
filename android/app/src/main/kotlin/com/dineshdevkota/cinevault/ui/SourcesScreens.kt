@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import kotlinx.coroutines.launch

@Composable
fun SourcesTab(movies: List<MovieEntity>) {
    val context = LocalContext.current
    val container = context.container
    val sources by remember { container.database.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scanning by container.scanner.isScanning.collectAsStateWithLifecycle()
    val status by container.scanner.status.collectAsStateWithLifecycle()
    val counts = remember(movies) { movies.groupingBy { it.sourceId }.eachCount() }

    LazyColumn(
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("Sources", kicker = "YOUR NAS", trailing = status) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { SourceEditorActivity.start(context, null) }) { Text("Add NAS Source") }
                OutlinedButton(
                    onClick = { container.scope.launch { container.scanner.scanAll() } },
                    enabled = !scanning && sources.isNotEmpty(),
                ) {
                    Text(if (scanning) "Scanning…" else "Rescan All")
                }
            }
        }
        if (sources.isEmpty()) {
            item { EmptyState("No NAS sources yet", "Add your NAS with its address, share and movie folder.") }
        }
        items(sources, key = { it.id }) { source ->
            SourceRow(
                source,
                count = counts[source.id] ?: 0,
                scanning = scanning,
                onScan = { container.scope.launch { container.scanner.scan(source.id) } },
                onEdit = { SourceEditorActivity.start(context, source.id) },
            )
        }
    }
}

@Composable
private fun SourceRow(source: SourceEntity, count: Int, scanning: Boolean, onScan: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceColor).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(sourceUrl(source), style = MaterialTheme.typography.bodyMedium, color = SecondaryText)
            Text(
                "$count movies · last scan ${source.lastScanAt?.let(::formatDate) ?: "never"}",
                style = MaterialTheme.typography.labelMedium,
                color = SecondaryText,
            )
        }
        OutlinedButton(onClick = onScan, enabled = !scanning) { Text("Scan") }
        OutlinedButton(onClick = onEdit) { Text("Edit") }
    }
}

fun sourceUrl(source: SourceEntity): String =
    "smb://" + listOf(source.host, source.share, source.rootPath).filter { it.isNotEmpty() }.joinToString("/")

@Composable
fun SettingsTab() {
    val settings = LocalContext.current.container.settings
    var apiKey by remember { mutableStateOf(settings.tmdbApiKey) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("Settings") }
        item { Text("Metadata (themoviedb.org)", style = MaterialTheme.typography.titleLarge) }
        item {
            TvTextField(
                apiKey,
                { apiKey = it },
                "TMDB API key or read access token",
                Modifier.width(640.dp),
                isPassword = true,
                imeAction = ImeAction.Done,
            )
        }
        item {
            Button(onClick = {
                settings.tmdbApiKey = apiKey
                message = if (apiKey.isBlank()) "TMDB key removed." else "TMDB key saved."
            }) {
                Text("Save Key")
            }
        }
        item {
            Text(
                message ?: "Get a free key at themoviedb.org → Settings → API. Posters and details are fetched " +
                    "automatically after each scan.",
                color = SecondaryText,
            )
        }
        item { Text("About", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "CineVault streams MP4, MKV, WebM, AVI, TS and more directly from your NAS over SMB. " +
                    "Which video and audio codecs play depends on your TV device.",
                color = SecondaryText,
            )
        }
        item {
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = SecondaryText)
        }
    }
}
