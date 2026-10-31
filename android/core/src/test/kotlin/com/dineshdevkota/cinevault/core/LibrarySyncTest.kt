package com.dineshdevkota.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySyncTest {
    private data class Row(val id: Int, val path: String)

    @Test
    fun splitsPresentAddedAndMissing() {
        val rows = listOf(Row(1, "Movies/A (2001).mkv"), Row(2, "Movies/B (2002).mp4"))
        val files = listOf(RemoteFile("Movies/B (2002).mp4", 10), RemoteFile("Movies/C (2003).mkv", 20))

        val diff = LibrarySync.diff(rows, files) { it.path }

        assertEquals(listOf(Row(2, "Movies/B (2002).mp4") to files[0]), diff.present)
        assertEquals(listOf(files[1]), diff.added)
        assertEquals(listOf(Row(1, "Movies/A (2001).mkv")), diff.missing)
    }

    @Test
    fun ignoresDuplicateFilesAndRows() {
        val rows = listOf(Row(1, "a.mkv"), Row(2, "a.mkv"))
        val files = listOf(RemoteFile("a.mkv", 1), RemoteFile("a.mkv", 1), RemoteFile("b.mkv", 2), RemoteFile("b.mkv", 2))

        val diff = LibrarySync.diff(rows, files) { it.path }

        assertEquals(listOf(Row(1, "a.mkv") to files[0]), diff.present)
        assertEquals(listOf(RemoteFile("b.mkv", 2)), diff.added)
        assertEquals(emptyList<Row>(), diff.missing)
    }

    @Test
    fun initialTitleFallsBackToFileName() {
        val file = RemoteFile("Movies/Extras/1080p.mkv", 1)
        assertEquals("1080p.mkv", file.fileName)
        assertEquals("1080p.mkv", LibrarySync.initialTitle(file, ParsedMovieName("", null, null)))
        assertEquals("Heat", LibrarySync.initialTitle(file, ParsedMovieName("Heat", 1995, null)))
    }
}
