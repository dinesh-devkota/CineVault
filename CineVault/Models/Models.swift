import CineVaultCore
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

    private var passwordAccount: String { "source-\(id.uuidString)" }

    func password() throws -> String {
        try Keychain.get(passwordAccount) ?? ""
    }

    func setPassword(_ value: String) throws {
        try Keychain.set(value, for: passwordAccount)
    }

    func config() throws -> SourceConfig {
        var secret = ""
        if !username.isEmpty { secret = try password() }
        return SourceConfig(id: id, host: host, port: port, share: share, rootPath: rootPath,
                            username: username, password: secret)
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
    var mediaKind: MediaKind {
        MediaFile.kind(path: filePath, genres: genres, matched: metadataFetched && tmdbID != nil,
                       isEpisode: episodeInfo != nil)
    }
    var episodeInfo: EpisodeInfo? {
        guard !MediaFile.isAudio(path: filePath) else { return nil }
        let local = EpisodeParser.parse(path: filePath)
        guard let name = seriesTitle ?? local?.seriesTitle, !name.isEmpty,
              let season = seasonNumber ?? local?.season, let episode = episodeNumber ?? local?.episode,
              (0...999).contains(season), (0...999).contains(episode) else { return nil }
        return EpisodeInfo(seriesTitle: name, season: season, episode: episode,
                           year: seriesTMDBID != nil ? year : local?.year)
    }
    var seriesKey: String? {
        guard let info = episodeInfo else { return nil }
        if let seriesTMDBID { return "tmdb:\(seriesTMDBID)" }
        return "local:\(TitleMatcher.normalize(info.seriesTitle))|\(info.year.map(String.init) ?? "")"
    }
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
    var seriesTitle: String?
    var seriesTMDBID: Int?
    var seasonNumber: Int?
    var episodeNumber: Int?
    var seasonPosterPath: String?
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
    var isWatchlisted: Bool = false

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

    func resetToFileName() {
        let parsed = FilenameParser.parse(path: filePath)
        parsedTitle = parsed.title
        parsedYear = parsed.year
        isLocked = false
        tmdbID = parsed.tmdbID
        seriesTitle = nil
        seriesTMDBID = nil
        seasonNumber = nil
        episodeNumber = nil
        seasonPosterPath = nil
        setTitle(parsedTitle.isEmpty ? fileName : parsedTitle)
        year = parsedYear
        overview = nil
        tagline = nil
        posterPath = nil
        backdropPath = nil
        genres = []
        runtimeMinutes = nil
        rating = nil
        metadataFetched = false
    }

    static func makeSortTitle(_ title: String) -> String {
        let lower = title.lowercased()
        for article in ["the ", "a ", "an "] where lower.hasPrefix(article) {
            return String(lower.dropFirst(article.count))
        }
        return lower
    }
}
