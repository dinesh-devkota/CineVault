import SwiftData
import XCTest
@testable import CineVault

@MainActor
private final class ScanGate {
    private(set) var entered = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
        entered = true
        await withCheckedContinuation { continuation = $0 }
    }

    func release() {
        continuation?.resume()
        continuation = nil
    }
}

@MainActor
final class LibraryBehaviorTests: XCTestCase {
    func testSearchExcludesHiddenAndMissingMovies() {
        let source = MediaSource(name: "Test", host: "test.invalid",
                                 share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "A Movie.mp4", fileSize: 1, source: source)
        movie.setTitle("A Movie")
        let normalized = "movie"

        XCTAssertTrue(LibrarySearch.matches(movie, text: "movie", normalized: normalized))
        movie.isHidden = true
        XCTAssertFalse(LibrarySearch.matches(movie, text: "movie", normalized: normalized))
        movie.isHidden = false
        movie.isMissing = true
        XCTAssertFalse(LibrarySearch.matches(movie, text: "movie", normalized: normalized))
    }

    func testOverlappingScansAreQueuedInsteadOfDiscarded() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
                                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let first = MediaSource(name: "First", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let second = MediaSource(name: "Second", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        context.insert(first)
        context.insert(second)
        try context.save()
        let firstID = first.id
        let gate = ScanGate()
        defer { gate.release() }
        let scanner = LibraryScanner { config in
            if config.id == firstID { await gate.wait() }
            return [RemoteFile(path: "\(config.id.uuidString).mp4", size: 1)]
        }

        let firstTask = Task { await scanner.scan(first, context: context) }
        let deadline = Date().addingTimeInterval(5)
        while !gate.entered && Date() < deadline { await Task.yield() }
        guard gate.entered else { XCTFail("First scan did not start."); return }
        var secondStarted = false
        var secondReturned = false
        let secondTask = Task {
            secondStarted = true
            await scanner.scan(second, context: context)
            secondReturned = true
        }
        while !secondStarted && Date() < deadline { await Task.yield() }
        XCTAssertTrue(secondStarted)
        await Task.yield()
        XCTAssertFalse(secondReturned, "A scan requested during another scan must wait for its turn.")

        gate.release()
        await firstTask.value
        await secondTask.value
        XCTAssertNotNil(first.lastScanDate)
        XCTAssertNotNil(second.lastScanDate)
        XCTAssertEqual(first.movies.count, 1)
        XCTAssertEqual(second.movies.count, 1)
        XCTAssertFalse(scanner.isScanning)
    }

    func testMetadataContinuesAfterAnIndividualLookupFails() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
                                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        context.insert(source)
        let broken = Movie(filePath: "Broken.mp4", fileSize: 1, source: source)
        let working = Movie(filePath: "Working.mp4", fileSize: 1, source: source)
        context.insert(broken)
        context.insert(working)
        let summary = try await MetadataService().update([broken, working], in: context) { movie in
            if movie === broken { throw MetadataError.httpStatus(404) }
            movie.metadataFetched = true
        }

        XCTAssertEqual(summary.attempted, 2)
        XCTAssertEqual(summary.failed, 1)
        XCTAssertTrue(summary.failureMessage?.contains("404") == true)
        XCTAssertFalse(broken.metadataFetched)
        XCTAssertTrue(working.metadataFetched)
    }
}
