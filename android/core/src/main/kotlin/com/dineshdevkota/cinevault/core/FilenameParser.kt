package com.dineshdevkota.cinevault.core

import java.time.Year

/** Result of parsing a movie file path. */
data class ParsedMovieName(val title: String, val year: Int? = null, val tmdbId: Int? = null)

/**
 * Turns release-style file names ("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv")
 * into a clean title and year. Mirrors the tvOS CineVaultCore parser.
 */
object FilenameParser {
    private val junkTokens = setOf(
        "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "uhd", "hd", "fhd", "sd",
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web", "webrip", "web-dl", "webdl",
        "hdrip", "dvdrip", "dvd", "dvdscr", "hdtv", "hdcam", "cam", "ts",
        "x264", "x265", "h264", "h265", "h.264", "h.265", "hevc", "avc", "xvid", "divx", "av1", "10bit", "8bit",
        "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "flac", "mp3", "dd5.1", "ddp5.1", "5.1", "7.1",
        "hdr", "hdr10", "hdr10+", "dv", "dolby", "vision", "sdr", "imax",
        "proper", "repack", "extended", "unrated", "uncut", "remastered", "limited", "internal",
        "multi", "dual", "subbed", "dubbed", "nf", "amzn", "dsnp", "hmax", "atvp",
    )
    private val tmdbTag = Regex("""[{\[]tmdb(id)?[-=](\d+)[}\]]""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\[[^\]]*\]""")
    private val separators = Regex("[._()]")
    private val genericPart = Regex("""(cd|disc|part|pt)\s*\d+""")
    private val genericTitles = setOf("movie", "video", "film", "main", "feature")

    fun parse(path: String): ParsedMovieName {
        val components = path.split('/').filter { it.isNotEmpty() }
        val fileName = components.lastOrNull() ?: path
        var parsed = parseName(Paths.stem(fileName))
        // "Movie (2010)/movie.mkv" or "Movie (2010)/CD1.avi": fall back to the folder name.
        if (parsed.year == null && components.size >= 2) {
            val folder = parseName(components[components.size - 2])
            if (folder.year != null || parsed.title.isEmpty() || isGeneric(parsed.title)) {
                parsed = ParsedMovieName(folder.title, folder.year, parsed.tmdbId ?: folder.tmdbId)
            }
        }
        return parsed
    }

    fun parseName(raw: String): ParsedMovieName {
        var name = raw
        var tmdbId: Int? = null
        tmdbTag.find(name)?.let { match ->
            tmdbId = match.groupValues[2].toIntOrNull()
            name = name.removeRange(match.range)
        }
        // Drop bracketed tags like [YTS] but keep "(1999)" for year detection.
        name = bracketTag.replace(name, " ")
        name = separators.replace(name, " ")
        val tokens = name.split(' ').filter { it.isNotEmpty() }.toMutableList()

        // Year: the last plausible year that is not the first token ("1917 2019", "2001 A Space Odyssey 1968").
        var year: Int? = null
        var cutIndex = tokens.size
        val maxYear = Year.now().value + 1
        for (index in tokens.indices.reversed()) {
            if (index == 0) break
            val token = tokens[index]
            if (token.length == 4 && token.all { it in '0'..'9' } && token.toInt() in 1900..maxYear) {
                year = token.toInt()
                cutIndex = index
                break
            }
        }
        if (year == null) {
            (1 until tokens.size).firstOrNull { isJunk(tokens[it]) }?.let { cutIndex = it }
        }
        while (tokens.size > cutIndex) tokens.removeAt(tokens.lastIndex)
        // Trailing release-group / dash leftovers.
        while (tokens.isNotEmpty() && (tokens.last() == "-" || isJunk(tokens.last()))) tokens.removeAt(tokens.lastIndex)
        return ParsedMovieName(tokens.joinToString(" ").trim(' ', '-'), year, tmdbId)
    }

    internal fun isJunk(token: String): Boolean {
        val lower = token.lowercase()
        if (lower in junkTokens) return true
        // "x264-GROUP", "WEB-DL", "DTS-HD"
        val head = lower.split('-').firstOrNull { it.isNotEmpty() }
        return head != null && head in junkTokens
    }

    internal fun isGeneric(title: String): Boolean {
        val lower = title.lowercase()
        return lower in genericTitles || genericPart.matches(lower)
    }
}

internal object Paths {
    /** File name without its last extension; dot-files keep their name. */
    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extension(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase() else ""
    }
}
