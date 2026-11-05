import XCTest
@testable import CineVaultCore

final class EpisodeParserTests: XCTestCase {
    func testPrefersSeriesFolderAndPreservesYear() {
        XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/The Example (2022)/Season 01/Ambiguous.S01E02.1080p.mkv"),
                       EpisodeInfo(seriesTitle: "The Example", season: 1, episode: 2, year: 2022))
    }
    func testFilenameMarkersAndSpecials() {
        for path in ["Show.Name.S01E02.Title.mkv", "Show Name 1x02 Title.mp4"] {
            XCTAssertEqual(EpisodeParser.parse(path: path), EpisodeInfo(seriesTitle: "Show Name", season: 1, episode: 2))
        }
        XCTAssertEqual(EpisodeParser.parse(path: "Show.Name.S00E03.Special.mkv"), EpisodeInfo(seriesTitle: "Show Name", season: 0, episode: 3))
        XCTAssertEqual(EpisodeParser.parse(path: "Show.Name.S999E999.mkv"), EpisodeInfo(seriesTitle: "Show Name", season: 999, episode: 999))
    }
    func testNumberedFilesRequireSeasonFolder() {
        XCTAssertEqual(EpisodeParser.parse(path: #"TV Shows\Show Name\Season02\02 - Episode title.mkv"#),
                       EpisodeInfo(seriesTitle: "Show Name", season: 2, episode: 2))
        XCTAssertNil(EpisodeParser.parse(path: "Movies/02 - Episode title.mkv"))
        XCTAssertNil(EpisodeParser.parse(path: "TV Shows/Season02/02 - Title.mkv"))
    }
    func testMeaningfulParentFallback() {
        XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/Show Name/S01E02.mkv"), EpisodeInfo(seriesTitle: "Show Name", season: 1, episode: 2))
        for path in ["TV Shows/S01E02.mkv", "Movies/S01E02.mkv", "Documentaries/S01E02.mkv", "S01E02.mkv"] {
            XCTAssertNil(EpisodeParser.parse(path: path), path)
        }
    }
    func testRejectsYearsResolutionsAndOutOfRangeNumbers() {
        for path in ["Movies/Film.2024.1080p.mkv", "Show/Season02/2024 - Film.mkv", "Show/Season02/1080p.mkv",
                     "Show.1920x1080.mkv", "Show.S1000E01.mkv", "Show.S01E1000.mkv", "Show/Season1000/02 - Title.mkv"] {
            XCTAssertNil(EpisodeParser.parse(path: path), path)
        }
    }
    func testTVPrecedesGenresAndMatchedGenresPrecedeFolders() {
        XCTAssertEqual(MediaFile.kind(path: "Documentaries/Show.S01E02.mp4", genres: ["Documentary"], matched: true), .tvShow)
        XCTAssertEqual(MediaFile.kind(path: "Movies/Film.mp4", genres: ["Documentary"], matched: true), .documentary)
        XCTAssertEqual(MediaFile.kind(path: "Movies/Concert.mp4", genres: ["Music"], matched: true), .music)
        XCTAssertEqual(MediaFile.kind(path: "Documentaries/Film.mp4", genres: ["Drama"], matched: true), .movie)
        XCTAssertEqual(MediaFile.kind(path: "Documentaries/Film.mp4"), .documentary)
        XCTAssertEqual(MediaFile.kind(path: "Music/Show.S01E02.mp3"), .music)
    }

    func testGroupsBracketedEpisodesAcrossPrefixedSeasonFolders() {
        for season in [1, 5, 9] {
            let path = "TV Shows/24/24 Season \(season)/24 - Day \(season) - [\(season)x23].mkv"
            XCTAssertEqual(EpisodeParser.parse(path: path), EpisodeInfo(seriesTitle: "24", season: season, episode: 23), path)
        }
        XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/Example (2022)/Example Season 3/Unrelated - [3x04].mkv"),
                       EpisodeInfo(seriesTitle: "Example", season: 3, episode: 4, year: 2022))
    }

    func testAcceptsBracketParenAndSpacedMarkersWithoutChangingTitle() {
        for filename in ["Show.Name.[S01E02].mkv", "Show Name (1x02).mp4", "Show Name [S01 E02].mkv", "Show Name (s01 e02).mp4"] {
            XCTAssertEqual(EpisodeParser.parse(path: filename), EpisodeInfo(seriesTitle: "Show Name", season: 1, episode: 2), filename)
        }
    }

    func testSeriesFolderWinsOverSeasonSpecificFilenameWithoutSeasonFolder() {
        XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/Example/Day Three/Other.Title.[3x04].mkv"),
                       EpisodeInfo(seriesTitle: "Example", season: 3, episode: 4))
        XCTAssertEqual(EpisodeParser.parse(path: #"TV Shows\Example\Example Season 2\03 - Episode title.mp4"#),
                       EpisodeInfo(seriesTitle: "Example", season: 2, episode: 3))
        XCTAssertEqual(EpisodeParser.parse(path: "Example/Example Season 2/[2x03].mp4"),
                       EpisodeInfo(seriesTitle: "Example", season: 2, episode: 3))
    }

    func testBracketedMovieResolutionsAndUnmarkedFilesRemainMovies() {
        for path in ["Movies/Film.[1920x1080].mkv", "Movies/Film.(640x480).mp4", "Movies/Film (2024) [1080p].mkv",
                     "TV Shows/Example/Example Season 2/Trailer.mp4", "TV Shows/Example/Example Season 2/2024 - Movie.mp4"] {
            XCTAssertNil(EpisodeParser.parse(path: path), path)
        }
    }

    func testGroupsAllEightObservedSeasonLayoutsAndLeavesUnnumberedExtrasOut() {
        for season in 1...8 {
            for episode in 1...24 {
                let number = episode < 10 ? "0\(episode)" : "\(episode)"
                let relative: String
                switch season {
                case 1: relative = "24 Season 1/24 - S01xE\(number).mp4"
                case 2, 3, 4: relative = "24 - [\(season)x\(number)] - Day \(season)  8 00 A.M. - 9 00 A.M.mkv"
                case 5, 6, 7: relative = "24 - Day \(season) - [\(season)x\(number)].mkv"
                default: relative = "24 - 8x\(number) - Day 8  4 00 P.M. - 5 00 P.M..mkv"
                }
                let path = "TV Shows/24/24 Season \(season)/\(relative)"
                XCTAssertEqual(EpisodeParser.parse(path: path), EpisodeInfo(seriesTitle: "24", season: season, episode: episode), path)
            }
        }
        XCTAssertEqual(EpisodeParser.parse(path: "Example (2022)/Example Season 1/Example Season 1/Other - S01xE02.mp4"),
                       EpisodeInfo(seriesTitle: "Example", season: 1, episode: 2, year: 2022))
        for name in ["Behind the Scenes", "Cast Interviews"] {
            XCTAssertNil(EpisodeParser.parse(path: "TV Shows/24/24 Season 1/24 EXTRAS/\(name).mp4"))
        }
    }

    func testExplicitSeasonParentKeepsShowsUnderCategoryFoldersSeparate() {
        for title in ["Show A", "Show B"] {
            let filename = title.replacingOccurrences(of: " ", with: ".")
            XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/Completed/\(title)/Season 1/\(filename).S01E01.mkv"),
                           EpisodeInfo(seriesTitle: title, season: 1, episode: 1))
            XCTAssertEqual(EpisodeParser.parse(path: "TV Shows/Completed/\(title)/\(title) Season 1/\(title) Season 1/Other.S01xE01.mp4"),
                           EpisodeInfo(seriesTitle: title, season: 1, episode: 1))
        }
    }
}
