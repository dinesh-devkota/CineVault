package com.dineshdevkota.cinevault.core

import java.util.Locale

enum class MediaKind(val label: String) { MOVIE("Movies"), TV_SHOW("TV Shows"), DOCUMENTARY("Documentaries"), MUSIC("Music") }

/** Library policy for one parent folder containing videos and audio. */
object MediaFile {
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "opus", "wma")
    private fun parts(path: String) = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    private fun extension(path: String) = parts(path).lastOrNull()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    fun isAudio(path: String): Boolean = extension(path) in audio
    fun kind(path: String, genres: List<String> = emptyList(), matched: Boolean = false,
             isEpisode: Boolean = EpisodeParser.parse(path) != null): MediaKind = when {
        isAudio(path) -> MediaKind.MUSIC
        isEpisode -> MediaKind.TV_SHOW
        matched && genres.any { it.equals("Documentary", true) } -> MediaKind.DOCUMENTARY
        matched && genres.any { it.equals("Music", true) } -> MediaKind.MUSIC
        matched -> MediaKind.MOVIE
        parts(path).dropLast(1).any { it.equals("documentary", true) || it.equals("documentaries", true) } -> MediaKind.DOCUMENTARY
        else -> MediaKind.MOVIE
    }
    fun shouldSkipDirectory(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.ROOT)
        return lower.firstOrNull() in setOf('.', '@', '#', '$') ||
            lower in setOf("system volume information", "lost+found", "recycler", "recycle.bin")
    }
    fun shouldIndex(path: String, size: Long): Boolean {
        if (size <= 0 || parts(path).any(::shouldSkipDirectory)) return false
        return extension(path) in audio || VideoFile.shouldIndex(path.replace('\\', '/'), size, minimumSize = 1)
    }
}
