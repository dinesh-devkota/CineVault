import XCTest
@testable import CineVaultCore

final class FilenameParserTests: XCTestCase {
    func testReleaseName() {
        XCTAssertEqual(FilenameParser.parse(path: "Movies/The.Matrix.1999.1080p.BluRay.x264-GRP.mkv"),
                       ParsedMovieName(title: "The Matrix", year: 1999))
    }

    func testParenthesizedYear() {
        XCTAssertEqual(FilenameParser.parse(path: "Inception (2010).mp4"),
                       ParsedMovieName(title: "Inception", year: 2010))
    }

    func testTitleThatIsAYear() {
        XCTAssertEqual(FilenameParser.parse(path: "1917 (2019) [2160p].mkv"),
                       ParsedMovieName(title: "1917", year: 2019))
        XCTAssertEqual(FilenameParser.parse(path: "2001.A.Space.Odyssey.1968.mkv"),
                       ParsedMovieName(title: "2001 A Space Odyssey", year: 1968))
    }

    func testFolderFallback() {
        XCTAssertEqual(FilenameParser.parse(path: "Movies/Heat (1995)/movie.mkv"),
                       ParsedMovieName(title: "Heat", year: 1995))
        XCTAssertEqual(FilenameParser.parse(path: "Movies/Heat (1995)/CD1.avi"),
                       ParsedMovieName(title: "Heat", year: 1995))
    }

    func testNoYearStripsJunk() {
        XCTAssertEqual(FilenameParser.parse(path: "Some_Movie_720p_WEB-DL.mp4"),
                       ParsedMovieName(title: "Some Movie", year: nil))
    }

    func testTMDBTag() {
        XCTAssertEqual(FilenameParser.parse(path: "Alien (1979) {tmdb-348}.mkv"),
                       ParsedMovieName(title: "Alien", year: 1979, tmdbID: 348))
    }

    func testBracketTags() {
        XCTAssertEqual(FilenameParser.parse(path: "[YTS] Parasite.2019.mp4"),
                       ParsedMovieName(title: "Parasite", year: 2019))
    }
}

final class VideoFileTests: XCTestCase {
    let big: Int64 = 700 * 1024 * 1024

    func testIndexing() {
        XCTAssertTrue(VideoFile.shouldIndex(path: "Movies/Heat (1995)/Heat.mkv", size: big))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/Heat/Heat-sample.mkv", size: big))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/Heat/Sample/x.mkv", size: big))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/@eaDir/x.mp4", size: big))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/._Heat.mp4", size: big))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/Heat.mp4", size: 1024))
        XCTAssertFalse(VideoFile.shouldIndex(path: "Movies/Heat.srt", size: big))
    }

    func testPlayability() {
        XCTAssertTrue(VideoFile.isNativelyPlayable(path: "a.MP4"))
        XCTAssertFalse(VideoFile.isNativelyPlayable(path: "a.mkv"))
        XCTAssertEqual(VideoFile.contentType(path: "a.mov"), "com.apple.quicktime-movie")
    }
}
