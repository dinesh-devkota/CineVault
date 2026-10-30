import XCTest
@testable import CineVault

#if !CINEVAULT_COMMERCIAL
@MainActor
final class PlaybackServicesTests: XCTestCase {
    func testPublicBuildPreservesLibraryResumeBehavior() {
        let services = PlaybackServices()
        let source = MediaSource(name: "Test", host: "test.invalid",
                                 share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Movie.mp4", fileSize: 1, source: source)
        XCTAssertFalse(services.canResume(movie))
        movie.playbackPosition = 120
        XCTAssertTrue(services.canResume(movie))
        XCTAssertEqual(services.resumePosition(movie), 120)
        movie.isWatched = true
        XCTAssertFalse(services.canResume(movie))
    }

    func testPublicPlaybackReportsMissingSourceWithoutStarting() {
        let source = MediaSource(name: "Test", host: "test.invalid",
                                 share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Movie.mp4", fileSize: 1, source: source)
        movie.source = nil
        XCTAssertNil(PlaybackController(movie: movie, resume: false, services: PlaybackServices()))
    }
}
#endif
