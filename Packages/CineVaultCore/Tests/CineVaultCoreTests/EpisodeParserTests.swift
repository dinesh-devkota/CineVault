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
}
