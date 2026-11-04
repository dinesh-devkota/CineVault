import XCTest
@testable import CineVaultCore

final class MediaFileTests: XCTestCase {
    func testSeparatesNestedCollectionsAndPrefersAudio() {
        XCTAssertEqual(MediaFile.kind(path: "Media/Movies/Film.mp4"), .movie)
        XCTAssertEqual(MediaFile.kind(path: "Media/DoCuMeNtArIeS/Nature/Film.MP4"), .documentary)
        XCTAssertEqual(MediaFile.kind(path: "Media\\DOCUMENTARY\\Film.mp4"), .documentary)
        XCTAssertEqual(MediaFile.kind(path: "Documentaries/Soundtracks/Track.FLAC"), .music)
        XCTAssertEqual(MediaFile.kind(path: "Documentary Films/Film.mp4"), .movie)
    }
    func testIndexesShortVideosAndAudioWithoutAdmittingEmptyOrUnsupportedFiles() {
        XCTAssertTrue(MediaFile.shouldIndex(path: "Movies/Short.mp4", size: 100))
        XCTAssertTrue(MediaFile.shouldIndex(path: "Music/Track.mp3", size: 100))
        XCTAssertFalse(MediaFile.shouldIndex(path: "Movies/Empty.mp4", size: 0))
        XCTAssertFalse(MediaFile.shouldIndex(path: "Music/Cover.jpg", size: 100))
    }
    func testMusicTitlesDoNotUseMovieExtrasExclusions() {
        XCTAssertTrue(MediaFile.shouldIndex(path: "Music/Sample/Extras/sample.mp3", size: 100))
        XCTAssertFalse(MediaFile.shouldIndex(path: "Movies/Samples/Film.mp4", size: 100))
        XCTAssertFalse(MediaFile.shouldIndex(path: "Movies/Film-trailer.mp4", size: 100))
    }
    func testIgnoresNasMetadataAndSystemFolders() {
        for folder in [".cache", "@eaDir", "#recycle", "$RECYCLE.BIN", "System Volume Information"] {
            XCTAssertFalse(MediaFile.shouldIndex(path: "Media/\(folder)/Track.mp3", size: 100))
            XCTAssertTrue(MediaFile.shouldSkipDirectory(name: folder))
        }
        XCTAssertFalse(MediaFile.shouldIndex(path: "Music/._Track.mp3", size: 100))
    }
    func testAudioPlaybackTypes() {
        XCTAssertTrue(MediaFile.isPlayable(path: "Music/Track.mp3"))
        XCTAssertTrue(MediaFile.isPlayable(path: "Music/Track.flac"))
        XCTAssertFalse(MediaFile.isPlayable(path: "Music/Track.wma"))
        XCTAssertNotEqual(MediaFile.contentType(path: "Music/Track.mp3"), "public.mpeg-4")
    }
}
