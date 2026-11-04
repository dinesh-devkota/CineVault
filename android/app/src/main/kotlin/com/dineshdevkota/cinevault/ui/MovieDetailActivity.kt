@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.playback.PlayerActivity
import kotlinx.coroutines.launch
import java.util.Locale

class MovieDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: return finish()
        setCineVaultContent { MovieDetailScreen(movieId, onClose = ::finish) }
    }

    companion object {
        private const val EXTRA_MOVIE_ID = "movie_id"

        fun start(context: Context, movieId: String) {
            context.startActivity(Intent(context, MovieDetailActivity::class.java).putExtra(EXTRA_MOVIE_ID, movieId))
        }
    }
}

@Composable
private fun MovieDetailScreen(movieId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val database = context.container.database
    val scope = rememberCoroutineScope()
    val movie by produceState<MovieEntity?>(null, movieId) {
        database.movies().observe(movieId).collect { if (it == null) onClose() else value = it }
    }
    val current = movie ?: return
    val sourceName by produceState<String?>(null, current.sourceId) {
        value = database.sources().get(current.sourceId)?.name
    }
    val actions = remember { FocusRequester() }
    LaunchedEffect(Unit) { actions.requestFocus() }

    Box(Modifier.fillMaxSize()) {
        Artwork(Tmdb.imageUrl(current.backdropPath, "w1280"), "", Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Background.copy(alpha = 0.97f),
                    0.45f to Background.copy(alpha = 0.85f),
                    1f to Background.copy(alpha = 0.25f),
                ),
            ),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                Tmdb.imageUrl(current.posterPath, "w500"),
                current.title,
                Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "CINEVAULT  /  MOVIE",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                    color = Accent,
                )
                Text(
                    current.title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                metadataLine(current).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = SecondaryText)
                }
                current.tagline?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic))
                }
                current.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 820.dp),
                    )
                }
                if (current.isMissing) {
                    Text(
                        "This file wasn't found in the last scan. Reconnect the NAS and rescan its source.",
                        color = ErrorText,
                    )
                }
                Row(
                    Modifier.padding(top = 12.dp).focusRequester(actions),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (current.isInProgress) {
                        Button(onClick = { PlayerActivity.start(context, current.id, resume = true) }) {
                            Text("Resume ${formatDuration(current.playbackPositionMs)}")
                        }
                        OutlinedButton(onClick = { PlayerActivity.start(context, current.id, resume = false) }) {
                            Text("Start Over")
                        }
                    } else {
                        Button(onClick = { PlayerActivity.start(context, current.id, resume = false) }) {
                            Text("Play")
                        }
                    }
                    OutlinedButton(onClick = { scope.launch { database.movies().setWatched(current.id, !current.isWatched) } }) {
                        Text(if (current.isWatched) "Mark Unwatched" else "Mark Watched")
                    }
                    OutlinedButton(onClick = { scope.launch { database.movies().setHidden(current.id, !current.isHidden) } }) {
                        Text(if (current.isHidden) "Unhide" else "Hide")
                    }
                    OutlinedButton(onClick = { EditMovieActivity.start(context, current.id) }) {
                        Text("Edit")
                    }
                }
                Text(
                    "IN YOUR LIBRARY",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                    color = SecondaryText,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    listOfNotNull(
                        sourceName,
                        Formatter.formatShortFileSize(context, current.fileSize),
                        "Watched".takeIf { current.isWatched },
                        "Hidden".takeIf { current.isHidden },
                    ).joinToString("   ·   "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    current.filePath,
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun metadataLine(movie: MovieEntity): String = buildList {
    movie.year?.let { add(it.toString()) }
    movie.runtimeMinutes?.takeIf { it > 0 }?.let { add(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m") }
    movie.rating?.takeIf { it > 0 }?.let { add(String.format(Locale.US, "★ %.1f", it)) }
    movie.genres.firstOrNull()?.let { add(it) }
}.joinToString("   ·   ")
