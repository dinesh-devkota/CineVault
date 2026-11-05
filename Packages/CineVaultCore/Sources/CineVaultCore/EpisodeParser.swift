import Foundation

public struct EpisodeInfo: Equatable, Sendable {
    public let seriesTitle: String
    public let season: Int
    public let episode: Int
    public let year: Int?
    public init(seriesTitle: String, season: Int, episode: Int, year: Int? = nil) {
        self.seriesTitle = seriesTitle; self.season = season; self.episode = episode; self.year = year
    }
}

public enum EpisodeParser {
    private static let markers = [
        #"(?<![\p{L}\p{N}])S\s*(\d{1,4})[\s._-]*x?[\s._-]*E\s*(\d{1,4})(?=$|[^\p{L}\p{N}]|E\s*\d)"#,
        // Restrict x-style season numbers so video dimensions cannot become episodes.
        #"(?<![\p{L}\p{N}])(\d{1,2})\s*x\s*(\d{1,4})(?=$|[^\p{L}\p{N}])"#
    ].compactMap { try? NSRegularExpression(pattern: $0, options: .caseInsensitive) }
    private static let seasonFolder = try? NSRegularExpression(pattern: #"^(?:(.+?)[ ._-]+)?Season[ ._-]*(\d{1,4})$"#, options: .caseInsensitive)
    private static let numberedFile = try? NSRegularExpression(pattern: #"^(\d{1,4})\s*[-–—]\s*\S"#)
    private static let genericFolders: Set<String> = ["tv", "tvshows", "tvseries", "shows", "series", "television",
        "movies", "movie", "documentaries", "documentary", "videos", "video", "media", "library", "seasons"]
    private static let tvRoots: Set<String> = ["tv", "tvshows", "tvseries", "shows", "series", "television"]
    private static func match(_ expression: NSRegularExpression?, _ text: String) -> NSTextCheckingResult? {
        expression?.firstMatch(in: text, range: NSRange(text.startIndex..<text.endIndex, in: text))
    }
    private static func capture(_ result: NSTextCheckingResult, _ index: Int, _ text: String) -> String? {
        guard let range = Range(result.range(at: index), in: text) else { return nil }
        return String(text[range])
    }
    private static func isGeneric(_ value: String) -> Bool {
        genericFolders.contains(normalized(value))
    }
    private static func normalized(_ value: String) -> String { value.lowercased().filter { $0.isLetter || $0.isNumber } }
    public static func parse(path: String) -> EpisodeInfo? {
        let parts = path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let filename = parts.last else { return nil }
        let parents = Array(parts.dropLast())
        let folderIndex = parents.indices.reversed().first { match(seasonFolder, parents[$0].trimmingCharacters(in: .whitespaces)) != nil }
        var folderSeason: Int?
        if let index = folderIndex {
            let folder = parents[index].trimmingCharacters(in: .whitespaces)
            guard let result = match(seasonFolder, folder), let text = capture(result, 2, folder),
                  let value = Int(text), (0...999).contains(value) else { return nil }
            folderSeason = value
        }
        let marker = markers.lazy.compactMap { match($0, filename) }.first
        let season: Int
        let episode: Int
        var prefix: String?
        if let marker {
            guard let seasonText = capture(marker, 1, filename), let episodeText = capture(marker, 2, filename),
                  let seasonValue = Int(seasonText), let episodeValue = Int(episodeText),
                  (0...999).contains(seasonValue), (0...999).contains(episodeValue),
                  let range = Range(marker.range, in: filename) else { return nil }
            season = seasonValue; episode = episodeValue
            let value = String(filename[..<range.lowerBound]).trimmingCharacters(in: CharacterSet(charactersIn: " ._-[](){}"))
            prefix = value.isEmpty ? nil : value
        } else {
            guard let folderSeason, let result = match(numberedFile, filename),
                  let text = capture(result, 1, filename), let value = Int(text), (0...999).contains(value) else { return nil }
            season = folderSeason; episode = value
        }
        // The library's series folder is stable when filenames use season-specific titles.
        var libraryTitle: String?
        if let root = parents.indices.reversed().first(where: { tvRoots.contains(normalized(parents[$0])) }),
           root + 1 < parents.count {
            let candidate = parents[root + 1]
            if !isGeneric(candidate), match(seasonFolder, candidate.trimmingCharacters(in: .whitespaces)) == nil {
                libraryTitle = candidate
            }
        }
        var folderTitle: String?
        if let index = folderIndex {
            var seriesIndex = index - 1
            while seriesIndex >= 0, match(seasonFolder, parents[seriesIndex].trimmingCharacters(in: .whitespaces)) != nil {
                seriesIndex -= 1
            }
            if seriesIndex >= 0, !isGeneric(parents[seriesIndex]) { folderTitle = parents[seriesIndex] }
        }
        var seasonTitle: String?
        if let index = folderIndex {
            let folder = parents[index].trimmingCharacters(in: .whitespaces)
            if let result = match(seasonFolder, folder), let value = capture(result, 1, folder) {
                let candidate = value.trimmingCharacters(in: CharacterSet(charactersIn: " ._-"))
                if !candidate.isEmpty, !isGeneric(candidate) { seasonTitle = candidate }
            }
        }
        let parentTitle = parents.last.flatMap { parent -> String? in
            isGeneric(parent) || match(seasonFolder, parent.trimmingCharacters(in: .whitespaces)) != nil ? nil : parent
        }
        guard let rawTitle = folderTitle ?? libraryTitle ?? seasonTitle ?? prefix ?? parentTitle else { return nil }
        let parsed = FilenameParser.parse(name: rawTitle)
        let title = parsed.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return nil }
        return EpisodeInfo(seriesTitle: title, season: season, episode: episode, year: parsed.year)
    }
}
