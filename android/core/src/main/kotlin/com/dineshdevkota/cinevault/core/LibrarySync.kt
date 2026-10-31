package com.dineshdevkota.cinevault.core

/** A video file found on the NAS. [path] is relative to the share and uses "/" separators. */
data class RemoteFile(val path: String, val size: Long) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** How a scan changes the library: rows whose file is still there, new files, and rows whose file vanished. */
data class ScanDiff<T>(
    val present: List<Pair<T, RemoteFile>>,
    val added: List<RemoteFile>,
    val missing: List<T>,
)

object LibrarySync {
    fun <T> diff(existing: Collection<T>, files: List<RemoteFile>, pathOf: (T) -> String): ScanDiff<T> {
        val byPath = LinkedHashMap<String, T>()
        for (item in existing) byPath.putIfAbsent(pathOf(item), item)
        val seen = HashSet<String>()
        val present = mutableListOf<Pair<T, RemoteFile>>()
        val added = mutableListOf<RemoteFile>()
        for (file in files) {
            if (!seen.add(file.path)) continue
            val item = byPath.remove(file.path)
            if (item != null) present += item to file else added += file
        }
        return ScanDiff(present, added, byPath.values.toList())
    }

    /** Title for a newly found file: the parsed title, or the file name when parsing leaves nothing. */
    fun initialTitle(file: RemoteFile, parsed: ParsedMovieName): String = parsed.title.ifEmpty { file.fileName }
}
