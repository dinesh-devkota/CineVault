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

    func testPlaybackCandidatesPreserveIndexedUnsupportedMedia() {
        for ext in ["mp4", "m4v", "mov", "mp3", "m4a", "aac", "flac", "wav", "aiff", "aif"] {
            XCTAssertTrue(MediaFile.isPlayable(path: "Media/Title.\(ext)"), ext)
            XCTAssertTrue(MediaFile.isPlayable(path: "Media\\Title.\(ext.uppercased())"), ext)
        }
        for ext in ["mkv", "avi", "wmv", "ts", "m2ts", "webm", "flv", "mpg", "mpeg", "iso", "ogg", "opus", "wma"] {
            XCTAssertTrue(MediaFile.shouldIndex(path: "Media/Title.\(ext)", size: 100), ext)
            XCTAssertFalse(MediaFile.isPlayable(path: "Media/Title.\(ext)"), ext)
            XCTAssertFalse(VideoFile.isNativelyPlayable(path: "Media/Title.\(ext)"), ext)
        }
        XCTAssertFalse(MediaFile.isPlayable(path: "Media/Cover.jpg"))
        XCTAssertTrue(MediaFile.isPlayable(path: "Music/Sample/Extras/sample.mp3"))
    }

    func testPlaybackEntryPointsRejectDeclarationsAndSystemPaths() {
        for path in ["fetch.d.ts", "Project\\FETCH.D.TS", "Project/node_modules/clip.mp4",
                     "Project\\NODE_MODULES\\clip.MOV", "Project/.git/clip.mp4", "Media/@eaDir/clip.mp4",
                     "Media/._clip.mp4"] {
            XCTAssertFalse(MediaFile.isPlayable(path: path), path)
            XCTAssertFalse(VideoFile.isNativelyPlayable(path: path), path)
        }
        XCTAssertFalse(MediaFile.isPlayable(path: "Music/node_modules/track.mp3"))
        XCTAssertTrue(MediaFile.isPlayable(path: "Movies/node_modules_archive/clip.mp4"))
    }

    func testRejectsDeclarationsAndSoftwareDependenciesWithoutRejectingTransportCandidates() {
        for path in ["fetch.d.ts", "Project/FETCH.D.TS", "Project/node_modules/fetch.ts", "Project\\NODE_MODULES\\clip.mp4"] {
            XCTAssertFalse(MediaFile.shouldIndex(path: path, size: 10_000), path)
            XCTAssertFalse(MediaFile.requiresContentCheck(path: path), path)
        }
        XCTAssertTrue(MediaFile.shouldSkipDirectory(name: "node_modules"))
        XCTAssertFalse(MediaFile.shouldSkipDirectory(name: "node_modules_archive"))
        XCTAssertTrue(MediaFile.shouldIndex(path: "Movies/Recording.ts", size: 10_000))
        XCTAssertTrue(MediaFile.requiresContentCheck(path: "Movies/Recording.TS"))
        XCTAssertFalse(MediaFile.requiresContentCheck(path: "Movies/Film.mp4"))
        XCTAssertFalse(MediaFile.requiresContentCheck(path: "Movies/Film.m2ts"))
    }

    private func transportPrefix(packetSize: Int, syncOffset: Int, packets: Int = 5) -> Data {
        var bytes = [UInt8](repeating: 0xff, count: packetSize * packets)
        for packet in 0..<packets {
            let start = packet * packetSize + syncOffset
            bytes[start] = 0x47; bytes[start + 1] = 0x40; bytes[start + 2] = 0
            bytes[start + 3] = UInt8(0x10 | packet)
        }
        return Data(bytes)
    }

    func testRecognizesStandardM2tsAndFecTransportPackets() {
        XCTAssertTrue(MediaFile.isTransportStream(prefix: transportPrefix(packetSize: 188, syncOffset: 0)))
        XCTAssertTrue(MediaFile.isTransportStream(prefix: transportPrefix(packetSize: 192, syncOffset: 4)))
        XCTAssertTrue(MediaFile.isTransportStream(prefix: transportPrefix(packetSize: 204, syncOffset: 0)))
        XCTAssertTrue(MediaFile.isTransportStream(prefix: transportPrefix(packetSize: 188, syncOffset: 0, packets: 3)))
    }

    func testRejectsSourceCodeShortDataAndInconsistentPacketHeaders() {
        let source = String(repeating: "export declare function fetch(url: string): Promise<Response>;", count: 20)
        XCTAssertFalse(MediaFile.isTransportStream(prefix: Data(source.utf8)))
        XCTAssertFalse(MediaFile.isTransportStream(prefix: Data()))
        XCTAssertFalse(MediaFile.isTransportStream(prefix: transportPrefix(packetSize: 188, syncOffset: 0, packets: 2)))
        var badSync = transportPrefix(packetSize: 188, syncOffset: 0)
        badSync[188 * 2] = 0
        XCTAssertFalse(MediaFile.isTransportStream(prefix: badSync))
        var badHeader = transportPrefix(packetSize: 188, syncOffset: 0)
        badHeader[3] = 0
        XCTAssertFalse(MediaFile.isTransportStream(prefix: badHeader))
    }
}
