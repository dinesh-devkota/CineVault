import Foundation
import SwiftData
import XCTest
@testable import CineVault

@MainActor
final class LibraryProgressTests: XCTestCase {
    func testFailedFinalSaveRestoresMissingFlagsAndScanDateButKeepsSavedBatches() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Media", host: "localhost", share: "Media", rootPath: "", username: "")
        context.insert(source)
        let old = Movie(filePath: "Old.mp4", fileSize: 100, source: source)
        old.setTitle("Keep my title")
        context.insert(old)
        try context.save()
        var saves = 0
        let found = RemoteFile(path: "New.mp4", size: 200)
        let scanner = LibraryScanner(scanWithProgress: { _, report in
            try await report(ScanProgress(files: [found], found: 1, folders: 1))
            return [found]
        }, saveContext: { context in
            saves += 1
            if saves == 2 { throw NSError(domain: "Save fixture", code: 1) }
            try context.save()
        })
        await scanner.scan(source, context: context)
        XCTAssertFalse(old.isMissing)
        XCTAssertEqual(old.title, "Keep my title")
        XCTAssertNil(source.lastScanDate)
        XCTAssertEqual(source.movies.count, 2)
        try context.save()
        XCTAssertFalse(old.isMissing)
        XCTAssertTrue(scanner.status?.contains("Scan failed") == true)
    }

    func testPartialResultsAppearBeforeFailureWithoutPruningExistingItems() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Media", host: "localhost", share: "Media", rootPath: "", username: "")
        context.insert(source)
        let old = Movie(filePath: "Movies/Old.mp4", fileSize: 100, source: source)
        old.setTitle("My edited title"); old.isWatched = true
        context.insert(old)
        try context.save()
        let scanner = LibraryScanner(scanWithProgress: { _, report in
            try await report(ScanProgress(files: [RemoteFile(path: "Music/Album/01 - Song.mp3", size: 100)], found: 1, folders: 2))
            XCTAssertEqual(source.movies.count, 2)
            XCTAssertTrue(source.movies.first { $0.filePath.hasSuffix(".mp3") }?.metadataFetched == true)
            XCTAssertEqual(old.title, "My edited title")
            XCTAssertTrue(old.isWatched)
            throw NSError(domain: "Fixture", code: 1)
        })
        await scanner.scan(source, context: context)
        XCTAssertEqual(source.movies.count, 2)
        XCTAssertFalse(old.isMissing)
        XCTAssertNil(source.lastScanDate)
        XCTAssertTrue(scanner.status?.contains("Items already found are kept") == true)
    }

    func testFinalReconciliationWaitsForAllBatchesAndCountsEachInsertionOnce() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Media", host: "localhost", share: "Media", rootPath: "", username: "")
        context.insert(source)
        let old = Movie(filePath: "Old.mp4", fileSize: 100, source: source)
        context.insert(old)
        try context.save()
        let files = [RemoteFile(path: "Movies/New.mp4", size: 100), RemoteFile(path: "Documentaries/Nature.mp4", size: 100)]
        let scanner = LibraryScanner(scanWithProgress: { _, report in
            try await report(ScanProgress(files: Array(files.prefix(1)), found: 1, folders: 1))
            XCTAssertFalse(old.isMissing)
            try await report(ScanProgress(files: Array(files.suffix(1)), found: 2, folders: 2))
            XCTAssertEqual(source.movies.count, 3)
            return files
        })
        await scanner.scan(source, context: context)
        XCTAssertTrue(old.isMissing)
        XCTAssertNotNil(source.lastScanDate)
        XCTAssertTrue(scanner.status?.contains("1 movies, 1 documentaries, 0 TV episodes, 0 music items (2 new)") == true)
    }
}
