@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.MovieEdits
import com.dineshdevkota.cinevault.core.TitleEdit
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.core.TmdbMovieSummary
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.metadata.TmdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Rename, re-match against TMDB, or reset a movie (tvOS Edit sheet parity). */
class EditMovieActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: return finish()
        setCineVaultContent { EditMovieScreen(movieId, onClose = ::finish) }
    }

    companion object {
        private const val EXTRA_MOVIE_ID = "movie_id"

        fun start(context: Context, movieId: String) {
            context.startActivity(Intent(context, EditMovieActivity::class.java).putExtra(EXTRA_MOVIE_ID, movieId))
        }
    }
}

private class Notice(val text: String, val isError: Boolean)

@Composable
private fun EditMovieScreen(movieId: String, onClose: () -> Unit) {
    val container = LocalContext.current.container
    val editor = container.editor
    val scope = rememberCoroutineScope()
    val movie by produceState<MovieEntity?>(null, movieId) {
        container.database.movies().observe(movieId).collect { if (it == null) onClose() else value = it }
    }
    val current = movie ?: return
    // Keyed so the fields follow the saved movie, e.g. after a reset whose re-match failed.
    var title by remember(current.title) { mutableStateOf(current.title) }
    var year by remember(current.year) { mutableStateOf(current.year?.toString().orEmpty()) }
    val episode = current.episodeInfo
    var query by remember(current.title, current.seriesTitle) { mutableStateOf(current.seriesTitle ?: episode?.seriesTitle ?: current.title) }
    var results by remember { mutableStateOf<List<TmdbMovieSummary>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstField.requestFocus() }

    /** Runs one edit; [work] returns a confirmation to show, or null to close the screen. */
    fun launchWork(work: suspend () -> String?) {
        busy = true
        notice = null
        scope.launch {
            try {
                val confirmation = work()
                if (confirmation == null) onClose() else notice = Notice(confirmation, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Notice(TmdbClient.describe(e), isError = true)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Edit Media", kicker = "LIBRARY", trailing = current.fileName)
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Details", style = MaterialTheme.typography.titleMedium)
                TvTextField(title, { title = it }, "Title", Modifier.fillMaxWidth().focusRequester(firstField))
                TvTextField(
                    year,
                    { year = it },
                    "Year (optional)",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        when (val edit = MovieEdits.titleEdit(title, year)) {
                            is TitleEdit.Invalid -> notice = Notice(edit.message, isError = true)
                            is TitleEdit.Valid -> launchWork {
                                editor.rename(current.id, edit)
                                null
                            }
                        }
                    },
                ) {
                    Text("Save Title")
                }
                Text(
                    "A saved title is kept when you rescan or refresh metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                )
                Text("Library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        enabled = !busy && (current.isWatched || current.playbackPositionMs > 0),
                        onClick = {
                            launchWork {
                                editor.resetProgress(current.id)
                                "Progress reset."
                            }
                        },
                    ) {
                        Text("Reset Progress")
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            launchWork {
                                editor.resetToFile(current.id)
                                null
                            }
                        },
                    ) {
                        Text("Reset to File Name")
                    }
                }
                Text(
                    current.filePath,
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Fix Match", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(query, { query = it }, "Search TMDB", Modifier.weight(1f), imeAction = ImeAction.Search)
                    Button(
                        enabled = !busy && query.isNotBlank(),
                        onClick = {
                            launchWork {
                                results = if (episode != null) editor.searchTV(query) else editor.search(query)
                                if (results.isEmpty()) "No results." else if (episode != null) "Choose the right TV show." else "Choose the right movie."
                            }
                        },
                    ) {
                        Text("Search")
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(results, key = { it.id }) { result ->
                        MatchCard(result) {
                            if (!busy) {
                                launchWork {
                                    if (episode != null) editor.fixTVMatch(current.id, result.id)
                                    else editor.fixMatch(current.id, result.id)
                                    null
                                }
                            }
                        }
                    }
                }
            }
        }
        notice?.let { Text(it.text, color = if (it.isError) ErrorText else SecondaryText) }
    }
}

@Composable
private fun MatchCard(result: TmdbMovieSummary, onClick: () -> Unit) {
    Column(Modifier.width(150.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        ) {
            Artwork(Tmdb.imageUrl(result.posterPath, "w342"), result.title, Modifier.fillMaxSize())
        }
        Text(
            result.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(result.year?.toString() ?: " ", style = MaterialTheme.typography.labelMedium, color = SecondaryText)
    }
}
