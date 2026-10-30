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
        let bytes = try await SMBService.shared.read(config, path: movie.path, offset: 0, length: 4)
        XCTAssertEqual(bytes, Data(repeating: 0, count: 4))
        await SMBService.shared.reset(config)
    }

    func testReadReturnsTheRequestedByteRange() async throws {
        let config = configuration()
        let bytes = try await SMBService.shared.read(
            config, path: "Movies/Feature (2020)/bytes.bin", offset: 3, length: 4
        )

        XCTAssertEqual(bytes, Data("3456".utf8))
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
