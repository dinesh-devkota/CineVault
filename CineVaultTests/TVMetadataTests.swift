import CineVaultCore
import Foundation
import SwiftData
import XCTest
@testable import CineVault

@MainActor
final class TVMetadataTests: XCTestCase {
    func testLocalGroupingWorksWithoutMetadataAndKeepsRemakesSeparate() {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Media", rootPath: "", username: "")
        let first = Movie(filePath: "TV Shows/Example (2000)/Season01/Example.S01E01.mp4", fileSize: 1, source: source)
        let remake = Movie(filePath: "TV Shows/Example (2022)/Season01/Example.S01E01.mp4", fileSize: 1, source: source)
        XCTAssertEqual(first.mediaKind, .tvShow)
        XCTAssertEqual(first.episodeInfo?.season, 1)
        XCTAssertNotEqual(first.seriesKey, remake.seriesKey)
    }

    func testTVMatchingUsesSeriesAndSeasonOnceWithoutAddingRemoteEpisodes() async throws {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Media", rootPath: "", username: "")
        let first = Movie(filePath: "TV Shows/Example (2022)/Season01/Example.S01E01.mp4", fileSize: 1, source: source)
        let second = Movie(filePath: "TV Shows/Example (2022)/Season01/Example.S01E02.mp4", fileSize: 1, source: source)
        first.setTitle("First file"); second.setTitle("Second file")
        first.tmdbID = 999 // A legacy episode that was incorrectly treated as a movie.
        first.metadataFetched = true
        first.playbackPosition = 90
        first.isWatchlisted = true
        var paths: [String] = []
        let service = MetadataService(requestData: { request in
            let url = try XCTUnwrap(request.url)
            paths.append(url.path)
            let body: String
            switch url.path {
            case "/3/search/tv":
                body = #"{"results":[{"id":42,"name":"Example","first_air_date":"2022-01-01"}]}"#
            case "/3/tv/42":
                body = #"{"id":42,"name":"Example","first_air_date":"2022-01-01","poster_path":"/show.jpg","genres":[{"name":"Documentary"}]}"#
            case "/3/tv/42/season/1":
                body = #"{"season_number":1,"poster_path":"/season.jpg","episodes":[{"season_number":1,"episode_number":1,"name":"Pilot","runtime":30,"still_path":"/pilot.jpg"},{"season_number":1,"episode_number":2,"name":"Next","runtime":40},{"season_number":1,"episode_number":3,"name":"Not on NAS"}]}"#
            default:
                XCTFail("Unexpected request: \(url.path)")
                throw MetadataError.invalidResponse
            }
            return (Data(body.utf8), try XCTUnwrap(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)))
        }, readAPIKey: { "key" })
        try await service.match(first)
        try await service.match(second)
        XCTAssertEqual(paths, ["/3/search/tv", "/3/tv/42", "/3/tv/42/season/1"])
        XCTAssertEqual(first.title, "Pilot")
        XCTAssertEqual(second.title, "Next")
        XCTAssertNil(first.tmdbID)
        XCTAssertEqual(first.seriesTMDBID, 42)
        XCTAssertEqual(first.posterPath, "/show.jpg")
        XCTAssertEqual(first.seasonPosterPath, "/season.jpg")
        XCTAssertEqual(first.backdropPath, "/pilot.jpg")
        XCTAssertEqual(first.mediaKind, .tvShow)
        XCTAssertEqual(first.playbackPosition, 90)
        XCTAssertTrue(first.isWatchlisted)
    }

    func testMatchedGenresOverrideFolderFallbackButNotTVIdentity() {
        let source = MediaSource(name: "Test", host: "test.invalid", share: "Media", rootPath: "", username: "")
        let movie = Movie(filePath: "Documentaries/Film.mp4", fileSize: 1, source: source)
        XCTAssertEqual(movie.mediaKind, .documentary)
        movie.tmdbID = 42; movie.metadataFetched = true; movie.genres = ["Drama"]
        XCTAssertEqual(movie.mediaKind, .movie)
        movie.genres = ["Music"]
        XCTAssertEqual(movie.mediaKind, .music)
    }
}
