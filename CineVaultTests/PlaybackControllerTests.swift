import AVFoundation
import XCTest
@testable import CineVault

@MainActor
final class PlaybackControllerTests: XCTestCase {
    func testReplayingWatchedMovieRestoresResume() throws {
        let services = PlaybackServices()
        let movie = makeMovie()
        movie.isWatched = true
        let controller = try XCTUnwrap(PlaybackController(movie: movie, resume: false, services: services))
        defer { controller.stop() }

        controller.record(time: 120)

        XCTAssertFalse(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, 120)
        XCTAssertTrue(services.canResume(movie))
    }

    func testPlaybackBoundaryDoesNotMarkMovieWatchedOrSavePastLimit() throws {
        let movie = makeMovie()
        let controller = try XCTUnwrap(PlaybackController(movie: movie, resume: false, services: PlaybackServices()))
        defer { controller.stop() }
        let item = try XCTUnwrap(controller.player.currentItem)
        item.forwardPlaybackEndTime = CMTime(seconds: 90, preferredTimescale: 600)

        controller.record(time: 98)
        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)

        XCTAssertFalse(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, 90)
    }

    func testLateProgressDoesNotUndoFinishedState() throws {
        let movie = makeMovie()
        let controller = try XCTUnwrap(PlaybackController(movie: movie, resume: false, services: PlaybackServices()))
        defer { controller.stop() }
        let item = try XCTUnwrap(controller.player.currentItem)

        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        controller.record(time: 60)

        XCTAssertTrue(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, 0)
    }

    func testStoppedPlaybackIgnoresLateProgressAndEndNotifications() throws {
        let movie = makeMovie()
        let controller = try XCTUnwrap(PlaybackController(movie: movie, resume: false, services: PlaybackServices()))
        let item = try XCTUnwrap(controller.player.currentItem)
        controller.record(time: 60)
        controller.stop()
        let positionAtStop = movie.playbackPosition
        let lastPlayedAtStop = movie.lastPlayedAt

        controller.record(time: 120)
        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        controller.stop()

        XCTAssertFalse(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, positionAtStop)
        XCTAssertEqual(movie.lastPlayedAt, lastPlayedAtStop)
    }

    private func makeMovie() -> Movie {
        let source = MediaSource(name: "Test", host: "test.invalid",
                                 share: "Movies", rootPath: "", username: "")
        return Movie(filePath: "Movie.mp4", fileSize: 1, source: source)
    }
}
