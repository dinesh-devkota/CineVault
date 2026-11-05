package com.dineshdevkota.cinevault.core

import java.util.Locale

data class EpisodeInfo(val seriesTitle: String, val season: Int, val episode: Int, val year: Int? = null)

/** Recognizes episode markers and numbered files beneath an explicit season folder. */
object EpisodeParser {
    private val markers = listOf(
        Regex("""(?<![\p{L}\p{N}])S\s*(\d{1,4})[\s._-]*x?[\s._-]*E\s*(\d{1,4})(?=$|[^\p{L}\p{N}]|E\s*\d)""", RegexOption.IGNORE_CASE),
        // Restrict x-style season numbers so video dimensions cannot become episodes.
        Regex("""(?<![\p{L}\p{N}])(\d{1,2})\s*x\s*(\d{1,4})(?=$|[^\p{L}\p{N}])""", RegexOption.IGNORE_CASE),
    )
    private val seasonFolder = Regex("""^(?:(.+?)[ ._-]+)?Season[ ._-]*(\d{1,4})$""", RegexOption.IGNORE_CASE)
    private val numberedFile = Regex("""^(\d{1,4})\s*[-–—]\s*\S""")
    private val genericFolders = setOf("tv", "tvshows", "tvseries", "shows", "series", "television",
        "movies", "movie", "documentaries", "documentary", "videos", "video", "media", "library", "seasons")
    private val tvRoots = setOf("tv", "tvshows", "tvseries", "shows", "series", "television")
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun isGeneric(value: String) = normalized(value) in genericFolders

    fun parse(path: String): EpisodeInfo? {
        val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
        val filename = parts.lastOrNull() ?: return null
        val parents = parts.dropLast(1)
        val folderIndex = parents.indexOfLast { seasonFolder.matches(it.trim()) }
        val folderSeason = if (folderIndex >= 0) {
            seasonFolder.matchEntire(parents[folderIndex].trim())?.groupValues?.get(2)
                ?.toIntOrNull()?.takeIf { it in 0..999 } ?: return null
        } else null
        val marker = markers.firstNotNullOfOrNull { it.find(filename) }
        val season: Int
        val episode: Int
        val prefix: String?
        if (marker != null) {
            season = marker.groupValues[1].toIntOrNull() ?: return null
            episode = marker.groupValues[2].toIntOrNull() ?: return null
            if (season !in 0..999 || episode !in 0..999) return null
            prefix = filename.substring(0, marker.range.first)
                .trim(' ', '.', '_', '-', '[', ']', '(', ')', '{', '}').takeIf(String::isNotBlank)
        } else {
            season = folderSeason ?: return null
            episode = numberedFile.find(filename)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (episode !in 0..999) return null
            prefix = null
        }
        // The library's series folder is stable even when filenames use season-specific titles.
        val tvRoot = parents.indexOfLast { normalized(it) in tvRoots }
        val libraryTitle = parents.getOrNull(tvRoot + 1)?.takeIf {
            tvRoot >= 0 && !isGeneric(it) && !seasonFolder.matches(it.trim())
        }
        var seriesIndex = folderIndex - 1
        while (seriesIndex >= 0 && seasonFolder.matches(parents[seriesIndex].trim())) seriesIndex--
        val folderTitle = parents.getOrNull(seriesIndex)?.takeUnless(::isGeneric)
        val seasonTitle = parents.getOrNull(folderIndex)?.let {
            seasonFolder.matchEntire(it.trim())?.groupValues?.get(1)?.trim(' ', '.', '_', '-')
                ?.takeIf { title -> title.isNotBlank() && !isGeneric(title) }
        }
        val parentTitle = parents.lastOrNull()?.takeUnless { isGeneric(it) || seasonFolder.matches(it.trim()) }
        val rawTitle = folderTitle ?: libraryTitle ?: seasonTitle ?: prefix ?: parentTitle ?: return null
        val parsed = FilenameParser.parseName(rawTitle)
        val title = parsed.title.trim().takeIf(String::isNotEmpty) ?: return null
        return EpisodeInfo(title, season, episode, parsed.year)
    }
}
