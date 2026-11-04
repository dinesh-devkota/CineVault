import Foundation
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
    private func waitUntilEntered(_ gate: ScanGate) async -> Bool {
        let deadline = Date().addingTimeInterval(5)
        while !gate.entered && Date() < deadline { await Task.yield() }
        return gate.entered
    }

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

    func testCancelledScanDoesNotApplyLateResults() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
                                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let existing = Movie(filePath: "Existing.mp4", fileSize: 100, source: source)
        context.insert(source)
        context.insert(existing)
        try context.save()
        let gate = ScanGate()
        defer { gate.release() }
        let scanner = LibraryScanner { _ in
            // A NAS request may finish normally even after its caller is cancelled.
            await gate.wait()
            return [RemoteFile(path: "Late.mp4", size: 200)]
        }
        let scan = Task { await scanner.scan(source, context: context) }
        guard await waitUntilEntered(gate) else { XCTFail("Scan did not start."); return }

        scan.cancel()
        gate.release()
        await scan.value

        XCTAssertFalse(existing.isMissing)
        XCTAssertNil(source.lastScanDate)
        XCTAssertEqual(source.movies.map(\.filePath), ["Existing.mp4"])
        XCTAssertFalse(scanner.isScanning)
        XCTAssertTrue(scanner.status?.contains("cancelled") == true)
    }

    func testLateMetadataDetailsPreserveManualRematch() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Movie.mp4", fileSize: 1, source: source)
        movie.setTitle("Initial title")
        let gate = ScanGate()
        defer { gate.release() }
        let service = MetadataService(requestData: { request in
            await gate.wait()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":1,"title":"Stale title","poster_path":"/stale.jpg"}"#.utf8), response)
        }, readAPIKey: { "fixture" })
        let lookup = Task { try await service.apply(tmdbID: 1, to: movie) }
        guard await waitUntilEntered(gate) else { XCTFail("Metadata lookup did not start."); return }

        movie.tmdbID = 2
        movie.setTitle("Manual choice")
        movie.posterPath = "/manual.jpg"
        movie.isLocked = true
        movie.metadataFetched = true
        gate.release()
        try await lookup.value

        XCTAssertEqual(movie.tmdbID, 2)
        XCTAssertEqual(movie.title, "Manual choice")
        XCTAssertEqual(movie.posterPath, "/manual.jpg")
        XCTAssertTrue(movie.metadataFetched)
    }

    func testLateMetadataSearchDoesNotRematchAnEditedMovie() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Initial.mp4", fileSize: 1, source: source)
        movie.parsedTitle = "Initial"
        movie.setTitle("Initial")
        let gate = ScanGate()
        defer { gate.release() }
        var requests = 0
        let service = MetadataService(requestData: { request in
            requests += 1
            await gate.wait()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"results":[{"id":1,"title":"Initial"}]}"#.utf8), response)
        }, readAPIKey: { "fixture" })
        let lookup = Task { try await service.match(movie) }
        guard await waitUntilEntered(gate) else { XCTFail("Metadata search did not start."); return }

        movie.setTitle("User title")
        movie.isLocked = true
        gate.release()
        try await lookup.value

        XCTAssertEqual(requests, 1, "The stale search must not start a details request.")
        XCTAssertEqual(movie.title, "User title")
        XCTAssertNil(movie.tmdbID)
        XCTAssertFalse(movie.metadataFetched)
    }

    func testCancelledMetadataDoesNotApplyLateResponse() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Original.mp4", fileSize: 1, source: source)
        movie.setTitle("Original")
        let gate = ScanGate()
        defer { gate.release() }
        let service = MetadataService(requestData: { request in
            await gate.wait()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":1,"title":"Cancelled"}"#.utf8), response)
        }, readAPIKey: { "fixture" })
        let lookup = Task { try await service.apply(tmdbID: 1, to: movie) }
        guard await waitUntilEntered(gate) else { XCTFail("Metadata lookup did not start."); return }

        lookup.cancel()
        gate.release()
        do {
            try await lookup.value
            XCTFail("Cancelled metadata lookup must report cancellation.")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(movie.title, "Original")
        XCTAssertNil(movie.tmdbID)
        XCTAssertFalse(movie.metadataFetched)
    }

    func testManualRematchWinsWhenEarlierAutomaticDetailsFinishFirst() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Original.mp4", fileSize: 1, source: source)
        movie.setTitle("Original")
        movie.tmdbID = 1
        let automaticGate = ScanGate()
        let manualGate = ScanGate()
        defer {
            automaticGate.release()
            manualGate.release()
        }
        let service = MetadataService(requestData: { request in
            let manual = request.url!.lastPathComponent == "2"
            if manual { await manualGate.wait() } else { await automaticGate.wait() }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let data = manual ? #"{"id":2,"title":"Manual choice"}"# : #"{"id":1,"title":"Automatic choice"}"#
            return (Data(data.utf8), response)
        }, readAPIKey: { "fixture" })
        let automatic = Task { try await service.match(movie) }
        guard await waitUntilEntered(automaticGate) else { XCTFail("Automatic lookup did not start."); return }
        let manual = Task { try await service.apply(tmdbID: 2, to: movie, force: true) }
        guard await waitUntilEntered(manualGate) else { XCTFail("Manual lookup did not start."); return }

        automaticGate.release()
        try await automatic.value
        XCTAssertEqual(movie.title, "Original", "A superseded automatic response must not mutate the movie.")
        manualGate.release()
        try await manual.value

        XCTAssertEqual(movie.tmdbID, 2)
        XCTAssertEqual(movie.title, "Manual choice")
        XCTAssertTrue(movie.metadataFetched)
        XCTAssertTrue(movie.isLocked)
    }

    func testAutomaticMatchDoesNotSupersedePendingManualRematch() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Original.mp4", fileSize: 1, source: source)
        movie.parsedTitle = "Original"
        movie.setTitle("Original")
        let gate = ScanGate()
        defer { gate.release() }
        var requests = 0
        let service = MetadataService(requestData: { request in
            requests += 1
            guard requests == 1 else {
                XCTFail("Automatic matching must not start a second request during a manual selection.")
                throw MetadataError.httpStatus(500)
            }
            await gate.wait()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":2,"title":"Manual choice"}"#.utf8), response)
        }, readAPIKey: { "fixture" })
        let manual = Task { try await service.apply(tmdbID: 2, to: movie, force: true) }
        guard await waitUntilEntered(gate) else { XCTFail("Manual lookup did not start."); return }

        try await service.match(movie)
        XCTAssertEqual(requests, 1, "A scan must wait for the pending manual choice rather than start another request.")
        gate.release()
        try await manual.value

        XCTAssertEqual(movie.tmdbID, 2)
        XCTAssertEqual(movie.title, "Manual choice")
        XCTAssertTrue(movie.metadataFetched)
        XCTAssertTrue(movie.isLocked)
    }

    func testSupersededManualSelectionDoesNotLockOutNewerChoice() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Original.mp4", fileSize: 1, source: source)
        movie.setTitle("Original")
        let firstGate = ScanGate()
        let secondGate = ScanGate()
        defer {
            firstGate.release()
            secondGate.release()
        }
        let service = MetadataService(requestData: { request in
            let second = request.url!.lastPathComponent == "2"
            if second { await secondGate.wait() } else { await firstGate.wait() }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let data = second ? #"{"id":2,"title":"Newer choice"}"# : #"{"id":1,"title":"Older choice"}"#
            return (Data(data.utf8), response)
        }, readAPIKey: { "fixture" })
        let first = Task { try await service.apply(tmdbID: 1, to: movie, force: true) }
        guard await waitUntilEntered(firstGate) else { XCTFail("First selection did not start."); return }
        let second = Task { try await service.apply(tmdbID: 2, to: movie, force: true) }
        guard await waitUntilEntered(secondGate) else { XCTFail("Second selection did not start."); return }

        firstGate.release()
        let firstApplied = try await first.value
        XCTAssertFalse(firstApplied, "A superseded response must not trigger the caller's save/dismiss action.")
        XCTAssertFalse(movie.isLocked, "A superseded response must not trigger the caller's save/lock action.")
        secondGate.release()
        let secondApplied = try await second.value

        XCTAssertTrue(secondApplied)
        XCTAssertEqual(movie.tmdbID, 2)
        XCTAssertEqual(movie.title, "Newer choice")
        XCTAssertTrue(movie.isLocked)
    }

    func testResetInvalidatesPendingMatchEvenWhenVisibleIdentityIsUnchanged() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "Original.mp4", fileSize: 1, source: source)
        movie.parsedTitle = "Original"
        movie.setTitle("Original")
        let gate = ScanGate()
        defer { gate.release() }
        let service = MetadataService(requestData: { request in
            await gate.wait()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":1,"title":"Pending choice"}"#.utf8), response)
        }, readAPIKey: { "fixture" })
        let lookup = Task { try await service.apply(tmdbID: 1, to: movie, force: true) }
        guard await waitUntilEntered(gate) else { XCTFail("Lookup did not start."); return }

        service.resetToFileName(movie)
        gate.release()
        let applied = try await lookup.value

        XCTAssertFalse(applied)
        XCTAssertEqual(movie.title, "Original")
        XCTAssertNil(movie.tmdbID)
        XCTAssertFalse(movie.metadataFetched)
        XCTAssertFalse(movie.isLocked)
    }

    func testResetToFileNameClearsMismatchedMetadataAndPreservesLibraryState() {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "The Movie (2020).mp4", fileSize: 1, source: source)
        movie.parsedTitle = "The Movie"
        movie.parsedYear = 2020
        movie.setTitle("Wrong match")
        movie.year = 1990
        movie.tmdbID = 1
        movie.overview = "Wrong overview"
        movie.tagline = "Wrong tagline"
        movie.posterPath = "/wrong.jpg"
        movie.backdropPath = "/wrong-backdrop.jpg"
        movie.genres = ["Wrong genre"]
        movie.runtimeMinutes = 90
        movie.rating = 5
        movie.metadataFetched = true
        movie.isLocked = true
        movie.isHidden = true
        movie.isWatched = true
        movie.playbackPosition = 120

        movie.resetToFileName()

        XCTAssertEqual(movie.title, "The Movie")
        XCTAssertEqual(movie.sortTitle, "movie")
        XCTAssertEqual(movie.year, 2020)
        XCTAssertNil(movie.tmdbID)
        XCTAssertNil(movie.overview)
        XCTAssertNil(movie.tagline)
        XCTAssertNil(movie.posterPath)
        XCTAssertNil(movie.backdropPath)
        XCTAssertTrue(movie.genres.isEmpty)
        XCTAssertNil(movie.runtimeMinutes)
        XCTAssertNil(movie.rating)
        XCTAssertFalse(movie.metadataFetched)
        XCTAssertFalse(movie.isLocked)
        XCTAssertTrue(movie.isHidden)
        XCTAssertTrue(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, 120)
    }
}
