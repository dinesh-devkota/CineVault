package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class MediaFileTest {
    @Test fun separatesNestedCollectionsAndPrefersAudio() {
        assertEquals(MediaKind.MOVIE, MediaFile.kind("Media/Movies/Film.mp4"))
        assertEquals(MediaKind.DOCUMENTARY, MediaFile.kind("Media/DoCuMeNtArIeS/Nature/Film.MP4"))
        assertEquals(MediaKind.DOCUMENTARY, MediaFile.kind("Media\\DOCUMENTARY\\Film.mp4"))
        assertEquals(MediaKind.MUSIC, MediaFile.kind("Documentaries/Soundtracks/Track.FLAC"))
        assertEquals(MediaKind.MOVIE, MediaFile.kind("Documentary Films/Film.mp4"))
    }
    @Test fun indexesShortVideosAndAudioWithoutAdmittingEmptyOrUnsupportedFiles() {
        assertTrue(MediaFile.shouldIndex("Movies/Short.mp4", 100))
        assertTrue(MediaFile.shouldIndex("Music/Track.mp3", 100))
        assertFalse(MediaFile.shouldIndex("Movies/Empty.mp4", 0))
        assertFalse(MediaFile.shouldIndex("Music/Cover.jpg", 100))
    }
    @Test fun musicTitlesDoNotUseMovieExtrasExclusions() {
        assertTrue(MediaFile.shouldIndex("Music/Sample/Extras/sample.mp3", 100))
        assertFalse(MediaFile.shouldIndex("Movies/Samples/Film.mp4", 100))
        assertFalse(MediaFile.shouldIndex("Movies/Film-trailer.mp4", 100))
    }
    @Test fun ignoresNasMetadataAndSystemFolders() {
        for (folder in listOf(".cache", "@eaDir", "#recycle", "\$RECYCLE.BIN", "System Volume Information")) {
            assertFalse(folder, MediaFile.shouldIndex("Media/$folder/Track.mp3", 100))
            assertTrue(MediaFile.shouldSkipDirectory(folder))
        }
        assertFalse(MediaFile.shouldIndex("Music/._Track.mp3", 100))
    }

    @Test fun playbackCandidatesMatchTheBundledMedia3Containers() {
        for (ext in listOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "ts", "mpg", "mpeg", "flv",
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")) {
            assertTrue(ext, MediaFile.isPlayable("Media/Title.$ext"))
            assertTrue(ext, MediaFile.isPlayable("Media\\Title.${ext.uppercase()}"))
        }
        for (ext in listOf("wmv", "iso", "m2ts", "wma", "aiff", "aif")) {
            assertTrue(ext, MediaFile.shouldIndex("Media/Title.$ext", 100))
            assertFalse(ext, MediaFile.isPlayable("Media/Title.$ext"))
            assertFalse(ext, VideoFile.isPlayable("Media/Title.$ext"))
        }
        assertFalse(MediaFile.isPlayable("Media/Cover.jpg"))
        assertTrue(MediaFile.isPlayable("Music/Sample/Extras/sample.mp3"))
    }

    @Test fun playbackEntryPointsRejectDeclarationsAndSystemPaths() {
        for (path in listOf("fetch.d.ts", "Project\\FETCH.D.TS", "Project/node_modules/clip.mp4",
            "Project\\NODE_MODULES\\clip.MKV", "Project/.git/clip.mp4", "Media/@eaDir/clip.mp4",
            "Media/._clip.mp4")) {
            assertFalse(path, MediaFile.isPlayable(path))
            assertFalse(path, VideoFile.isPlayable(path))
        }
        assertFalse(MediaFile.isPlayable("Music/node_modules/track.mp3"))
        assertTrue(MediaFile.isPlayable("Movies/node_modules_archive/clip.mp4"))
    }

    @Test fun rejectsDeclarationFilesAndSoftwareDependenciesWithoutRejectingTransportCandidates() {
        for (path in listOf("fetch.d.ts", "Project/FETCH.D.TS", "Project/node_modules/fetch.ts",
            "Project\\NODE_MODULES\\clip.mp4")) {
            assertFalse(path, MediaFile.shouldIndex(path, 10_000))
            assertFalse(path, MediaFile.requiresContentCheck(path))
        }
        assertTrue(MediaFile.shouldSkipDirectory("node_modules"))
        assertFalse(MediaFile.shouldSkipDirectory("node_modules_archive"))
        assertTrue(MediaFile.shouldIndex("Movies/Recording.ts", 10_000))
        assertTrue(MediaFile.requiresContentCheck("Movies/Recording.TS"))
        assertFalse(MediaFile.requiresContentCheck("Movies/Film.mp4"))
        assertFalse(MediaFile.requiresContentCheck("Movies/Film.m2ts"))
    }

    private fun transportPrefix(packetSize: Int, syncOffset: Int, packets: Int = 5): ByteArray =
        ByteArray(packetSize * packets) { 0xff.toByte() }.apply {
            for (packet in 0 until packets) {
                val start = packet * packetSize + syncOffset
                this[start] = 0x47; this[start + 1] = 0x40; this[start + 2] = 0
                this[start + 3] = (0x10 or packet).toByte()
            }
        }

    @Test fun recognizesStandardM2tsAndFecTransportPackets() {
        assertTrue(MediaFile.isTransportStream(transportPrefix(188, 0)))
        assertTrue(MediaFile.isTransportStream(transportPrefix(192, 4)))
        assertTrue(MediaFile.isTransportStream(transportPrefix(204, 0)))
        assertTrue(MediaFile.isTransportStream(transportPrefix(188, 0, packets = 3)))
    }

    @Test fun playbackRequiresTheTransportPacketLayoutSupportedByMedia3() {
        assertTrue(MediaFile.isPlayableTransportStream(transportPrefix(188, 0)))
        assertFalse(MediaFile.isPlayableTransportStream(transportPrefix(192, 4)))
        assertFalse(MediaFile.isPlayableTransportStream(transportPrefix(204, 0)))
        assertFalse(MediaFile.isPlayableTransportStream(transportPrefix(188, 0, packets = 3)))
        assertFalse(MediaFile.isPlayableTransportStream(transportPrefix(188, 0).apply { this[188 * 2] = 0 }))
        assertFalse(MediaFile.isPlayableTransportStream("export declare const value: string;".repeat(40).toByteArray()))
    }

    @Test fun rejectsSourceCodeShortDataAndInconsistentPacketHeaders() {
        assertFalse(MediaFile.isTransportStream("export declare function fetch(url: string): Promise<Response>;".repeat(20).toByteArray()))
        assertFalse(MediaFile.isTransportStream(byteArrayOf()))
        assertFalse(MediaFile.isTransportStream(transportPrefix(188, 0, packets = 2)))
        assertFalse(MediaFile.isTransportStream(transportPrefix(188, 0).apply { this[188 * 2] = 0 }))
        assertFalse(MediaFile.isTransportStream(transportPrefix(188, 0).apply { this[3] = 0 }))
    }
}
