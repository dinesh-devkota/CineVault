import XCTest
@testable import CineVaultCore

final class ScanExclusionsTests: XCTestCase {
    func testCanonicalizesRelativeFoldersWithoutChangingDisplayCase() {
        XCTAssertEqual(ScanExclusions.normalizeFolder(path: "  /Extras\\Trailers//  "), "Extras/Trailers")
        XCTAssertEqual(ScanExclusions.normalizeFolder(path: " Featurettes / Behind the Scenes / "), "Featurettes/Behind the Scenes")
        XCTAssertEqual(ScanExclusions.normalizeFolder(path: " /Éxtras//Trailers/ "), "Éxtras/Trailers")
        XCTAssertEqual(ScanExclusions.normalizeFolder(path: ".hidden"), ".hidden")
    }

    func testRejectsTraversalAbsoluteLocationsAndRootWideRules() {
        for path in ["", "  ", "/", "\\", ".", "./Extras", "Extras/../Movies", "..\\Movies",
                     "smb://nas/share", "file:///Media", "C:\\Media", "\\\\nas\\share", "//nas/share",
                     "*", "**", "**/*", "?", "Extras/A\u{0}B", "Extras/A\nB"] {
            XCTAssertNil(ScanExclusions.normalizeFolder(path: path), path)
        }
    }

    func testMatchesOnlyTheExactDirectoryAndItsDescendants() {
        for path in ["Media/Movies/Extras", "media\\movies\\EXTRAS\\Deep", " /MEDIA/Movies/Extras/ "] {
            XCTAssertTrue(ScanExclusions.isDirectoryExcluded(path: path, rootPath: " /Media\\Movies/ ", ignoredFolders: ["Extras"]), path)
        }
        for path in ["Media/Movies", "Media/Movies/Extras 2", "Media/Movies/My Extras", "Media/Movies2/Extras", "Other/Extras"] {
            XCTAssertFalse(ScanExclusions.isDirectoryExcluded(path: path, rootPath: "Media/Movies", ignoredFolders: ["Extras"]), path)
        }
    }

    func testNestedRulesAreRelativeToTheSelectedRoot() {
        let rules = ["Bonus/Trailers"]
        XCTAssertTrue(ScanExclusions.isDirectoryExcluded(path: "Media/Bonus/Trailers", rootPath: "Media", ignoredFolders: rules))
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Media/Bonus/Trailers/Deep/clip.mp4", rootPath: "Media", ignoredFolders: rules))
        for path in ["Media/Other/Bonus/Trailers/clip.mp4", "Media/Bonus/clip.mp4", "Media/Bonus/Trailers 2/clip.mp4"] {
            XCTAssertFalse(ScanExclusions.isFileExcluded(path: path, rootPath: "Media", ignoredFolders: rules), path)
        }
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Media/Bonus/Trailers/clip.mp4", rootPath: "Media", ignoredFolders: ["Media/Bonus/Trailers"]))
    }

    func testFileFallbackMatchesParentsNotFileNames() {
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Movies/Extras/clip.mp4", rootPath: "Movies", ignoredFolders: ["Extras"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Movies/Extras", rootPath: "Movies", ignoredFolders: ["Extras"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Movies/Extras.mp4", rootPath: "Movies", ignoredFolders: ["Extras"]))
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Extras/clip.mp4", rootPath: "", ignoredFolders: ["Extras"]))
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Extras/clip.mp4", rootPath: "/", ignoredFolders: ["Extras"]))
        XCTAssertFalse(ScanExclusions.isDirectoryExcluded(path: "", rootPath: "", ignoredFolders: ["Extras"]))
    }

    func testWildcardCharactersAreLiteralAndInvalidRulesNeverMatch() {
        XCTAssertEqual(ScanExclusions.normalizeFolder(path: "Extras*"), "Extras*")
        for path in ["Extras 2/clip.mp4", "Extras/clip.mp4"] {
            XCTAssertFalse(ScanExclusions.isFileExcluded(path: path, rootPath: "", ignoredFolders: ["Extras*"]))
        }
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Extras*/clip.mp4", rootPath: "", ignoredFolders: ["Extras*"]))
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "[Bonus]/clip.mp4", rootPath: "", ignoredFolders: ["[Bonus]"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Bonus/clip.mp4", rootPath: "", ignoredFolders: ["[Bonus]"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Extras/clip.mp4", rootPath: "", ignoredFolders: ["", "/", "..", "**/*"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Media/Extras/clip.mp4", rootPath: "../Media", ignoredFolders: ["Extras"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Media/../Extras/clip.mp4", rootPath: "Media", ignoredFolders: ["Extras"]))
    }

    func testRemovingRulesImmediatelyIncludesPreviouslyIgnoredPaths() {
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Media/Extras/clip.mp4", rootPath: "Media", ignoredFolders: ["Extras"]))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Media/Extras/clip.mp4", rootPath: "Media", ignoredFolders: []))
    }

    func testExactFileRulesDoNotHideSiblingsOrDescendants() {
        XCTAssertEqual(ScanExclusions.normalizeFile(path: " /Season 1\\Clip.mp4/ "), "Season 1/Clip.mp4")
        XCTAssertNil(ScanExclusions.normalizeFile(path: "../Clip.mp4"))
        let rules = ["Season 1/Clip.mp4"]
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Media/Season 1/Clip.mp4", rootPath: "Media", ignoredFolders: [], ignoredFiles: rules))
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "MEDIA\\season 1\\CLIP.MP4", rootPath: " /media/ ", ignoredFolders: [], ignoredFiles: rules))
        for path in ["Media/Season 1/Clip2.mp4", "Media/Season 1/Clip.mp4.bak", "Media/Season 2/Clip.mp4",
                     "Media/Season 1/Clip.mp4/child.mp4", "Other/Season 1/Clip.mp4"] {
            XCTAssertFalse(ScanExclusions.isFileExcluded(path: path, rootPath: "Media", ignoredFolders: [], ignoredFiles: rules), path)
        }
        XCTAssertTrue(ScanExclusions.isFileExcluded(path: "Media/Bonus/Deep/anything.mp4", rootPath: "Media", ignoredFolders: ["Bonus"], ignoredFiles: rules))
        XCTAssertFalse(ScanExclusions.isFileExcluded(path: "Media/Season 1/Clip.mp4", rootPath: "Media", ignoredFolders: [], ignoredFiles: []))
    }
}
