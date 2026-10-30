import XCTest
@testable import CineVaultCore

final class TMDBTests: XCTestCase {
    func testDecodeSearch() throws {
        let json = #"{"page":1,"results":[{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30","poster_path":"/p.jpg","vote_average":8.2}]}"#
        let response = try TMDB.decoder.decode(TMDBSearchResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.results.first?.id, 603)
        XCTAssertEqual(response.results.first?.year, 1999)
        XCTAssertEqual(response.results.first?.posterPath, "/p.jpg")
    }

    func testRequestAuth() {
        let v3 = TMDB.searchRequest(title: "Heat", year: 1995, apiKey: "abc123")
        XCTAssertTrue(v3.url!.absoluteString.contains("api_key=abc123"))
        XCTAssertTrue(v3.url!.absoluteString.contains("year=1995"))
        XCTAssertNil(v3.value(forHTTPHeaderField: "Authorization"))

        let token = String(repeating: "x", count: 60)
        let v4 = TMDB.detailsRequest(id: 1, apiKey: token)
        XCTAssertEqual(v4.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        XCTAssertFalse(v4.url!.absoluteString.contains("api_key"))
    }

    func testMatcherPrefersYear() {
        let candidates = [
            TMDBMovieSummary(id: 1, title: "Dune", releaseDate: "2021-09-15"),
            TMDBMovieSummary(id: 2, title: "Dune", releaseDate: "1984-12-14"),
        ]
        XCTAssertEqual(TitleMatcher.bestMatch(title: "Dune", year: 1984, in: candidates)?.id, 2)
        XCTAssertEqual(TitleMatcher.bestMatch(title: "Dune", year: nil, in: candidates)?.id, 1)
    }

    func testMatcherRejectsUnrelated() {
        let candidates = [TMDBMovieSummary(id: 1, title: "Completely Different", releaseDate: "2001-01-01")]
        XCTAssertNil(TitleMatcher.bestMatch(title: "Heat", year: 1995, in: candidates))
    }

    func testNormalize() {
        XCTAssertEqual(TitleMatcher.normalize("Amélie: Le Fabuleux & Co."), "amelie le fabuleux and co")
        XCTAssertEqual(TitleMatcher.similarity("Spider-Man", "Spider Man"), 1)
    }
}
