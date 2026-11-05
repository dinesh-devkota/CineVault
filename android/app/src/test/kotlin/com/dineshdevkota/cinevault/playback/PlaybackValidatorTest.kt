package com.dineshdevkota.cinevault.playback

import com.dineshdevkota.cinevault.core.MediaFile
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.smb.SmbException
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackValidatorTest {
    private val config = SourceConfig("fixture", "fixture.invalid", null, "Media", "Library", "", "")
    private fun movie(path: String) = MovieEntity(sourceId = config.id, filePath = path,
        fileName = path.substringAfterLast('/'), fileSize = 1024, parsedTitle = "Fixture", parsedYear = null,
        title = "Fixture", year = null)

    @Test fun unsupportedLegacyRowsNeverContactTheNas() = runBlocking {
        for (path in listOf("Library/fetch.d.ts", "Library/node_modules/demo.mp4", "Library/readme.txt")) {
            val failure = runCatching {
                PlaybackValidator.validate(movie(path), config) { _, _ -> error("Unsupported files must not open the NAS") }
            }.exceptionOrNull()
            assertTrue(path, failure is SmbException)
            assertTrue(failure!!.message!!.contains("not supported"))
        }
    }

    @Test fun missingAndIgnoredEntriesCannotBypassPlaybackChecks() = runBlocking {
        val paths = listOf(movie("Library/Private/Feature.mp4"), movie("Library/One.mp4"),
            movie("Library/Missing.mp4").copy(isMissing = true))
        val exclusions = config.copy(ignoredFolders = listOf("Private"), ignoredFiles = listOf("One.mp4"))
        for (entry in paths) {
            assertNotNull(PlaybackValidator.rejectionReason(entry, exclusions))
            assertTrue(runCatching {
                PlaybackValidator.validate(entry, exclusions) { _, _ -> error("Unavailable files must not open the NAS") }
            }.exceptionOrNull() is SmbException)
        }
        assertNull(PlaybackValidator.rejectionReason(movie("Library/Private Collection/Feature.mp4"), exclusions))
    }

    @Test fun supportedUnambiguousContainerDoesNotNeedAHeaderProbe() = runBlocking {
        PlaybackValidator.validate(movie("Library/Feature.mp4"), config) { _, _ -> error("Unexpected header probe") }
    }

    @Test fun transportStreamMustPassContentCheckBeforePlayback() = runBlocking {
        val entry = movie("Library/clip.ts")
        var probes = 0
        val invalid = runCatching {
            PlaybackValidator.validate(entry, config) { captured, path ->
                probes++
                assertEquals(config, captured)
                assertEquals(entry.filePath, path)
                MediaFile.isPlayableTransportStream("export const fixture = true;".toByteArray())
            }
        }.exceptionOrNull()
        assertTrue(invalid is SmbException)
        assertTrue(invalid!!.message!!.contains("not a supported MPEG transport stream"))
        val packets = ByteArray(188 * 5)
        repeat(5) { packets[it * 188] = 0x47; packets[it * 188 + 3] = 0x10 }
        PlaybackValidator.validate(entry, config) { _, _ -> probes++; MediaFile.isPlayableTransportStream(packets) }
        assertEquals(2, probes)
    }

    @Test fun scanRecognizes192BytePacketsButAndroidPlaybackRejectsThem() = runBlocking {
        val packets = ByteArray(192 * 4)
        repeat(4) { packets[it * 192 + 4] = 0x47; packets[it * 192 + 7] = 0x10 }
        assertTrue(MediaFile.isTransportStream(packets))
        val failure = runCatching {
            PlaybackValidator.validate(movie("Library/192-byte.ts"), config) { _, _ ->
                MediaFile.isPlayableTransportStream(packets)
            }
        }.exceptionOrNull()
        assertTrue(failure is SmbException)
        assertTrue(failure!!.message!!.contains("not a supported MPEG transport stream"))
    }
}
