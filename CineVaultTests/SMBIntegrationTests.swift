import AVFoundation
import Foundation
import XCTest
@testable import CineVault

final class SMBIntegrationTests: XCTestCase {
    private actor ReadGate {
        private(set) var attempts = 0
        private var released = false
        private var continuation: CheckedContinuation<Void, Never>?

        func nextAttempt() -> Int {
            attempts += 1
            return attempts
        }

        func wait() async {
            guard !released else { return }
            await withCheckedContinuation { continuation = $0 }
        }

        func release() {
            released = true
            continuation?.resume()
            continuation = nil
        }
    }

    private enum ForcedFailure: Error {
        case expected
    }

    private func configuration(password: String = "fixture-only") -> SourceConfig {
        SourceConfig(id: UUID(), host: "127.0.0.1", port: 1445, share: "CINEVAULT",
                     rootPath: "Movies", username: "cinevault", password: password)
    }

    func testScanFindsNestedMoviesAndSkipsExtras() async throws {
        let config = configuration()
        let files = try await SMBService.shared.scanVideos(config)

        XCTAssertEqual(files.count, 1)
        let movie = try XCTUnwrap(files.first)
        XCTAssertEqual(movie.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")),
                       "Movies/Feature (2020)/Feature (2020).mp4")
        XCTAssertEqual(movie.size, 60 * 1024 * 1024)
        let bytes = try await SMBService.shared.read(config, path: movie.path, offset: 4, length: 4)
        XCTAssertEqual(bytes, Data("ftyp".utf8))
        await SMBService.shared.reset(config)
    }

    @MainActor
    func testPlaybackAndSeekStreamTheScannedMovie() async throws {
        let config = configuration()
        let files = try await SMBService.shared.scanVideos(config)
        let movie = try XCTUnwrap(files.first)
        let source = MediaSource(name: "Fixture", host: config.host, port: config.port, share: config.share,
                                 rootPath: config.rootPath, username: config.username)
        source.id = config.id
        let staleMovie = Movie(filePath: movie.path, fileSize: 1, source: source)
        let controller = try XCTUnwrap(PlaybackController(
            movie: staleMovie, resume: false, services: PlaybackServices(), sourceConfig: { _ in config }
        ))
        let player = controller.player
        defer {
            controller.stop()
            player.replaceCurrentItem(with: nil)
        }

        player.play()
        try await waitForPlayback(player, past: 0.5)

        let seekFinished = expectation(description: "Seek completes")
        player.seek(to: CMTime(seconds: 8, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero) { finished in
            XCTAssertTrue(finished, "The player could not seek in the SMB movie.")
            seekFinished.fulfill()
        }
        await fulfillment(of: [seekFinished], timeout: 30)
        player.play()
        try await waitForPlayback(player, past: 8.5)

        player.pause()
        player.replaceCurrentItem(with: nil)
        await SMBService.shared.reset(config)
    }

    @MainActor
    private func waitForPlayback(_ player: AVPlayer, past target: Double) async throws {
        let deadline = Date().addingTimeInterval(40)
        while Date() < deadline {
            let position = player.currentTime().seconds
            if position.isFinite && position > target { return }
            if let error = player.currentItem?.error ?? player.error { throw error }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        throw NSError(
            domain: "SMBPlaybackTest",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey:
                "Playback did not reach \(target) seconds; position \(player.currentTime().seconds), " +
                "item status \(String(describing: player.currentItem?.status)), " +
                "player status \(player.timeControlStatus.rawValue)."]
        )
    }

    func testReadReturnsTheRequestedByteRange() async throws {
        let config = configuration()
        let bytes = try await SMBService.shared.read(
            config, path: "Movies/Feature (2020)/bytes.bin", offset: 3, length: 4
        )

        XCTAssertEqual(bytes, Data("3456".utf8))
        await SMBService.shared.reset(config)
    }

    @MainActor
    func testFailedOperationDoesNotDisconnectConcurrentRead() async throws {
        let config = configuration()
        let service = SMBService(timeout: 3)
        let gate = ReadGate()
        let readStarted = expectation(description: "Concurrent read has acquired the connection")
        let read = Task {
            try await service.withManager(config) { manager in
                if await gate.nextAttempt() == 1 {
                    readStarted.fulfill()
                    await gate.wait()
                }
                return try await manager.contents(
                    atPath: "Movies/Feature (2020)/bytes.bin",
                    range: UInt64(3)..<UInt64(7), progress: nil
                )
            }
        }
        defer { Task { await gate.release() } }
        await fulfillment(of: [readStarted], timeout: 15)

        do {
            let _: Void = try await service.withManager(config) { _ in
                throw ForcedFailure.expected
            }
            XCTFail("The failing operation must report its error.")
        } catch {
            XCTAssertTrue(error is ForcedFailure)
        }

        await gate.release()
        let bytes = try await read.value
        XCTAssertEqual(bytes, Data("3456".utf8))
        let attempts = await gate.attempts
        XCTAssertEqual(attempts, 1, "The concurrent read should not need to reconnect.")
        await service.reset(config)
    }

    @MainActor
    func testResetDoesNotDisconnectAnAcquiredRead() async throws {
        for resetBySourceID in [false, true] {
            let config = configuration()
            let service = SMBService(timeout: 3)
            let gate = ReadGate()
            let acquired = expectation(description: "Read acquired the cached connection")
            let read = Task {
                try await service.withManager(config) { manager in
                    if await gate.nextAttempt() == 1 {
                        acquired.fulfill()
                        await gate.wait()
                    }
                    return try await manager.contents(
                        atPath: "Movies/Feature (2020)/bytes.bin",
                        range: UInt64(3)..<UInt64(7), progress: nil
                    )
                }
            }
            defer { Task { await gate.release() } }
            await fulfillment(of: [acquired], timeout: 15)

            if resetBySourceID {
                await service.reset(sourceID: config.id)
            } else {
                await service.reset(config)
            }
            // A new request can reconnect while the old request still owns its connection.
            let fresh = try await service.read(
                config, path: "Movies/Feature (2020)/bytes.bin", offset: 0, length: 3
            )
            XCTAssertEqual(fresh, Data("012".utf8))
            await gate.release()
            let bytes = try await read.value
            XCTAssertEqual(bytes, Data("3456".utf8))
            let attempts = await gate.attempts
            XCTAssertEqual(attempts, 1, "Reset must not force an acquired read to reconnect.")
            await service.reset(config)
        }
    }

    @MainActor
    func testResetDuringConnectionDoesNotRestoreStaleManager() async throws {
        let config = configuration()
        let gate = ReadGate()
        let connected = expectation(description: "Connection has completed but is not cached")
        let service = SMBService(timeout: 3) { manager, share in
            try await manager.connectShare(name: share)
            if await gate.nextAttempt() == 1 {
                connected.fulfill()
                await gate.wait()
            }
        }
        let first = Task {
            try await service.fileSize(config, path: "Movies/Feature (2020)/bytes.bin")
        }
        defer { Task { await gate.release() } }
        await fulfillment(of: [connected], timeout: 15)

        await service.reset(sourceID: config.id)
        await gate.release()
        do {
            _ = try await first.value
            XCTFail("A connection invalidated by a source reset must not be cached.")
        } catch {
            XCTAssertEqual(error as? SMBError, .sourceChanged)
        }

        let size = try await service.fileSize(config, path: "Movies/Feature (2020)/bytes.bin")
        XCTAssertEqual(size, 10)
        let attempts = await gate.attempts
        XCTAssertEqual(attempts, 2, "The next request must establish a new connection.")
        await service.reset(config)
    }

    func testTruncatedRangeReportsUnexpectedEnd() async throws {
        let config = configuration()
        let loader = SMBResourceLoader(config: config, path: "Movies/Feature (2020)/bytes.bin")
        var received = Data()
        do {
            try await loader.streamBytes(from: 0, to: 12) { received.append($0) }
            XCTFail("An incomplete byte range must not succeed.")
        } catch {
            XCTAssertEqual(error as? SMBError, .unexpectedEndOfFile)
        }
        XCTAssertEqual(received, Data("0123456789".utf8))
        await SMBService.shared.reset(config)
    }

    func testInvalidPasswordIsReported() async {
        let config = configuration(password: "incorrect")
        let service = SMBService(timeout: 2)
        do {
            try await service.testConnection(config)
            XCTFail("An invalid password must not connect.")
        } catch {
            await service.reset(config)
            return
        }
        await service.reset(config)
    }
}
