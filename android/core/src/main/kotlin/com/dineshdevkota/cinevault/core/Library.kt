package com.dineshdevkota.cinevault.core

/** Library fields shared by the sort, filter and search rules. */
interface LibraryEntry {
    val title: String
    val sortTitle: String
    val fileName: String
    val year: Int?
    val rating: Double?
    val genres: List<String>
    val addedAt: Long
    val lastPlayedAt: Long?
    val isWatched: Boolean
    val isHidden: Boolean
    val isMissing: Boolean
    val playbackPositionMs: Long
}

object WatchRules {
    /** Playback past this fraction marks a movie watched. */
    const val WATCHED_FRACTION = 0.92
    const val IN_PROGRESS_MS = 30_000L

    fun isInProgress(isWatched: Boolean, positionMs: Long): Boolean = !isWatched && positionMs > IN_PROGRESS_MS

    fun reachedEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs.toDouble() / durationMs > WATCHED_FRACTION

    fun sortTitle(title: String): String {
        val lower = title.lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (lower.startsWith(article)) return lower.substring(article.length)
        }
        return lower
    }
}

enum class LibrarySort(val label: String) {
    TITLE("Title"),
    RECENTLY_ADDED("Recently Added"),
    YEAR("Year"),
    RATING("Rating"),
    LAST_PLAYED("Last Played");

    fun <T : LibraryEntry> sort(items: List<T>): List<T> = when (this) {
        TITLE -> items.sortedBy { it.sortTitle }
        RECENTLY_ADDED -> items.sortedByDescending { it.addedAt }
        YEAR -> items.sortedByDescending { it.year ?: 0 }
        RATING -> items.sortedByDescending { it.rating ?: 0.0 }
        LAST_PLAYED -> items.sortedByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
    }
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    UNWATCHED("Unwatched"),
    IN_PROGRESS("In Progress"),
    WATCHED("Watched"),
    HIDDEN("Hidden");

    fun matches(entry: LibraryEntry): Boolean = when (this) {
        ALL -> !entry.isHidden
        UNWATCHED -> !entry.isHidden && !entry.isWatched
        IN_PROGRESS -> !entry.isHidden && WatchRules.isInProgress(entry.isWatched, entry.playbackPositionMs)
        WATCHED -> !entry.isHidden && entry.isWatched
        HIDDEN -> entry.isHidden
    }
}

object LibraryQuery {
    fun <T : LibraryEntry> visible(items: List<T>, sort: LibrarySort, filter: LibraryFilter, genre: String?): List<T> =
        sort.sort(items.filter { !it.isMissing && filter.matches(it) && (genre == null || genre in it.genres) })

    /** Hidden and missing movies never appear in search. */
    fun searchMatches(entry: LibraryEntry, text: String): Boolean {
        if (text.isBlank() || entry.isMissing || entry.isHidden) return false
        val normalized = TitleMatcher.normalize(text)
        return (normalized.isNotEmpty() && TitleMatcher.normalize(entry.title).contains(normalized)) ||
            entry.genres.any { it.contains(text, ignoreCase = true) } ||
            entry.fileName.contains(text, ignoreCase = true)
    }

    fun genres(items: List<LibraryEntry>): List<String> = items.flatMap { it.genres }.toSortedSet().toList()

    fun <T : LibraryEntry> continueWatching(items: List<T>): List<T> = items
        .filter { !it.isMissing && !it.isHidden && WatchRules.isInProgress(it.isWatched, it.playbackPositionMs) }
        .sortedByDescending { it.lastPlayedAt ?: 0L }
}
