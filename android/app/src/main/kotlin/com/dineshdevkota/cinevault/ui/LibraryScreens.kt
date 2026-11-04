@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.LibraryFilter
import com.dineshdevkota.cinevault.core.LibraryQuery
import com.dineshdevkota.cinevault.core.LibrarySort
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.data.MovieEntity

private val tabs = listOf("Home", "Movies", "Search", "Sources", "Settings")

@Composable
fun RootScreen() {
    val context = LocalContext.current
    val container = context.container
    val movies by remember { container.database.movies().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }
    val openMovie: (MovieEntity) -> Unit = { MovieDetailActivity.start(context, it.id) }

    var tabRowFocused by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    var tabsPlaced by remember { mutableStateOf(false) }
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    // Like tvOS: Back first returns to the tab bar, then to Home, then leaves the app.
    BackHandler(enabled = !tabRowFocused || tab != 0) {
        if (tabRowFocused) tab = 0
        tabFocus[tab].requestFocus()
    }
    // TabRow subcomposes its tabs during layout, after this effect can first run.
    // Wait for their placement before restoring focus. A recreated window can
    // also have focus while its Compose view does not; acquire the owner first.
    LaunchedEffect(windowFocused, tabsPlaced) {
        if (windowFocused && tabsPlaced && !initialFocusRequested) {
            // The owner's initial focus search can select another tab.
            val selectedTab = tab
            if (!view.hasFocus()) view.requestFocus()
            initialFocusRequested = tabFocus[selectedTab].requestFocus() && view.hasFocus()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tab,
            modifier = Modifier
                .padding(start = ScreenPadding, top = 20.dp, bottom = 4.dp)
                .onGloballyPositioned { tabsPlaced = true }
                .onFocusChanged { tabRowFocused = it.hasFocus },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = index == tab,
                    onFocus = { tab = index },
                    modifier = Modifier.focusRequester(tabFocus[index]),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeTab(movies, openMovie)
                1 -> MoviesTab(movies, openMovie)
                2 -> SearchTab(movies, openMovie)
                3 -> SourcesTab(movies)
                else -> SettingsTab()
            }
        }
    }
}

@Composable
private fun HomeTab(movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit) {
    val status by LocalContext.current.container.scanner.status.collectAsStateWithLifecycle()
    val available = remember(movies) { movies.filter { !it.isHidden && !it.isMissing }.sortedByDescending { it.addedAt } }
    val continuing = remember(movies) { LibraryQuery.continueWatching(movies).take(20) }
    val unwatched = remember(available) { available.filter { !it.isWatched }.take(20) }
    val featured = remember(available) {
        val fresh = available.filter { !it.isWatched && !it.isInProgress }
        fresh.firstOrNull { it.backdropPath != null } ?: fresh.firstOrNull() ?: available.firstOrNull()
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            ScreenHeader(
                "Welcome to CineVault",
                Modifier.padding(horizontal = ScreenPadding),
                kicker = "YOUR PERSONAL CINEMA",
                trailing = status,
            )
        }
        if (available.isEmpty()) {
            item {
                EmptyState(
                    "Your movie collection starts here",
                    "Add a NAS source, then add a TMDB key for artwork and details.",
                )
            }
        } else {
            if (continuing.isNotEmpty()) item { MovieRow("Continue Watching", continuing, onOpen, wide = true) }
            featured?.let { movie -> item { FeaturedCard(movie, onClick = { onOpen(movie) }) } }
            item { MovieRow("Recently Added", available.take(20), onOpen) }
            if (unwatched.isNotEmpty()) item { MovieRow("Unwatched", unwatched, onOpen) }
        }
    }
}

@Composable
private fun FeaturedCard(movie: MovieEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = ScreenPadding).fillMaxWidth().aspectRatio(21f / 7f),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxSize()) {
            Artwork(Tmdb.imageUrl(movie.backdropPath ?: movie.posterPath, "w1280"), "", Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Transparent),
                ),
            )
            Column(Modifier.align(Alignment.CenterStart).padding(32.dp).fillMaxWidth(0.55f)) {
                Text("FEATURED", style = MaterialTheme.typography.labelLarge, color = Accent)
                Text(movie.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                movie.overview?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (movie.isInProgress) {
                ProgressLine(
                    movie.progress,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MovieGrid(content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PosterWidth),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        content = content,
    )
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.posters(movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit) {
    items(movies, key = { it.id }) { movie -> PosterCard(movie, onClick = { onOpen(movie) }) }
}

@Composable
private fun MoviesTab(movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit) {
    val settings = LocalContext.current.container.settings
    var sort by remember { mutableStateOf(settings.librarySort) }
    var filter by remember { mutableStateOf(settings.libraryFilter) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val genres = remember(movies) { listOf<String?>(null) + LibraryQuery.genres(movies) }
    val visible = remember(movies, sort, filter, genre) { LibraryQuery.visible(movies, sort, filter, genre) }

    MovieGrid {
        fullWidth { ScreenHeader("Movies", kicker = "YOUR COLLECTION", trailing = "${visible.size} titles") }
        fullWidth {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CycleButton("Sort", sort, LibrarySort.entries, { it.label }, {
                    sort = it
                    settings.librarySort = it
                })
                CycleButton("Show", filter, LibraryFilter.entries, { it.label }, {
                    filter = it
                    settings.libraryFilter = it
                })
                CycleButton("Genre", genre, genres, { it ?: "All" }, { genre = it })
            }
        }
        when {
            movies.isEmpty() -> fullWidth { EmptyState("No movies yet", "Add your NAS in the Sources tab.") }
            visible.isEmpty() -> fullWidth { EmptyState("No matching movies", "Try another filter or genre.") }
            else -> posters(visible, onOpen)
        }
    }
}

@Composable
private fun SearchTab(movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val results = remember(movies, text) {
        if (text.isBlank()) emptyList() else movies.filter { LibraryQuery.searchMatches(it, text) }.sortedBy { it.sortTitle }
    }

    MovieGrid {
        fullWidth { ScreenHeader("Search") }
        fullWidth {
            TvTextField(
                text,
                { text = it },
                "Titles, genres, files",
                Modifier.width(640.dp),
                imeAction = ImeAction.Search,
            )
        }
        when {
            text.isBlank() -> fullWidth {
                EmptyState("Search your collection", "Find a movie by title, genre or file name.")
            }
            results.isEmpty() -> fullWidth { EmptyState("No results", "Try a different search.") }
            else -> {
                fullWidth { Text("${results.size} results", color = SecondaryText) }
                posters(results, onOpen)
            }
        }
    }
}
