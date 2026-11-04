import Foundation
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

public enum MediaKind: String, CaseIterable, Hashable, Sendable {
    case movie, tvShow, documentary, music
    public var label: String {
        switch self { case .movie: return "Movies"; case .tvShow: return "TV Shows"; case .documentary: return "Documentaries"; case .music: return "Music" }
    }
}

public enum MediaFile {
    private static let audio: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "opus", "wma"]
    private static func parts(_ path: String) -> [String] {
        path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init)
    }
    public static func isAudio(path: String) -> Bool { audio.contains((path as NSString).pathExtension.lowercased()) }
    public static func kind(path: String, genres: [String] = [], matched: Bool = false, isEpisode: Bool? = nil) -> MediaKind {
        if isAudio(path: path) { return .music }
        if isEpisode ?? (EpisodeParser.parse(path: path) != nil) { return .tvShow }
        if matched {
            if genres.contains(where: { $0.caseInsensitiveCompare("Documentary") == .orderedSame }) { return .documentary }
            if genres.contains(where: { $0.caseInsensitiveCompare("Music") == .orderedSame }) { return .music }
            return .movie
        }
        if parts(path).dropLast().contains(where: { ["documentary", "documentaries"].contains($0.lowercased()) }) { return .documentary }
        return .movie
    }
    public static func shouldSkipDirectory(name: String) -> Bool {
        let lower = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return lower.first.map { ".@#$".contains($0) } == true ||
            ["system volume information", "lost+found", "recycler", "recycle.bin"].contains(lower)
    }
    public static func shouldIndex(path: String, size: Int64) -> Bool {
        guard size > 0, !parts(path).contains(where: { shouldSkipDirectory(name: $0) }) else { return false }
        return audio.contains((path as NSString).pathExtension.lowercased()) ||
            VideoFile.shouldIndex(path: path.replacingOccurrences(of: "\\", with: "/"), size: size, minimumSize: 1)
    }
    public static func isPlayable(path: String) -> Bool {
        let ext = (path as NSString).pathExtension.lowercased()
        return ["mp3", "m4a", "aac", "flac", "wav", "aiff", "aif"].contains(ext) || VideoFile.isNativelyPlayable(path: path)
    }
    public static func contentType(path: String) -> String {
        #if canImport(UniformTypeIdentifiers)
        if let type = UTType(filenameExtension: (path as NSString).pathExtension) { return type.identifier }
        #endif
        return VideoFile.contentType(path: path)
    }
}
