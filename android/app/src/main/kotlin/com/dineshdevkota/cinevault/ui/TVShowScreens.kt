@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.data.MovieEntity

@Composable
internal fun CollectionRow(
    title: String, movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit, onShow: (String) -> Unit,
) {
    val collection = remember(movies) { collectionItems(movies, limit = 20) }
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = ScreenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(collection, key = { it.id }) { item ->
                when (item) {
                    is CollectionItem.Movie -> PosterCard(item.movie, onClick = { onOpen(item.movie) }, modifier = Modifier.width(PosterWidth))
                    is CollectionItem.Show -> TVArtworkCard(item.show.title, "${item.show.seasons.size} seasons", item.show.posterPath,
                        modifier = Modifier.width(PosterWidth), onClick = { onShow(item.show.id) })
                }
            }
        }
    }
}

@Composable
internal fun TVShowRow(episodes: List<MovieEntity>, onShow: (String) -> Unit) {
    val shows = remember(episodes) { groupTVShows(episodes).take(20) }
    Column {
        Text("TV Shows", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = ScreenPadding))
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(shows, key = { it.id }) { show ->
                TVArtworkCard(show.title, "${show.seasons.size} seasons", show.posterPath,
                    modifier = Modifier.width(PosterWidth), onClick = { onShow(show.id) })
            }
        }
    }
}

@Composable
internal fun TVShowBrowser(
    episodes: List<MovieEntity>, onOpen: (MovieEntity) -> Unit,
    initialShowId: String? = null, onExit: (() -> Unit)? = null,
    preparedShows: List<TVShowGroup>? = null,
) {
    val shows = preparedShows ?: remember(episodes) { groupTVShows(episodes) }
    var showId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var season by rememberSaveable(initialShowId) { mutableStateOf<Int?>(null) }
    var anchorId by rememberSaveable(initialShowId) { mutableStateOf(shows.firstOrNull { it.id == initialShowId }?.episodes?.firstOrNull()?.id) }
    val show = shows.firstOrNull { it.id == showId }
        ?: shows.firstOrNull { candidate -> anchorId != null && candidate.episodes.any { it.id == anchorId } }
    val goBack: () -> Unit = {
        if (season != null) season = null
        else if (initialShowId != null && onExit != null) onExit()
        else { showId = null; anchorId = null }
    }
    BackHandler(enabled = showId != null || onExit != null) {
        if (showId == null) onExit?.invoke() else goBack()
    }
    LaunchedEffect(shows.map { it.id }) {
        if (showId != null && shows.none { it.id == showId }) {
            showId = show?.id
            if (show == null) { season = null; anchorId = null }
        }
    }
    LaunchedEffect(show?.seasons) {
        if (season != null && show?.seasons?.contains(season) != true) season = null
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showId != null || onExit != null) {
            Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                OutlinedButton(onClick = { if (showId == null) onExit?.invoke() else goBack() }) { Text("Back") }
                Text(show?.let { selected -> season?.let { "${selected.title} · ${tvSeasonTitle(it)}" } ?: selected.title } ?: "TV Shows",
                    style = MaterialTheme.typography.titleLarge)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (season == null) 148.dp else 260.dp),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (show == null) {
                items(shows, key = { it.id }) { item ->
                    TVArtworkCard(item.title, "${item.seasons.size} seasons", item.posterPath,
                        onClick = { showId = item.id; anchorId = item.episodes.firstOrNull()?.id; season = null })
                }
            } else if (season == null) {
                items(show.seasons, key = { it }) { number ->
                    val children = show.episodes.filter { tvSeason(it) == number }
                    TVArtworkCard(tvSeasonTitle(number), "${children.size} episodes", children.firstNotNullOfOrNull { it.seasonPosterPath },
                        onClick = { season = number })
                }
            } else {
                val children = show.episodes.filter { tvSeason(it) == season }.sortedWith(compareBy(::tvEpisode).thenBy { it.filePath })
                items(children, key = { it.id }) { episode ->
                    val number = tvEpisode(episode).takeUnless { it == Int.MAX_VALUE }
                    TVArtworkCard(number?.let { "E$it · ${episode.title}" } ?: episode.title,
                        if (episode.isWatched) "Watched" else if (episode.isInProgress) "In progress" else "Episode",
                        episode.backdropPath, wide = true, onClick = { onOpen(episode) })
                }
            }
        }
    }
}

@Composable
private fun TVArtworkCard(
    title: String, subtitle: String, path: String?, modifier: Modifier = Modifier,
    wide: Boolean = false, onClick: () -> Unit,
) {
    Column(modifier) {
        Card(onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp))) {
            Artwork(Tmdb.imageUrl(path, if (wide) "w780" else "w342"), title, Modifier.fillMaxSize())
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = SecondaryText)
    }
}
