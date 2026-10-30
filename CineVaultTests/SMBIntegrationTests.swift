import AVFoundation
import Foundation
import XCTest
@testable import CineVault

final class SMBIntegrationTests: XCTestCase {
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
        let loader = SMBResourceLoader(config: config, path: movie.path, knownSize: movie.size)
        let player = AVPlayer(playerItem: AVPlayerItem(asset: loader.makeAsset()))
        defer {
            player.pause()
            player.replaceCurrentItem(with: nil)
            withExtendedLifetime(loader) {}
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

    func testTruncatedRangeReportsUnexpectedEnd() async throws {
        let config = configuration()
        let loader = SMBResourceLoader(config: config, path: "Movies/Feature (2020)/bytes.bin", knownSize: 12)
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
