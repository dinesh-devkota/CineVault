import Foundation

/// Result of parsing a movie file path.
public struct ParsedMovieName: Equatable, Sendable {
    public var title: String
    public var year: Int?
    public var tmdbID: Int?

    public init(title: String, year: Int? = nil, tmdbID: Int? = nil) {
        self.title = title
        self.year = year
        self.tmdbID = tmdbID
    }
}

/// Turns release-style file names ("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv")
/// into a clean title and year.
public enum FilenameParser {
    static let junkTokens: Set<String> = [
        "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "uhd", "hd", "fhd", "sd",
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web", "webrip", "web-dl", "webdl",
        "hdrip", "dvdrip", "dvd", "dvdscr", "hdtv", "hdcam", "cam", "ts",
        "x264", "x265", "h264", "h265", "h.264", "h.265", "hevc", "avc", "xvid", "divx", "av1", "10bit", "8bit",
        "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "flac", "mp3", "dd5.1", "ddp5.1", "5.1", "7.1",
        "hdr", "hdr10", "hdr10+", "dv", "dolby", "vision", "sdr", "imax",
        "proper", "repack", "extended", "unrated", "uncut", "remastered", "limited", "internal",
        "multi", "dual", "subbed", "dubbed", "nf", "amzn", "dsnp", "hmax", "atvp",
    ]

    public static func parse(path: String) -> ParsedMovieName {
        let components = path.split(separator: "/").map(String.init)
        let fileName = components.last ?? path
        let stem = (fileName as NSString).deletingPathExtension
        var parsed = parse(name: stem)
        // "Movie (2010)/movie.mkv" or "Movie (2010)/CD1.avi": fall back to the folder name.
        if parsed.year == nil, components.count >= 2 {
            let folder = parse(name: components[components.count - 2])
            if folder.year != nil || parsed.title.isEmpty || isGeneric(parsed.title) {
                parsed = ParsedMovieName(title: folder.title, year: folder.year, tmdbID: parsed.tmdbID ?? folder.tmdbID)
            }
        }
        return parsed
    }

    public static func parse(name raw: String) -> ParsedMovieName {
        var name = raw
        var tmdbID: Int?
        if let match = name.range(of: #"[\{\[]tmdb(id)?[-=](\d+)[\}\]]"#, options: [.regularExpression, .caseInsensitive]) {
            let digits = name[match].filter(\.isNumber)
            tmdbID = Int(digits)
            name.removeSubrange(match)
        }
        // Drop bracketed tags like [YTS] but keep "(1999)" for year detection.
        name = name.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression)
        // Separators.
        name = name.replacingOccurrences(of: #"[._]"#, with: " ", options: .regularExpression)
        name = name.replacingOccurrences(of: #"[()]"#, with: " ", options: .regularExpression)

        var tokens = name.split(separator: " ").map(String.init).filter { !$0.isEmpty }

        // Year: the last plausible year that is not the first token (e.g. "1917 2019", "2001 A Space Odyssey 1968").
        var year: Int?
        var cutIndex = tokens.count
        let currentYear = Calendar(identifier: .gregorian).component(.year, from: Date()) + 1
        for (index, token) in tokens.enumerated().reversed() where index > 0 {
            if token.count == 4, let value = Int(token), (1900...currentYear).contains(value) {
                year = value
                cutIndex = index
                break
            }
        }
        if year == nil {
            if let junk = tokens.indices.dropFirst().first(where: { isJunk(tokens[$0]) }) {
                cutIndex = junk
            }
        }
        tokens = Array(tokens[..<cutIndex])
        // Trailing release-group / dash leftovers.
        while let last = tokens.last, last == "-" || isJunk(last) { tokens.removeLast() }
        let title = tokens.joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: " -"))
        return ParsedMovieName(title: title, year: year, tmdbID: tmdbID)
    }

    static func isJunk(_ token: String) -> Bool {
        let lower = token.lowercased()
        if junkTokens.contains(lower) { return true }
        // "x264-GROUP", "WEB-DL", "DTS-HD"
        if let head = lower.split(separator: "-").first, junkTokens.contains(String(head)) { return true }
        return false
    }

    static func isGeneric(_ title: String) -> Bool {
        let lower = title.lowercased()
        return ["movie", "video", "film", "main", "feature"].contains(lower)
            || lower.range(of: #"^(cd|disc|part|pt)\s*\d+$"#, options: .regularExpression) != nil
    }
}
