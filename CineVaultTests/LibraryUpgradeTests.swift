import Foundation
import SwiftData
import XCTest
@testable import CineVault

/// Persisted fields shipped before Watchlist was added. Keep this frozen: the
/// fixture must create a real old store rather than a store using today's model.
private enum LibraryBeforeWatchlist {
    @Model
    final class MediaSource {
        var id: UUID = UUID()
        var name: String = ""
        var host: String = ""
        var port: Int?
        var share: String = ""
        var rootPath: String = ""
        var username: String = ""
        var lastScanDate: Date?
        @Relationship(deleteRule: .cascade, inverse: \Movie.source)
        var movies: [Movie] = []

        init(id: UUID) { self.id = id }
    }

    @Model
    final class Movie {
        var id: UUID = UUID()
        var filePath: String = ""
        var fileName: String = ""
        var fileSize: Int64 = 0
        var parsedTitle: String = ""
        var parsedYear: Int?
        var title: String = ""
        var sortTitle: String = ""
        var year: Int?
        var overview: String?
        var tagline: String?
        var tmdbID: Int?
        var posterPath: String?
        var backdropPath: String?
        var genres: [String] = []
        var runtimeMinutes: Int?
        var rating: Double?
        var metadataFetched: Bool = false
        var isLocked: Bool = false
        var dateAdded: Date = Date()
        var isWatched: Bool = false
        var playbackPosition: Double = 0
        var duration: Double = 0
        var lastPlayedAt: Date?
        var isHidden: Bool = false
        var isMissing: Bool = false
        var source: MediaSource?

        init(id: UUID, source: MediaSource) {
            self.id = id
            self.source = source
        }
    }
}

@MainActor
final class LibraryUpgradeTests: XCTestCase {
    private func withLegacyStore(at url: URL, _ body: (ModelContext) throws -> Void) throws {
        try autoreleasepool {
            let schema = Schema([LibraryBeforeWatchlist.MediaSource.self, LibraryBeforeWatchlist.Movie.self])
            let configuration = ModelConfiguration("LibraryUpgrade", schema: schema, url: url, cloudKitDatabase: .none)
            let container = try ModelContainer(for: schema, configurations: [configuration])
            let context = container.mainContext
            context.autosaveEnabled = false
            try body(context)
        }
    }

    private func withCurrentStore(at url: URL, _ body: (ModelContext) throws -> Void) throws {
        try autoreleasepool {
            let schema = Schema([MediaSource.self, Movie.self])
            let configuration = ModelConfiguration("LibraryUpgrade", schema: schema, url: url, cloudKitDatabase: .none)
            let container = try ModelContainer(for: schema, configurations: [configuration])
            let context = container.mainContext
            context.autosaveEnabled = false
            try body(context)
        }
    }

    func testOldLibraryMigratesWithoutLosingStateAndWatchlistPersistsAfterReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("cinevault-upgrade-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("library.store")
        let sourceID = UUID()
        let movieID = UUID()
        let watchedID = UUID()
        let added = Date(timeIntervalSince1970: 1_600_000_000)
        let played = Date(timeIntervalSince1970: 1_700_000_000)
        let scanned = Date(timeIntervalSince1970: 1_710_000_000)
        // Passwords live outside SwiftData under this stable source-ID account.
        // Migrating the source and relationship must preserve that lookup key.
        let account = "source-\(sourceID.uuidString)"

        try withLegacyStore(at: url) { context in
            let source = LibraryBeforeWatchlist.MediaSource(id: sourceID)
            source.name = "Original NAS"
            source.host = "nas.example.invalid"
            source.port = 1445
            source.share = "Video"
            source.rootPath = "Movies"
            source.username = "original-user"
            source.lastScanDate = scanned
            context.insert(source)
            let movie = LibraryBeforeWatchlist.Movie(id: movieID, source: source)
            movie.filePath = "Movies/The Matrix (1999) {tmdb-603}.mp4"
            movie.fileName = "The Matrix (1999) {tmdb-603}.mp4"
            movie.fileSize = 60 * 1024 * 1024
            movie.parsedTitle = "The Matrix"
            movie.parsedYear = 1999
            movie.title = "My Matrix"
            movie.sortTitle = "my matrix"
            movie.year = 1999
            movie.overview = "Original overview"
            movie.tagline = "Original tagline"
            movie.tmdbID = 603
            movie.posterPath = "/original-poster.jpg"
            movie.backdropPath = "/original-backdrop.jpg"
            movie.genres = ["Action", "Science Fiction"]
            movie.runtimeMinutes = 136
            movie.rating = 8.7
            movie.metadataFetched = true
            movie.isLocked = true
            movie.dateAdded = added
            movie.playbackPosition = 720
            movie.duration = 8160
            movie.lastPlayedAt = played
            movie.isHidden = true
            movie.isMissing = true
            context.insert(movie)
            let watched = LibraryBeforeWatchlist.Movie(id: watchedID, source: source)
            watched.filePath = "Movies/Watched.mp4"
            watched.fileName = "Watched.mp4"
            watched.title = "Watched"
            watched.sortTitle = "watched"
            watched.isWatched = true
            watched.duration = 600
            watched.lastPlayedAt = played
            context.insert(watched)
            try context.save()
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))

        try withCurrentStore(at: url) { context in
            let sources = try context.fetch(FetchDescriptor<MediaSource>())
            let source = try XCTUnwrap(sources.first)
            XCTAssertEqual(sources.count, 1)
            XCTAssertEqual(source.id, sourceID)
            XCTAssertEqual(source.name, "Original NAS")
            XCTAssertEqual(source.host, "nas.example.invalid")
            XCTAssertEqual(source.port, 1445)
            XCTAssertEqual(source.share, "Video")
            XCTAssertEqual(source.rootPath, "Movies")
            XCTAssertNil(source.ignoredFolders)
            XCTAssertNil(source.ignoredFiles)
            XCTAssertEqual(source.username, "original-user")
            XCTAssertEqual(source.lastScanDate, scanned)
            XCTAssertEqual("source-\(source.id.uuidString)", account, "The source must continue to reference its original external credential account.")
            let movies = try context.fetch(FetchDescriptor<Movie>())
            XCTAssertEqual(movies.count, 2)
            XCTAssertEqual(source.movies.count, 2)
            let movie = try XCTUnwrap(movies.first { $0.id == movieID })
            XCTAssertEqual(movie.source?.id, sourceID)
            XCTAssertEqual(movie.filePath, "Movies/The Matrix (1999) {tmdb-603}.mp4")
            XCTAssertEqual(movie.fileName, "The Matrix (1999) {tmdb-603}.mp4")
            XCTAssertEqual(movie.fileSize, 60 * 1024 * 1024)
            XCTAssertEqual(movie.parsedTitle, "The Matrix")
            XCTAssertEqual(movie.parsedYear, 1999)
            XCTAssertEqual(movie.title, "My Matrix")
            XCTAssertEqual(movie.sortTitle, "my matrix")
            XCTAssertEqual(movie.year, 1999)
            XCTAssertEqual(movie.overview, "Original overview")
            XCTAssertEqual(movie.tagline, "Original tagline")
            XCTAssertEqual(movie.tmdbID, 603)
            XCTAssertEqual(movie.posterPath, "/original-poster.jpg")
            XCTAssertEqual(movie.backdropPath, "/original-backdrop.jpg")
            XCTAssertEqual(movie.genres, ["Action", "Science Fiction"])
            XCTAssertEqual(movie.runtimeMinutes, 136)
            XCTAssertEqual(movie.rating, 8.7)
            XCTAssertTrue(movie.metadataFetched)
            XCTAssertTrue(movie.isLocked)
            XCTAssertEqual(movie.dateAdded, added)
            XCTAssertFalse(movie.isWatched)
            XCTAssertEqual(movie.playbackPosition, 720)
            XCTAssertEqual(movie.duration, 8160)
            XCTAssertEqual(movie.lastPlayedAt, played)
            XCTAssertTrue(movie.isHidden)
            XCTAssertTrue(movie.isMissing)
            XCTAssertFalse(movie.isWatchlisted)
            let watched = try XCTUnwrap(movies.first { $0.id == watchedID })
            XCTAssertTrue(watched.isWatched)
            XCTAssertEqual(watched.playbackPosition, 0)
            XCTAssertEqual(watched.duration, 600)
            XCTAssertFalse(watched.isWatchlisted)
            movie.isWatchlisted = true
            try context.save()
        }

        try withCurrentStore(at: url) { context in
            let movies = try context.fetch(FetchDescriptor<Movie>())
            let movie = try XCTUnwrap(movies.first { $0.id == movieID })
            XCTAssertTrue(movie.isWatchlisted, "The new field must survive closing and reopening the upgraded store.")
            XCTAssertEqual(movie.playbackPosition, 720)
            XCTAssertTrue(movie.isHidden)
            XCTAssertTrue(movie.isMissing)
            XCTAssertEqual(movie.source?.id, sourceID)
            XCTAssertEqual("source-\(try XCTUnwrap(movie.source).id.uuidString)", account)
            XCTAssertFalse(try XCTUnwrap(movies.first { $0.id == watchedID }).isWatchlisted)
        }
    }

    func testWatchlistSurvivesMetadataResetRefreshAndRescan() async throws {
        let container = try ModelContainer(for: MediaSource.self, Movie.self,
                                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        let source = MediaSource(name: "Fixture", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        let movie = Movie(filePath: "The Matrix (1999) {tmdb-603}.mp4", fileSize: 100, source: source)
        movie.isWatchlisted = true
        movie.isHidden = true
        movie.isWatched = true
        movie.playbackPosition = 120
        movie.isMissing = true
        context.insert(source)
        context.insert(movie)
        try context.save()
        let metadata = MetadataService(requestData: { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(#"{"id":603,"title":"The Matrix","release_date":"1999-03-30"}"#.utf8), response)
        }, readAPIKey: { "fixture" })

        metadata.resetToFileName(movie)
        XCTAssertTrue(movie.isWatchlisted)
        let applied = try await metadata.apply(tmdbID: 603, to: movie, force: true)
        XCTAssertTrue(applied)
        XCTAssertTrue(movie.isWatchlisted)
        let scanner = LibraryScanner(scanVideos: { _ in
            [RemoteFile(path: "The Matrix (1999) {tmdb-603}.mp4", size: 200)]
        })
        await scanner.scan(source, context: context)

        XCTAssertEqual(try context.fetch(FetchDescriptor<Movie>()).count, 1)
        XCTAssertEqual(movie.fileSize, 200)
        XCTAssertFalse(movie.isMissing)
        XCTAssertTrue(movie.isWatchlisted)
        XCTAssertTrue(movie.isHidden)
        XCTAssertTrue(movie.isWatched)
        XCTAssertEqual(movie.playbackPosition, 120)
    }

    func testWatchlistAndCombinedSourceDecadeGenreFilters() {
        let first = MediaSource(name: "First", host: "first.invalid", share: "Movies", rootPath: "", username: "")
        let second = MediaSource(name: "Second", host: "second.invalid", share: "Movies", rootPath: "", username: "")
        func movie(_ title: String, source: MediaSource, year: Int? = 1995,
                   genre: String = "Action", watchlisted: Bool = true) -> Movie {
            let result = Movie(filePath: "\(title).mp4", fileSize: 1, source: source)
            result.setTitle(title)
            result.year = year
            result.genres = [genre]
            result.isWatchlisted = watchlisted
            return result
        }
        let alpha = movie("Alpha", source: first, year: 1990)
        let beta = movie("Beta", source: first, year: 1999)
        let watched = movie("Watched", source: first)
        watched.isWatched = true
        let otherSource = movie("Zeta", source: second)
        let nextDecade = movie("Next decade", source: first, year: 2000)
        let unknownYear = movie("No year", source: first, year: nil)
        let drama = movie("Drama", source: first, genre: "Drama")
        let unlisted = movie("Not listed", source: first, watchlisted: false)
        let hidden = movie("Hidden", source: first)
        hidden.isHidden = true
        let missing = movie("Missing", source: first)
        missing.isMissing = true
        let movies = [missing, otherSource, beta, hidden, nextDecade, unlisted, unknownYear, watched, drama, alpha]

        XCTAssertEqual(LibraryQuery.visible(movies, sort: .title, filter: .watchlist,
                                             genre: "Action", sourceID: first.id, decade: 1990).map(\.title),
                       ["Alpha", "Beta", "Watched"])
        XCTAssertEqual(LibraryQuery.visible(movies, sort: .title, filter: .unwatched,
                                             genre: "Action", sourceID: first.id, decade: 1990).map(\.title),
                       ["Alpha", "Beta", "Not listed"])
        XCTAssertEqual(LibraryQuery.visible(movies, sort: .title, filter: .hidden,
                                             genre: "Action", sourceID: first.id, decade: 1990).map(\.title), ["Hidden"])
        XCTAssertEqual(LibraryQuery.watchlist(movies).map(\.title),
                       ["Alpha", "Beta", "Drama", "Next decade", "No year", "Watched", "Zeta"])
        XCTAssertTrue(LibraryQuery.visible(movies, sort: .title, filter: .watchlist,
                                           genre: "Action", sourceID: first.id, decade: 1980).isEmpty)
    }

    func testBrowsePreferencesPersistAndSourceDeletionClearsOnlyTheRemovedSelection() throws {
        let suite = "cinevault-library-preferences-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        // These sort/filter keys already existed before this upgrade.
        defaults.set(LibrarySort.rating.rawValue, forKey: "librarySort")
        defaults.set(LibraryFilter.unwatched.rawValue, forKey: "libraryFilter")
        let initial = LibraryPreferences(defaults: defaults)
        XCTAssertEqual(initial.sort, .rating)
        XCTAssertEqual(initial.filter, .unwatched)
        XCTAssertEqual(initial.genre, "")
        XCTAssertEqual(initial.sourceSelection, "")
        XCTAssertEqual(initial.decade, 0)
        let source = MediaSource(name: "Saved NAS", host: "test.invalid", share: "Movies", rootPath: "", username: "")
        initial.sort = .year
        initial.filter = .watchlist
        initial.genre = "Science Fiction"
        initial.sourceSelection = source.id.uuidString
        initial.decade = 1990

        let restored = LibraryPreferences(defaults: defaults)
        XCTAssertEqual(restored.sort, .year)
        XCTAssertEqual(restored.filter, .watchlist)
        XCTAssertEqual(restored.genre, "Science Fiction")
        XCTAssertEqual(restored.sourceSelection, source.id.uuidString)
        XCTAssertEqual(restored.decade, 1990)
        restored.sourcesChanged(from: [], to: [])
        XCTAssertEqual(restored.sourceSelection, source.id.uuidString, "An initial empty query must not erase the saved source.")
        XCTAssertNil(LibraryQuery.selectedSourceID(restored.sourceSelection, sources: []))
        restored.sourcesChanged(from: [], to: [source])
        XCTAssertEqual(LibraryQuery.selectedSourceID(restored.sourceSelection, sources: [source]), source.id)
        restored.sourcesChanged(from: [source.id], to: [])
        XCTAssertEqual(restored.sourceSelection, "")
        let afterDeletion = LibraryPreferences(defaults: defaults)
        XCTAssertEqual(afterDeletion.sourceSelection, "")
        XCTAssertEqual(afterDeletion.genre, "Science Fiction", "Removing a source must preserve the other browse preferences.")
        XCTAssertEqual(afterDeletion.decade, 1990)

        defaults.set("Unsupported sort", forKey: "librarySort")
        defaults.set("Unsupported filter", forKey: "libraryFilter")
        defaults.set(1995, forKey: "libraryDecade")
        let invalid = LibraryPreferences(defaults: defaults)
        XCTAssertEqual(invalid.sort, .title)
        XCTAssertEqual(invalid.filter, .all)
        XCTAssertEqual(invalid.decade, 0)
    }
}
