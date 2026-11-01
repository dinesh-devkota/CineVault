package com.dineshdevkota.cinevault.core

/** The Edit screen's title and year, checked before saving. */
sealed interface TitleEdit {
    data class Valid(val title: String, val year: Int?) : TitleEdit
    data class Invalid(val message: String) : TitleEdit
}

object MovieEdits {
    const val MIN_YEAR = 1870
    const val MAX_YEAR = 2100

    /** A blank year clears it; anything else must be a plausible release year. */
    fun titleEdit(title: String, yearText: String): TitleEdit {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return TitleEdit.Invalid("Enter a title.")
        val cleanYear = yearText.trim()
        if (cleanYear.isEmpty()) return TitleEdit.Valid(cleanTitle, null)
        val year = cleanYear.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
            ?: return TitleEdit.Invalid("Year must be from $MIN_YEAR to $MAX_YEAR, or left empty.")
        return TitleEdit.Valid(cleanTitle, year)
    }

    /** What a fresh scan shows for [path]: the parsed title (or file name), year, and any {tmdb-…} tag. */
    fun fromFile(path: String): ParsedMovieName {
        val parsed = FilenameParser.parse(path)
        return parsed.copy(title = LibrarySync.initialTitle(RemoteFile(path, 0), parsed))
    }
}
