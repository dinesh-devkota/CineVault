import Foundation
import SwiftData

@Model
final class MediaSource {
    var id: UUID = UUID()
    var name: String = ""
    var host: String = ""
    var port: Int?
    var share: String = ""
    /// Folder inside the share to scan, e.g. "Movies". Empty scans the whole share.
    var rootPath: String = ""
    var username: String = ""
    var lastScanDate: Date?
    @Relationship(deleteRule: .cascade, inverse: \Movie.source)
    var movies: [Movie] = []

    init(name: String, host: String, port: Int? = nil, share: String, rootPath: String, username: String) {
        self.name = name
        self.host = host
        self.port = port
        self.share = share
        self.rootPath = rootPath
        self.username = username
    }

    var password: String {
        get { Keychain.get("source-\(id.uuidString)") ?? "" }
        set { Keychain.set(newValue, for: "source-\(id.uuidString)") }
    }

    var config: SourceConfig {
        SourceConfig(id: id, host: host, port: port, share: share, rootPath: rootPath,
                     username: username, password: password)
    }
}

/// Sendable snapshot of a source used off the main actor.
struct SourceConfig: Sendable, Hashable {
    let id: UUID
    let host: String
    let port: Int?
    let share: String
    let rootPath: String
    let username: String
    let password: String

    var serverURL: URL? {
        URL(string: "smb://\(host)" + (port.map { ":\($0)" } ?? ""))
    }
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
    /// User renamed / re-matched manually; rescans and auto-match won't overwrite.
    var isLocked: Bool = false

    var dateAdded: Date = Date()
    var isWatched: Bool = false
    var playbackPosition: Double = 0
    var duration: Double = 0
    var lastPlayedAt: Date?
    var isHidden: Bool = false
    var isMissing: Bool = false

    var source: MediaSource?

    init(filePath: String, fileSize: Int64, source: MediaSource) {
        self.filePath = filePath
        self.fileName = (filePath as NSString).lastPathComponent
        self.fileSize = fileSize
        self.source = source
    }

    var displayYear: String { year.map(String.init) ?? "" }

    var progress: Double {
        duration > 0 ? min(playbackPosition / duration, 1) : 0
    }

    var isInProgress: Bool { !isWatched && playbackPosition > 30 }

    func setTitle(_ newTitle: String) {
        title = newTitle
        sortTitle = Movie.makeSortTitle(newTitle)
    }

    static func makeSortTitle(_ title: String) -> String {
        let lower = title.lowercased()
        for article in ["the ", "a ", "an "] where lower.hasPrefix(article) {
            return String(lower.dropFirst(article.count))
        }
        return lower
    }
}
