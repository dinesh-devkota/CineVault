package com.dineshdevkota.cinevault.core

import java.util.Locale

data class EpisodeInfo(val seriesTitle: String, val season: Int, val episode: Int, val year: Int? = null)

/** Recognizes episode markers and numbered files beneath an explicit season folder. */
object EpisodeParser {
    private val markers = listOf(
        Regex("""(?:^|[ ._-])S(\d{1,4})E(\d{1,4})(?=$|[ ._-]|E\d)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[ ._-])(\d{1,4})x(\d{1,4})(?=$|[ ._-])""", RegexOption.IGNORE_CASE),
    )
    private val seasonFolder = Regex("""^Season[ ._-]*(\d{1,4})$""", RegexOption.IGNORE_CASE)
    private val numberedFile = Regex("""^(\d{1,4})\s*[-–—]\s*\S""")
    private val genericFolders = setOf("tv", "tvshows", "tvseries", "shows", "series", "television",
        "movies", "movie", "documentaries", "documentary", "videos", "video", "media", "library", "seasons")
    private fun isGeneric(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit) in genericFolders

    fun parse(path: String): EpisodeInfo? {
        val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
        val filename = parts.lastOrNull() ?: return null
        val parents = parts.dropLast(1)
        val folderIndex = parents.indexOfLast { seasonFolder.matches(it.trim()) }
        val folderSeason = if (folderIndex >= 0) {
            seasonFolder.matchEntire(parents[folderIndex].trim())?.groupValues?.get(1)
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
            prefix = filename.substring(0, marker.range.first).trim(' ', '.', '_', '-').takeIf(String::isNotBlank)
        } else {
            season = folderSeason ?: return null
            episode = numberedFile.find(filename)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (episode !in 0..999) return null
            prefix = null
        }
        val folderTitle = parents.getOrNull(folderIndex - 1)?.takeIf { folderIndex > 0 && !isGeneric(it) }
        val parentTitle = parents.lastOrNull()?.takeUnless { isGeneric(it) || seasonFolder.matches(it.trim()) }
        val rawTitle = folderTitle ?: prefix ?: parentTitle ?: return null
        val parsed = FilenameParser.parseName(rawTitle)
        val title = parsed.title.trim().takeIf(String::isNotEmpty) ?: return null
        return EpisodeInfo(title, season, episode, parsed.year)
    }
}
