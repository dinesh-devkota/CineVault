import AVFoundation
import XCTest
@testable import CineVault

@MainActor
final class PlaybackControllerTests: XCTestCase {
    func testUnplayableAssetDoesNotChangeViewingHistory() async throws {
        let movie = makeMovieWithHistory()
        do {
            _ = try await PlaybackController.prepare(movie: movie, resume: false, services: PlaybackServices(),
                                                     loadPlayable: { _ in false })
            XCTFail("An unplayable asset must not create a playback controller")
        } catch PlaybackPreparationError.unsupportedFormat {
            assertOriginalHistory(movie)
        }
    }

    func testChangedSourceOrFileRejectsPreparedAssetWithoutChangingHistory() async throws {
        let mutations: [(Movie) -> Void] = [
            { $0.source?.host = "changed.invalid" },
            { $0.source?.rootPath = "Other" },
            { $0.filePath = "Other.mp4" },
            { $0.source?.ignoredFiles = ["Movie.mp4"] }
        ]
        for mutate in mutations {
            let movie = makeMovieWithHistory()
            do {
                _ = try await PlaybackController.prepare(movie: movie, resume: false, services: PlaybackServices(),
                                                         loadPlayable: { _ in mutate(movie); return true })
                XCTFail("A prepared asset must still match the current source and file")
            } catch PlaybackPreparationError.fileUnavailable {
                assertOriginalHistory(movie)
            }
        }
    }

    func testCancelledLoadDiscardsLatePlayableResultWithoutChangingHistory() async throws {
        let movie = makeMovieWithHistory()
        let preparation = Task { @MainActor in
            try await PlaybackController.prepare(movie: movie, resume: false, services: PlaybackServices(),
                                                  loadPlayable: { _ in
                withUnsafeCurrentTask { $0?.cancel() }
                return true
            })
        }
        do {
            _ = try await preparation.value
            XCTFail("A cancelled load must not create a playback controller")
        } catch is CancellationError {
            assertOriginalHistory(movie)
        }
    }

    func testTypeScriptDeclarationCannotReachAssetLoading() async throws {
        let movie = makeMovieWithHistory()
        movie.filePath = "fetch.d.ts"
        XCTAssertFalse(PlaybackController.isNativeCandidate(movie))
        do {
            _ = try await PlaybackController.prepare(movie: movie, resume: false, services: PlaybackServices(),
                                                     loadPlayable: { _ in
                XCTFail("A declaration file must be rejected before loading an asset")
                return true
            })
            XCTFail("A declaration file must not create a playback controller")
        } catch PlaybackPreparationError.unsupportedFormat {
            assertOriginalHistory(movie)
        }
    }

    func testPlaybackMetadataIdentifiesEpisodeAndUsesAvailableDetails() throws {
        let movie = makeMovie()
        movie.filePath = "TV Shows/Example Show (2020)/Season 02/Example.S02E03.mp4"
        movie.setTitle("The Return")
        movie.year = 2021
        movie.genres = ["Drama"]
        movie.runtimeMinutes = 42
        movie.overview = "A journey home."
        let metadata = PlaybackController.metadata(for: movie)
        XCTAssertEqual(metadata.first { $0.identifier == .commonIdentifierTitle }?.stringValue, "The Return")
        let subtitle = try XCTUnwrap(metadata.first { $0.identifier == .iTunesMetadataTrackSubTitle }?.stringValue)
        XCTAssertTrue(subtitle.contains("Example Show · Season 2 · Episode 3"))
        XCTAssertTrue(subtitle.contains("2021 · Drama · 42 min"))
        XCTAssertEqual(metadata.first { $0.identifier == .commonIdentifierDescription }?.stringValue, "A journey home.")
    }

    func testPlaybackWithoutOnlineMetadataStillHasFileName() {
        let movie = makeMovie()
        let metadata = PlaybackController.metadata(for: movie)
        XCTAssertEqual(metadata.first { $0.identifier == .commonIdentifierTitle }?.stringValue, "Movie.mp4")
    }

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

    private func makeMovieWithHistory() -> Movie {
        let movie = makeMovie()
        movie.playbackPosition = 120
        movie.duration = 3600
        movie.isWatched = true
        movie.lastPlayedAt = Date(timeIntervalSince1970: 1234)
        return movie
    }

    private func assertOriginalHistory(_ movie: Movie, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(movie.playbackPosition, 120, file: file, line: line)
        XCTAssertEqual(movie.duration, 3600, file: file, line: line)
        XCTAssertTrue(movie.isWatched, file: file, line: line)
        XCTAssertEqual(movie.lastPlayedAt, Date(timeIntervalSince1970: 1234), file: file, line: line)
    }
}
