import CineVaultCore
import XCTest
@testable import CineVault

@MainActor
final class TVShowGroupingTests: XCTestCase {
    private func episode(_ path: String) -> Movie {
        let source = MediaSource(name: "NAS", host: "test.invalid", share: "Media", rootPath: "", username: "")
        let movie = Movie(filePath: path, fileSize: 10, source: source)
        movie.resetToFileName()
        return movie
    }

    func testLocalSeasonsAndEpisodeOrderingWorkWithoutMetadata() {
        let later = episode("TV/Example/Season 10/Example.S10E02.mp4")
        let earlier = episode("TV/Example/Season 10/Example.S10E01.mp4")
        let secondSeason = episode("TV/Example/Season 2/Example.S02E01.mp4")
        let show = TVShowGroup.groups([later, earlier, secondSeason]).first!
        XCTAssertEqual(show.title, "Example")
        XCTAssertEqual(show.seasons, [2, 10])
        XCTAssertEqual(show.episodes(in: 10).map(\.id), [earlier.id, later.id])
    }

    func testRemakeYearsStaySeparateWithoutMetadata() {
        let original = episode("TV/Example (1990)/Season 1/Example.S01E01.mp4")
        let remake = episode("TV/Example (2020)/Season 1/Example.S01E01.mp4")
        XCTAssertEqual(TVShowGroup.groups([original, remake]).count, 2)
    }

    func testPartialMetadataKeepsLocalEpisodesTogether() {
        let fetched = episode("TV/Local Name/Season 1/Local.Name.S01E01.mp4")
        fetched.seriesTMDBID = 42
        fetched.seriesTitle = "Canonical Title"
        fetched.year = 2020
        fetched.posterPath = "/show.jpg"
        let pending = episode("TV/Local Name/Season 1/Local.Name.S01E02.mp4")
        let shows = TVShowGroup.groups([pending, fetched])
        XCTAssertEqual(shows.count, 1)
        XCTAssertEqual(shows.first?.id, "tmdb:42")
        XCTAssertEqual(shows.first?.title, "Canonical Title")
        XCTAssertEqual(shows.first?.posterPath, "/show.jpg")
    }

    func testAmbiguousMatchesAndCallerFiltersArePreserved() {
        let first = episode("TV/Example/Season 1/Example.S01E01.mp4")
        first.seriesTMDBID = 1
        let second = episode("TV/Example/Season 1/Example.S01E02.mp4")
        second.seriesTMDBID = 2
        let pending = episode("TV/Example/Season 1/Example.S01E03.mp4")
        XCTAssertEqual(TVShowGroup.groups([first, second, pending]).count, 3)
        pending.isHidden = true
        XCTAssertEqual(TVShowGroup.groups([pending]).first?.episodes.map(\.id), [pending.id])
    }
}
