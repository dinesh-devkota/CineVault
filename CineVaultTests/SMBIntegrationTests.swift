import AVFoundation
import Foundation
import XCTest
@testable import CineVault

final class SMBIntegrationTests: XCTestCase {
    func testSetupListsSharesAndDirectChildFolders() async throws {
        let config = configuration()
        let service = SMBService(timeout: 5)
        let shares = try await service.listShares(config)
        XCTAssertTrue(shares.contains("CINEVAULT"))
        XCTAssertFalse(shares.contains("IPC$"))
        let folders = try await service.listFolders(config)
        XCTAssertTrue(folders.contains("Feature (2020)"))
        XCTAssertFalse(folders.contains("Too Small.mp4"))
        await service.reset(config)
    }

    @MainActor
    func testMediaScanReportsIncrementalFilesAndIncludesShortVideos() async throws {
        let config = configuration()
        let service = SMBService(timeout: 5)
        var reported: [String] = []
        let files = try await service.scanMedia(config) { progress in
            reported.append(contentsOf: progress.files.map(\.path))
        }
        XCTAssertEqual(Set(files.map(\.path)), Set(reported))
        XCTAssertTrue(files.contains { $0.path.hasSuffix("Too Small.mp4") })
        XCTAssertTrue(files.contains { $0.path.hasSuffix("Feature (2020).mp4") })
        XCTAssertFalse(files.contains { $0.path.contains("Extras/") || $0.path.contains("@eaDir/") })
        await service.reset(config)
    }

    @MainActor
    func testMediaScanChecksTransportStreamsAndSkipsIgnoredFoldersBeforeTraversal() async throws {
        let service = SMBService(timeout: 5)
        let baseline = configuration(rootPath: "MediaChecks")
        let files = try await service.scanMedia(baseline) { _ in }
        XCTAssertEqual(Set(files.map { ($0.path as NSString).lastPathComponent }), ["clip.ts", "Keep.mp4", "Hidden.mp4"])
        await service.reset(baseline)

        let ignored = configuration(rootPath: "MediaChecks", ignoredFolders: ["Ignored"], ignoredFiles: ["Keep.mp4"])
        var reported: [String] = []
        var visitedFolders = 0
        let filtered = try await service.scanMedia(ignored) { progress in
            reported.append(contentsOf: progress.files.map(\.path))
            visitedFolders = progress.folders
        }
        XCTAssertEqual(filtered.map { ($0.path as NSString).lastPathComponent }, ["clip.ts"])
        XCTAssertEqual(reported, filtered.map(\.path))
        XCTAssertEqual(visitedFolders, 1, "Ignored and node_modules folders must not be traversed.")
        let entries = try await service.listEntries(ignored)
        XCTAssertTrue(entries.contains { $0.name == "Ignored" && $0.isDirectory })
        XCTAssertTrue(entries.contains { $0.name == "Keep.mp4" && !$0.isDirectory })
        await service.reset(ignored)
    }

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

    private func configuration(password: String = "fixture-only", rootPath: String = "Movies",
                               ignoredFolders: [String] = [], ignoredFiles: [String] = []) -> SourceConfig {
        SourceConfig(id: UUID(), host: "127.0.0.1", port: 1445, share: "CINEVAULT",
                     rootPath: rootPath, username: "cinevault", password: password,
                     ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles)
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
        let prepared = try await PlaybackController.prepare(
            movie: staleMovie, resume: false, services: PlaybackServices(), sourceConfig: { _ in config }
        )
        let controller = try XCTUnwrap(prepared)
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
    func testResetLetsAcquiredReadFinishBeforeRejectingStaleResult() async throws {
        for resetBySourceID in [false, true] {
            let config = configuration()
            let service = SMBService(timeout: 3)
            let gate = ReadGate()
            let acquired = expectation(description: "Read acquired the cached connection")
            let completed = expectation(description: "Acquired read finishes on its retained connection")
            let read = Task {
                try await service.withManager(config) { manager in
                    if await gate.nextAttempt() == 1 {
                        acquired.fulfill()
                        await gate.wait()
                    }
                    let bytes = try await manager.contents(
                        atPath: "Movies/Feature (2020)/bytes.bin",
                        range: UInt64(3)..<UInt64(7), progress: nil
                    )
                    XCTAssertEqual(bytes, Data("3456".utf8), "Reset must leave the acquired connection usable until its read finishes.")
                    completed.fulfill()
                    return bytes
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
            do {
                _ = try await read.value
                XCTFail("The completed read belongs to the previous source generation.")
            } catch {
                XCTAssertEqual(error as? SMBError, .sourceChanged)
            }
            await fulfillment(of: [completed], timeout: 15)
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

    @MainActor
    func testResetDuringFailedOperationDoesNotReconnectStaleSource() async throws {
        let config = configuration()
        let gate = ReadGate()
        let operationStarted = expectation(description: "Operation is using the original connection")
        let service = SMBService(timeout: 3) { manager, share in
            _ = await gate.nextAttempt()
            try await manager.connectShare(name: share)
        }
        let operation = Task {
            try await service.withManager(config) { _ -> Data in
                operationStarted.fulfill()
                await gate.wait()
                throw ForcedFailure.expected
            }
        }
        defer { Task { await gate.release() } }
        await fulfillment(of: [operationStarted], timeout: 15)

        await service.reset(sourceID: config.id)
        await gate.release()
        do {
            _ = try await operation.value
            XCTFail("An operation invalidated by a source reset must fail.")
        } catch {
            XCTAssertEqual(error as? SMBError, .sourceChanged)
        }
        let staleAttempts = await gate.attempts
        XCTAssertEqual(staleAttempts, 1, "A failed operation must not reconnect the old source after reset.")

        let size = try await service.fileSize(config, path: "Movies/Feature (2020)/bytes.bin")
        XCTAssertEqual(size, 10)
        let attempts = await gate.attempts
        XCTAssertEqual(attempts, 2, "The next operation must establish its own connection.")
        await service.reset(config)
    }

    @MainActor
    func testResetDuringSuccessfulOperationRejectsStaleResult() async throws {
        let config = configuration()
        let service = SMBService(timeout: 3)
        let gate = ReadGate()
        let operationStarted = expectation(description: "Read has completed before the source reset")
        let operation = Task {
            try await service.withManager(config) { manager in
                let bytes = try await manager.contents(
                    atPath: "Movies/Feature (2020)/bytes.bin",
                    range: UInt64(3)..<UInt64(7), progress: nil
                )
                operationStarted.fulfill()
                await gate.wait()
                return bytes
            }
        }
        defer { Task { await gate.release() } }
        await fulfillment(of: [operationStarted], timeout: 15)

        await service.reset(sourceID: config.id)
        await gate.release()
        do {
            _ = try await operation.value
            XCTFail("A result from the previous source generation must not be returned.")
        } catch {
            XCTAssertEqual(error as? SMBError, .sourceChanged)
        }
        await service.reset(config)
    }

    func testInvalidByteRangesAreRejectedBeforeReading() async throws {
        let config = configuration()
        let service = SMBService(timeout: 3)
        for (offset, length) in [(Int64(-1), 1), (Int64(0), -1), (Int64.max, 1)] {
            do {
                _ = try await service.read(config, path: "Movies/Feature (2020)/bytes.bin", offset: offset, length: length)
                XCTFail("An invalid byte range must not be read.")
            } catch {
                XCTAssertEqual(error as? SMBError, .invalidByteRange)
            }
        }
        let empty = try await service.read(config, path: "does-not-exist", offset: 0, length: 0)
        XCTAssertTrue(empty.isEmpty)

        let loader = SMBResourceLoader(config: config, path: "Movies/Feature (2020)/bytes.bin")
        for (start, end) in [(Int64(-1), Int64(0)), (Int64(10), Int64(9))] {
            do {
                try await loader.streamBytes(from: start, to: end) { _ in XCTFail("Invalid ranges must not produce data.") }
                XCTFail("An invalid stream range must fail.")
            } catch {
                XCTAssertEqual(error as? SMBError, .invalidByteRange)
            }
        }
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
