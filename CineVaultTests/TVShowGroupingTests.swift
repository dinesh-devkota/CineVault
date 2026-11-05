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

    private func twentyFourEpisodes() -> [Movie] {
        (1...8).flatMap { season in
            (1...24).map { number in
                let padded = number < 10 ? "0\(number)" : String(number)
                let folder = "TV Shows/24/24 Season \(season)"
                let path: String
                switch season {
                case 1: path = "\(folder)/24 Season 1/24 - S01xE\(padded).mp4"
                case 2...4: path = "\(folder)/24 - [\(season)x\(padded)] - Day \(season)  8 00 A.M. - 9 00 A.M.mkv"
                case 5...7: path = "\(folder)/24 - Day \(season) - [\(season)x\(padded)].mkv"
                default: path = "\(folder)/24 - 8x\(padded) - Day 8  4 00 P.M. - 5 00 P.M..mkv"
                }
                return episode(path)
            }
        }
    }

    func testTwentyFourLocalFilesBecomeOneShowWithEightCompleteSeasons() throws {
        let episodes = twentyFourEpisodes()
        XCTAssertTrue(episodes.allSatisfy { $0.seriesTitle == nil && $0.seriesTMDBID == nil && $0.seasonNumber == nil })
        XCTAssertTrue(episodes.allSatisfy { $0.mediaKind == .tvShow })
        let shows = TVShowGroup.groups(Array(episodes.reversed()))
        XCTAssertEqual(shows.count, 1)
        let show = try XCTUnwrap(shows.first)
        XCTAssertEqual(show.title, "24")
        XCTAssertEqual(show.seasons, Array(1...8))
        for season in 1...8 {
            XCTAssertEqual(show.episodes(in: season).count, 24)
            XCTAssertEqual(show.episodes(in: season).map(TVShowGroup.episode), Array(1...24))
        }
        let extras = [episode("TV Shows/24/24 Season 1/24 EXTRAS/Behind the Scenes.mp4"),
                      episode("TV Shows/24/24 Season 1/24 EXTRAS/Cast Interviews.mp4")]
        XCTAssertTrue(extras.allSatisfy { $0.episodeInfo == nil })
        XCTAssertEqual(CollectionRowItem.grouped(episodes + extras).count, 3)
    }

    func testCollectionRowsGroupBeforeTheTwentyTileLimitAndPreserveOrder() throws {
        let episodes = twentyFourEpisodes()
        let movies = (1...25).map { episode("Movies/Movie \($0).mp4") }
        let items = CollectionRowItem.grouped([movies[0]] + episodes + Array(movies.dropFirst()))
        XCTAssertEqual(items.count, 20)
        XCTAssertEqual(items.first?.id, "movie:\(movies[0].id)")
        guard case .show(let show) = items[1] else { return XCTFail("Episodes must occupy one show tile.") }
        XCTAssertEqual(show.episodes.count, 192)
        XCTAssertEqual(show.seasons, Array(1...8))
        XCTAssertEqual(items.last?.id, "movie:\(movies[18].id)")
    }

    func testCollectionRowsKeepWatchlistAndUnwatchedEpisodeFilters() throws {
        let episodes = Array(twentyFourEpisodes().prefix(3))
        episodes[0].isWatchlisted = true
        episodes[2].isWatchlisted = true
        episodes[2].isWatched = true
        let watchlist = CollectionRowItem.grouped(LibraryQuery.watchlist(episodes))
        XCTAssertEqual(watchlist.count, 1)
        guard case .show(let saved) = try XCTUnwrap(watchlist.first) else { return XCTFail("Expected a show tile.") }
        XCTAssertEqual(Set(saved.episodes.map(\.id)), Set([episodes[0].id, episodes[2].id]))
        let unwatched = CollectionRowItem.grouped(episodes.filter { !$0.isWatched })
        guard case .show(let pending) = try XCTUnwrap(unwatched.first) else { return XCTFail("Expected a show tile.") }
        XCTAssertEqual(pending.episodes.map(\.id), [episodes[0].id, episodes[1].id])
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
