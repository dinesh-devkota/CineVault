package com.dineshdevkota.cinevault.core

/** Decides which files on a share are movies and whether ExoPlayer can open them. */
object VideoFile {
    /** Containers ExoPlayer extracts natively (MP4/MOV, Matroska/WebM, AVI, MPEG-TS/PS, FLV). */
    val playableExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "ts", "m2ts", "mpg", "mpeg", "flv")

    /** Indexed so the library is complete, but not playable on Android TV. */
    val otherExtensions = setOf("wmv", "iso")

    fun isVideo(path: String): Boolean {
        val ext = Paths.extension(path)
        return ext in playableExtensions || ext in otherExtensions
    }

    fun isPlayable(path: String): Boolean = Paths.extension(path) in playableExtensions

    /** Skips samples, trailers, extras and NAS metadata folders/files. */
    fun shouldIndex(path: String, size: Long, minimumSize: Long = 50L * 1024 * 1024): Boolean {
        if (!isVideo(path) || size < minimumSize) return false
        val parts = path.lowercase().split('/').filter { it.isNotEmpty() }
        if (parts.any { shouldSkipDirectory(it) }) return false
        val stem = Paths.stem(parts.lastOrNull() ?: "")
        if (stem.startsWith("._")) return false
        val markers = listOf("sample", "trailer", "featurette", "behindthescenes", "deleted", "extra")
        return markers.none { marker ->
            stem == marker || stem.endsWith("-$marker") || stem.endsWith(".$marker") ||
                stem.endsWith("_$marker") || stem.endsWith(" $marker")
        }
    }

    fun shouldSkipDirectory(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith(".") || lower.startsWith("@") || lower.startsWith("#") || lower.startsWith("$")) return true
        return lower in setOf(
            "sample", "samples", "extras", "featurettes", "trailers", "behind the scenes", "deleted scenes",
            "lost+found", "recycle.bin", "system volume information",
        )
    }
}
