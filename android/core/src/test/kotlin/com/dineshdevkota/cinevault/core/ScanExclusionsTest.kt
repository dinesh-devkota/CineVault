package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class ScanExclusionsTest {
    @Test fun canonicalizesRelativeFoldersWithoutChangingDisplayCase() {
        assertEquals("Extras/Trailers", ScanExclusions.normalizeFolder("  /Extras\\Trailers//  "))
        assertEquals("Featurettes/Behind the Scenes", ScanExclusions.normalizeFolder(" Featurettes / Behind the Scenes / "))
        assertEquals("Éxtras/Trailers", ScanExclusions.normalizeFolder(" /Éxtras//Trailers/ "))
        assertEquals(".hidden", ScanExclusions.normalizeFolder(".hidden"))
    }

    @Test fun rejectsTraversalAbsoluteLocationsAndRootWideRules() {
        for (path in listOf("", "  ", "/", "\\", ".", "./Extras", "Extras/../Movies", "..\\Movies",
            "smb://nas/share", "file:///Media", "C:\\Media", "\\\\nas\\share", "//nas/share",
            "*", "**", "**/*", "?", "Extras/A\u0000B", "Extras/A\nB")) {
            assertNull(path, ScanExclusions.normalizeFolder(path))
        }
    }

    @Test fun matchesOnlyTheExactDirectoryAndItsDescendants() {
        val rules = listOf("Extras")
        for (path in listOf("Media/Movies/Extras", "media\\movies\\EXTRAS\\Deep", " /MEDIA/Movies/Extras/ ")) {
            assertTrue(path, ScanExclusions.isDirectoryExcluded(path, " /Media\\Movies/ ", rules))
        }
        for (path in listOf("Media/Movies", "Media/Movies/Extras 2", "Media/Movies/My Extras", "Media/Movies2/Extras", "Other/Extras")) {
            assertFalse(path, ScanExclusions.isDirectoryExcluded(path, "Media/Movies", rules))
        }
    }

    @Test fun nestedRulesAreRelativeToTheSelectedRoot() {
        val rules = listOf("Bonus/Trailers")
        assertTrue(ScanExclusions.isDirectoryExcluded("Media/Bonus/Trailers", "Media", rules))
        assertTrue(ScanExclusions.isFileExcluded("Media/Bonus/Trailers/Deep/clip.mp4", "Media", rules))
        assertFalse(ScanExclusions.isFileExcluded("Media/Other/Bonus/Trailers/clip.mp4", "Media", rules))
        assertFalse(ScanExclusions.isFileExcluded("Media/Bonus/clip.mp4", "Media", rules))
        assertFalse(ScanExclusions.isFileExcluded("Media/Bonus/Trailers 2/clip.mp4", "Media", rules))
        assertFalse(ScanExclusions.isFileExcluded("Media/Bonus/Trailers/clip.mp4", "Media", listOf("Media/Bonus/Trailers")))
    }

    @Test fun fileFallbackMatchesParentsNotFileNames() {
        assertTrue(ScanExclusions.isFileExcluded("Movies/Extras/clip.mp4", "Movies", listOf("Extras")))
        assertFalse(ScanExclusions.isFileExcluded("Movies/Extras", "Movies", listOf("Extras")))
        assertFalse(ScanExclusions.isFileExcluded("Movies/Extras.mp4", "Movies", listOf("Extras")))
        assertTrue(ScanExclusions.isFileExcluded("Extras/clip.mp4", "", listOf("Extras")))
        assertTrue(ScanExclusions.isFileExcluded("Extras/clip.mp4", "/", listOf("Extras")))
        assertFalse(ScanExclusions.isDirectoryExcluded("", "", listOf("Extras")))
    }

    @Test fun wildcardCharactersAreLiteralAndInvalidRulesNeverMatch() {
        assertEquals("Extras*", ScanExclusions.normalizeFolder("Extras*"))
        assertFalse(ScanExclusions.isFileExcluded("Extras 2/clip.mp4", "", listOf("Extras*")))
        assertFalse(ScanExclusions.isFileExcluded("Extras/clip.mp4", "", listOf("Extras*")))
        assertTrue(ScanExclusions.isFileExcluded("Extras*/clip.mp4", "", listOf("Extras*")))
        assertTrue(ScanExclusions.isFileExcluded("[Bonus]/clip.mp4", "", listOf("[Bonus]")))
        assertFalse(ScanExclusions.isFileExcluded("Bonus/clip.mp4", "", listOf("[Bonus]")))
        assertFalse(ScanExclusions.isFileExcluded("Extras/clip.mp4", "", listOf("", "/", "..", "**/*")))
        assertFalse(ScanExclusions.isFileExcluded("Media/Extras/clip.mp4", "../Media", listOf("Extras")))
        assertFalse(ScanExclusions.isFileExcluded("Media/../Extras/clip.mp4", "Media", listOf("Extras")))
    }

    @Test fun removingRulesImmediatelyIncludesPreviouslyIgnoredPaths() {
        assertTrue(ScanExclusions.isFileExcluded("Media/Extras/clip.mp4", "Media", listOf("Extras")))
        assertFalse(ScanExclusions.isFileExcluded("Media/Extras/clip.mp4", "Media", emptyList()))
    }

    @Test fun exactFileRulesDoNotHideSiblingsOrDescendants() {
        assertEquals("Season 1/Clip.mp4", ScanExclusions.normalizeFile(" /Season 1\\Clip.mp4/ "))
        assertNull(ScanExclusions.normalizeFile("../Clip.mp4"))
        val rules = listOf("Season 1/Clip.mp4")
        assertTrue(ScanExclusions.isFileExcluded("Media/Season 1/Clip.mp4", "Media", emptyList(), rules))
        assertTrue(ScanExclusions.isFileExcluded("MEDIA\\season 1\\CLIP.MP4", " /media/ ", emptyList(), rules))
        for (path in listOf("Media/Season 1/Clip2.mp4", "Media/Season 1/Clip.mp4.bak", "Media/Season 2/Clip.mp4",
            "Media/Season 1/Clip.mp4/child.mp4", "Other/Season 1/Clip.mp4")) {
            assertFalse(path, ScanExclusions.isFileExcluded(path, "Media", emptyList(), rules))
        }
        assertTrue(ScanExclusions.isFileExcluded("Media/Bonus/Deep/anything.mp4", "Media", listOf("Bonus"), rules))
        assertFalse(ScanExclusions.isFileExcluded("Media/Season 1/Clip.mp4", "Media", emptyList(), emptyList()))
    }
}
