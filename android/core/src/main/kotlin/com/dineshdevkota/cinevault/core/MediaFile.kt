package com.dineshdevkota.cinevault.core

import java.util.Locale

enum class MediaKind(val label: String) { MOVIE("Movies"), TV_SHOW("TV Shows"), DOCUMENTARY("Documentaries"), MUSIC("Music") }

/** Library policy for one parent folder containing videos and audio. */
object MediaFile {
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "opus", "wma")
    private val playableAudio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    private fun parts(path: String) = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    private fun extension(path: String) = parts(path).lastOrNull()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    private fun isTypeScriptDeclaration(path: String) = parts(path).lastOrNull()?.endsWith(".d.ts", ignoreCase = true) == true
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
            lower in setOf("system volume information", "lost+found", "recycler", "recycle.bin", "node_modules")
    }
    fun shouldIndex(path: String, size: Long): Boolean {
        if (size <= 0 || isTypeScriptDeclaration(path) || parts(path).any(::shouldSkipDirectory)) return false
        return extension(path) in audio || VideoFile.shouldIndex(path.replace('\\', '/'), size, minimumSize = 1)
    }

    internal fun isPlaybackPathAllowed(path: String): Boolean =
        !isTypeScriptDeclaration(path) && !parts(path).any(::shouldSkipDirectory)

    /** Container candidate for Media3; the device must also support the contained codecs.
     * .ts candidates additionally require isPlayableTransportStream before playback. */
    fun isPlayable(path: String): Boolean = isPlaybackPathAllowed(path) &&
        (extension(path) in playableAudio || VideoFile.isPlayable(path))

    /** .ts is also a source-code extension; callers must probe before emitting these candidates. */
    fun requiresContentCheck(path: String): Boolean = extension(path) == "ts" &&
        !isTypeScriptDeclaration(path) && !parts(path).any(::shouldSkipDirectory)

    /** Inspect up to 1024 bytes: at least three complete, consistently aligned transport packets. */
    fun isTransportStream(prefix: ByteArray): Boolean {
        val available = minOf(prefix.size, 1024)
        return listOf(188 to 0, 192 to 4, 204 to 0).any { (packetSize, syncOffset) ->
            val packets = available / packetSize
            packets >= 3 && (0 until packets).all { packet ->
                val start = packet * packetSize + syncOffset
                (prefix[start].toInt() and 0xff) == 0x47 &&
                    (prefix[start + 3].toInt() and 0x30) != 0
            }
        }
    }

    /** Media3 1.8.0 TsExtractor accepts 188-byte packets, not M2TS (192) or FEC (204). */
    fun isPlayableTransportStream(prefix: ByteArray): Boolean {
        val packets = minOf(prefix.size, 1024) / 188
        return packets >= 5 && (0 until packets).all { packet ->
            val start = packet * 188
            (prefix[start].toInt() and 0xff) == 0x47 &&
                (prefix[start + 3].toInt() and 0x30) != 0
        }
    }
}
