import Foundation
import SwiftData
import XCTest
@testable import CineVault

@MainActor
final class SourceExclusionsTests: XCTestCase {
    private func source() -> MediaSource {
        MediaSource(name: "Fixture", host: "test.invalid", share: "Media", rootPath: "Library", username: "")
    }

    func testRulesPersistAndPreserveHistoryAcrossReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("cinevault-ignore-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("library.store")
        func withStore(_ body: (ModelContext) throws -> Void) throws {
            try autoreleasepool {
                let schema = Schema([MediaSource.self, Movie.self])
                let config = ModelConfiguration("Exclusions", schema: schema, url: url, cloudKitDatabase: .none)
                let container = try ModelContainer(for: schema, configurations: [config])
                container.mainContext.autosaveEnabled = false
                try body(container.mainContext)
            }
        }
        try withStore { context in
            let source = source()
            let folderMovie = Movie(filePath: "Library/Private/Watched.mp4", fileSize: 100, source: source)
            folderMovie.isWatched = true; folderMovie.isWatchlisted = true; folderMovie.playbackPosition = 42
            let exactMovie = Movie(filePath: "Library/Movies/One.mp4", fileSize: 100, source: source)
            let kept = Movie(filePath: "Library/Movies/One.mp4/Other.mp4", fileSize: 100, source: source)
            context.insert(source); context.insert(folderMovie); context.insert(exactMovie); context.insert(kept)
            try source.applyIgnoredPaths(folders: ["Private", "private"], files: ["Movies\\One.mp4"])
            XCTAssertTrue(folderMovie.isMissing); XCTAssertTrue(exactMovie.isMissing); XCTAssertFalse(kept.isMissing)
            try context.save()
        }
        try withStore { context in
            let source = try XCTUnwrap(context.fetch(FetchDescriptor<MediaSource>()).first)
            XCTAssertEqual(source.ignoredFolders, ["Private"])
            XCTAssertEqual(source.ignoredFiles, ["Movies/One.mp4"])
            let movie = try XCTUnwrap(source.movies.first { $0.filePath.hasSuffix("Watched.mp4") })
            XCTAssertTrue(movie.isMissing); XCTAssertTrue(movie.isWatched); XCTAssertTrue(movie.isWatchlisted)
            XCTAssertEqual(movie.playbackPosition, 42)
            try source.applyIgnoredPaths(folders: [], files: [])
            XCTAssertTrue(movie.isMissing, "Stopping an exclusion restores files only after a successful rescan.")
            try context.save()
        }
        try withStore { context in
            let source = try XCTUnwrap(context.fetch(FetchDescriptor<MediaSource>()).first)
            XCTAssertEqual(source.ignoredFolders ?? [], [])
            XCTAssertEqual(source.ignoredFiles ?? [], [])
        }
    }

    func testProgressAndFinalFilesCannotReinsertIgnoredOrCodeFilesAndRescanRestoresUnignored() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = source()
        context.insert(source)
        let ignored = Movie(filePath: "Library/Private/Old.mp4", fileSize: 100, source: source)
        ignored.isWatched = true; ignored.playbackPosition = 42
        let code = Movie(filePath: "Library/fetch.d.ts", fileSize: 100, source: source)
        context.insert(ignored); context.insert(code)
        try source.applyIgnoredPaths(folders: ["Private"], files: ["Movies/One.mp4"])
        try context.save()
        let files = [RemoteFile(path: ignored.filePath, size: 100), RemoteFile(path: "Library/Movies/One.mp4", size: 100),
                     RemoteFile(path: "Library/Movies/Keep.mp4", size: 100), RemoteFile(path: code.filePath, size: 100),
                     RemoteFile(path: "Library/node_modules/Bad.mp4", size: 100)]
        let scanner = LibraryScanner(fetchMetadata: { _ in nil }, scanWithProgress: { _, report in
            try await report(ScanProgress(files: files, found: files.count, folders: 1))
            XCTAssertTrue(ignored.isMissing)
            XCTAssertFalse(source.movies.contains { $0.filePath == "Library/Movies/One.mp4" })
            return files
        })
        await scanner.scan(source, context: context)
        XCTAssertEqual(source.movies.filter { !$0.isMissing }.map(\.filePath), ["Library/Movies/Keep.mp4"])
        XCTAssertTrue(code.isMissing)
        XCTAssertNotNil(source.lastScanDate)
        try source.applyIgnoredPaths(folders: [], files: [])
        let restored = LibraryScanner(fetchMetadata: { _ in nil }, scanVideos: { _ in [files[0], files[2]] })
        await restored.scan(source, context: context)
        XCTAssertFalse(ignored.isMissing)
        XCTAssertTrue(ignored.isWatched)
        XCTAssertEqual(ignored.playbackPosition, 42)
    }

    func testChangingRulesInvalidatesPendingScanAndRejectsInvalidPathsAtomically() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = source()
        context.insert(source); try context.save()
        let original = try source.config()
        try source.applyIgnoredPaths(folders: ["Private"], files: [])
        XCTAssertNotEqual(try source.config(), original)
        let foldersOnly = try source.config()
        try source.applyIgnoredPaths(folders: ["Private"], files: ["One.mp4"])
        XCTAssertNotEqual(try source.config(), foldersOnly)
        XCTAssertThrowsError(try source.applyIgnoredPaths(folders: ["Elsewhere"], files: ["../escape.mp4"]))
        XCTAssertEqual(source.ignoredFolders, ["Private"])
        XCTAssertEqual(source.ignoredFiles, ["One.mp4"])
        let scanner = LibraryScanner(fetchMetadata: { _ in nil }, scanWithProgress: { _, report in
            try source.applyIgnoredPaths(folders: ["Movies"], files: [])
            try await report(ScanProgress(files: [RemoteFile(path: "Library/Movies/Late.mp4", size: 100)], found: 1, folders: 1))
            return []
        })
        await scanner.scan(source, context: context)
        XCTAssertTrue(source.movies.isEmpty)
        XCTAssertNil(source.lastScanDate)
        XCTAssertTrue(scanner.status?.contains("source changed") == true)
    }

    func testLateMetadataCannotUpdateAnItemIgnoredDuringItsRequest() async throws {
        let source = source()
        let movie = Movie(filePath: "Library/Movies/One.mp4", fileSize: 100, source: source)
        movie.setTitle("Keep my title")
        source.movies = [movie]
        let metadata = MetadataService(requestData: { request in
            try source.applyIgnoredPaths(folders: [], files: ["Movies/One.mp4"])
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":1,"title":"Late response","poster_path":"/late.jpg"}"#.utf8), response)
        }, readAPIKey: { "fixture-only" })
        let applied = try await metadata.apply(tmdbID: 1, to: movie, force: true)
        XCTAssertFalse(applied)
        XCTAssertTrue(movie.isMissing)
        XCTAssertEqual(movie.title, "Keep my title")
        XCTAssertNil(movie.posterPath)
    }
}
