@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.data.MovieEntity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Text as MobileText

val PosterWidth = 168.dp
private val CardShape = RoundedCornerShape(10.dp)

/** Remote artwork over a titled placeholder, so missing or failed images still read well. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF2A2D36), Color(0xFF15161B)))),
        contentAlignment = Alignment.Center,
    ) {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = SecondaryText,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Accent))
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(28.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
        Text("✓", color = Color.Black, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PosterCard(movie: MovieEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(Tmdb.imageUrl(movie.posterPath, "w342"), movie.title, Modifier.fillMaxSize())
                if (movie.isWatched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (movie.isInProgress) {
                    ProgressLine(movie.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp))
                }
            }
        }
        Text(
            movie.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(movie.year?.toString() ?: " ", style = MaterialTheme.typography.labelMedium, color = SecondaryText)
    }
}

@Composable
fun ContinueCard(movie: MovieEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(300.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(Tmdb.imageUrl(movie.backdropPath ?: movie.posterPath, "w780"), movie.title, Modifier.fillMaxSize())
                ProgressLine(movie.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
            }
        }
        Text(
            movie.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        val remaining = (movie.durationMs - movie.playbackPositionMs).coerceAtLeast(0)
        Text(
            if (movie.durationMs > 0) "${formatDuration(remaining)} left" else "Resume at ${formatDuration(movie.playbackPositionMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = SecondaryText,
        )
    }
}

@Composable
fun MovieRow(title: String, movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit, wide: Boolean = false) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = ScreenPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(movies, key = { it.id }) { movie ->
                if (wide) {
                    ContinueCard(movie, onClick = { onOpen(movie) })
                } else {
                    PosterCard(movie, onClick = { onOpen(movie) }, modifier = Modifier.width(PosterWidth))
                }
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, kicker: String? = null, trailing: String? = null, showBack: Boolean = true) {
    val activity = LocalActivity.current as? ComponentActivity
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        if (showBack && activity != null && activity !is com.dineshdevkota.cinevault.MainActivity) {
            OutlinedButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }, modifier = Modifier.padding(end = 16.dp)) { Text("Back") }
        }
        Column(Modifier.weight(1f)) {
            if (kicker != null) {
                Text(kicker, style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp), color = Accent)
            }
            Text(title, style = MaterialTheme.typography.displaySmall)
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A D-pad friendly picker: each press selects the next option. */
@Composable
fun <T> CycleButton(
    label: String,
    value: T,
    options: List<T>,
    text: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { if (options.isNotEmpty()) onChange(options[(options.indexOf(value) + 1) % options.size]) },
        modifier = modifier,
    ) {
        Text("$label: ${text(value)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Arrow keys move focus off the field; the centre button opens the on-screen keyboard. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        enabled = enabled,
        label = { MobileText(label) },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
            showKeyboardOnFocus = false,
        ),
        // Results update live, so the IME's Search key only needs to close the keyboard.
        keyboardActions = KeyboardActions(onSearch = { defaultKeyboardAction(ImeAction.Done) }),
    )
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
