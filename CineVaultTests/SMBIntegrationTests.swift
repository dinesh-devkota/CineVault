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
        XCTAssertEqual(files.first?.path, "Movies/Feature (2020)/Feature (2020).mp4")
        XCTAssertEqual(files.first?.size, 60 * 1024 * 1024)
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
        do {
            try await SMBService.shared.testConnection(config)
            XCTFail("An invalid password must not connect.")
        } catch {
            await SMBService.shared.reset(config)
            return
        }
        await SMBService.shared.reset(config)
    }
}
