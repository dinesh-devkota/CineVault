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
}
