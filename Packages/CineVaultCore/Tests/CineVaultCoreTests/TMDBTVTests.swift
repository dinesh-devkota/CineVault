import Foundation
import XCTest
@testable import CineVaultCore

final class TMDBTVTests: XCTestCase {
    func testTVRequestsUseSeparateNamespaceAndFirstAirYear() throws {
        let search = TMDB.tvSearchRequest(title: "Example", year: 2022, apiKey: "key")
        let url = try XCTUnwrap(search.url)
        XCTAssertEqual(url.path, "/3/search/tv")
        let query = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(query.first { $0.name == "first_air_date_year" }?.value, "2022")
        XCTAssertNil(query.first { $0.name == "year" })
        XCTAssertEqual(TMDB.tvDetailsRequest(id: 42, apiKey: "key").url?.path, "/3/tv/42")
        XCTAssertEqual(TMDB.tvSeasonRequest(id: 42, season: 0, apiKey: "key").url?.path, "/3/tv/42/season/0")
    }

    func testTVResponsesDecodeSeriesSeasonAndEpisodeMetadata() throws {
        let search = try TMDB.decoder.decode(TMDBTVSearchResponse.self, from: Data(#"{"results":[{"id":42,"name":"Example","first_air_date":"2022-01-01","poster_path":"/show.jpg"}]}"#.utf8))
        XCTAssertEqual(search.results.first?.matchingCandidate.title, "Example")
        XCTAssertEqual(search.results.first?.matchingCandidate.year, 2022)
        let details = try TMDB.decoder.decode(TMDBTVDetails.self, from: Data(#"{"id":42,"name":"Example","episode_run_time":[45],"genres":[{"name":"Drama"}]}"#.utf8))
        XCTAssertEqual(details.episodeRunTime, [45])
        let season = try TMDB.decoder.decode(TMDBTVSeason.self, from: Data(#"{"season_number":0,"poster_path":"/season.jpg","episodes":[{"season_number":0,"episode_number":1,"name":"Special","runtime":30}]}"#.utf8))
        XCTAssertEqual(season.posterPath, "/season.jpg")
        XCTAssertEqual(season.episodes.first?.episodeNumber, 1)
        XCTAssertEqual(season.episodes.first?.runtime, 30)
    }
}
