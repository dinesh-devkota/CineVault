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
import androidx.compose.ui.unit.Dp
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
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.core.LibraryQuery
import com.dineshdevkota.cinevault.core.LibrarySort
import com.dineshdevkota.cinevault.core.Tmdb
import com.dineshdevkota.cinevault.data.MovieEntity

private val tabs = listOf("Home", "Library", "Search", "Sources", "Settings")

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
                    modifier = Modifier.focusRequester(tabFocus[index]).pointerClick { tab = index },
                    onClick = { tab = index },
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

private enum class HomeShowCollection { ALL, WATCHLIST, UNWATCHED }

@Composable
private fun HomeTab(movies: List<MovieEntity>, onOpen: (MovieEntity) -> Unit) {
    val context = LocalContext.current
    var selectedShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedShowCollection by rememberSaveable { mutableStateOf(HomeShowCollection.ALL) }
    val status by LocalContext.current.container.scanner.status.collectAsStateWithLifecycle()
    val available = remember(movies) { movies.filter { !it.isHidden && !it.isMissing }.sortedByDescending { it.addedAt } }
    val collections = remember(available) { available.groupBy { it.mediaKind } }
    val continuing = remember(movies) { LibraryQuery.continueWatching(movies).filter { it.mediaKind != MediaKind.MUSIC }.take(20) }
    val watchlist = remember(movies) { LibraryQuery.watchlist(movies) }
    val unwatched = remember(available) { available.filter { !it.isWatched } }
    val featured = remember(available) {
        val fresh = available.filter { !it.isWatched && !it.isInProgress && it.mediaKind != MediaKind.MUSIC }
        fresh.firstOrNull { it.backdropPath != null } ?: fresh.firstOrNull() ?: available.firstOrNull()
    }

    if (selectedShowId != null) {
        val selectedEpisodes = when (selectedShowCollection) {
            HomeShowCollection.ALL -> collections[MediaKind.TV_SHOW].orEmpty()
            HomeShowCollection.WATCHLIST -> watchlist.filter { it.mediaKind == MediaKind.TV_SHOW }
            HomeShowCollection.UNWATCHED -> unwatched.filter { it.mediaKind == MediaKind.TV_SHOW }
        }
        TVShowBrowser(selectedEpisodes, onOpen,
            initialShowId = selectedShowId, onExit = { selectedShowId = null })
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            ScreenHeader(
                "Welcome to Cine-Media",
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
                Button(onClick = { NasSetupActivity.start(context) }, modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    Text("Find my NAS")
                }
            }
        } else {
            if (continuing.isNotEmpty()) item { MovieRow("Continue Watching", continuing, onOpen, wide = true) }
            if (watchlist.isNotEmpty()) item {
                CollectionRow("Watchlist", watchlist, onOpen) {
                    selectedShowCollection = HomeShowCollection.WATCHLIST
                    selectedShowId = it
                }
            }
            featured?.let { movie -> item { FeaturedCard(movie, onClick = { onOpen(movie) }) } }
            for (kind in MediaKind.entries) {
                val collection = collections[kind].orEmpty()
                if (collection.isNotEmpty()) item {
                    if (kind == MediaKind.TV_SHOW) TVShowRow(collection) {
                        selectedShowCollection = HomeShowCollection.ALL
                        selectedShowId = it
                    }
                    else MovieRow(kind.label, collection.take(20), onOpen)
                }
            }
            if (unwatched.isNotEmpty()) item {
                CollectionRow("Unwatched", unwatched, onOpen) {
                    selectedShowCollection = HomeShowCollection.UNWATCHED
                    selectedShowId = it
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(movie: MovieEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = ScreenPadding).fillMaxWidth().aspectRatio(21f / 7f).pointerClick(onClick = onClick),
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
private fun MovieGrid(minimumPosterWidth: Dp = PosterWidth, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumPosterWidth),
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
    val container = LocalContext.current.container
    val settings = container.settings
    val sources by remember { container.database.sources().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    var sort by remember { mutableStateOf(settings.librarySort) }
    var filter by remember { mutableStateOf(settings.libraryFilter) }
    var genre by remember { mutableStateOf(settings.libraryGenre) }
    var sourceId by remember { mutableStateOf(settings.librarySourceId) }
    var decade by remember { mutableStateOf(settings.libraryDecade) }
    var kind by rememberSaveable { mutableStateOf(MediaKind.MOVIE) }
    val collection = remember(movies, kind) { movies.filter { it.mediaKind == kind } }
    val genres = remember(movies) { listOf<String?>(null) + LibraryQuery.genres(movies) }
    val decades = remember(movies) { listOf<Int?>(null) + LibraryQuery.decades(movies) }
    // Wait for the first source result before clearing a saved selection. An empty
    // loading placeholder must not erase the user's preference on every launch.
    val selectedSourceId = sourceId.takeIf { id -> sources?.any { it.id == id } != false }
    LaunchedEffect(sources, sourceId) {
        if (sources != null && selectedSourceId != sourceId) {
            sourceId = null
            settings.librarySourceId = null
        }
    }
    val visible = remember(collection, sort, filter, genre, selectedSourceId, decade) {
        LibraryQuery.visible(collection, sort, filter, genre, selectedSourceId, decade)
    }
    val tvShows = remember(visible, kind) { if (kind == MediaKind.TV_SHOW) groupTVShows(visible) else emptyList() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            kind.label, Modifier.padding(horizontal = ScreenPadding),
            kicker = "YOUR COLLECTION", trailing = if (kind == MediaKind.TV_SHOW) "${tvShows.size} shows" else "${visible.size} titles",
        )
        // Keep focused filters outside the recycled result items. Replacing all
        // posters with an empty state must not dispose the control being used.
        Column(
            Modifier.padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CycleButton("Sort", sort, LibrarySort.entries, { it.label }, {
                    sort = it
                    settings.librarySort = it
                })
                CycleButton("Show", filter, LibraryFilter.entries, { it.label }, {
                    filter = it
                    settings.libraryFilter = it
                })
                CycleButton("Genre", genre, genres, { it ?: "All" }, {
                    genre = it
                    settings.libraryGenre = it
                }, Modifier.width(240.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CycleButton("Source", selectedSourceId, listOf<String?>(null) + sources.orEmpty().map { it.id }, { id ->
                    sources?.firstOrNull { it.id == id }?.name ?: "All sources"
                }, {
                    sourceId = it
                    settings.librarySourceId = it
                }, Modifier.width(320.dp))
                CycleButton("Decade", decade, decades, { it?.let { year -> "${year}s" } ?: "All decades" }, {
                    decade = it
                    settings.libraryDecade = it
                })
                CycleButton("Section", kind, MediaKind.entries, { it.label }, {
                    kind = it
                    genre = null; decade = null; filter = LibraryFilter.ALL
                })
            }
        }
        Box(Modifier.weight(1f)) {
            if (kind == MediaKind.TV_SHOW && visible.isNotEmpty()) {
                TVShowBrowser(visible, onOpen, preparedShows = tvShows)
            } else {
            // Five columns on a 960dp-wide TV leave room for a complete poster
            // and its labels beneath the persistent filter controls.
            MovieGrid(minimumPosterWidth = 148.dp) {
                when {
                    collection.isEmpty() -> fullWidth { EmptyState("No ${kind.label.lowercase()} yet", "Add the parent media folder in Sources. Its subfolders are scanned automatically.") }
                    visible.isEmpty() -> fullWidth {
                        EmptyState(if (kind == MediaKind.MOVIE) "No matching movies" else "No matching items", "Try another filter, source, decade or genre.")
                    }
                    else -> posters(visible, onOpen)
                }
            }
            }
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
