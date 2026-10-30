import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct TMDBMovieSummary: Codable, Sendable, Equatable {
    public var id: Int
    public var title: String
    public var originalTitle: String?
    public var releaseDate: String?
    public var overview: String?
    public var posterPath: String?
    public var backdropPath: String?
    public var voteAverage: Double?

    public init(id: Int, title: String, releaseDate: String? = nil) {
        self.id = id
        self.title = title
        self.releaseDate = releaseDate
    }

    public var year: Int? { releaseDate.flatMap { Int($0.prefix(4)) } }
}

public struct TMDBSearchResponse: Codable, Sendable {
    public var results: [TMDBMovieSummary]
}

public struct TMDBMovieDetails: Codable, Sendable {
    public struct Genre: Codable, Sendable { public var name: String }
    public var id: Int
    public var title: String
    public var overview: String?
    public var tagline: String?
    public var releaseDate: String?
    public var runtime: Int?
    public var voteAverage: Double?
    public var posterPath: String?
    public var backdropPath: String?
    public var genres: [Genre]?

    public var year: Int? { releaseDate.flatMap { Int($0.prefix(4)) } }
}

public enum TMDB {
    public static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    /// Accepts either a v3 API key (query parameter) or a v4 read access token (Bearer).
    public static func request(path: String, query: [String: String] = [:], apiKey: String) -> URLRequest {
        var components = URLComponents(string: "https://api.themoviedb.org/3" + path)!
        var items = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        let isBearer = apiKey.count > 40
        if !isBearer { items.append(URLQueryItem(name: "api_key", value: apiKey)) }
        components.queryItems = items.isEmpty ? nil : items
        var request = URLRequest(url: components.url!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if isBearer { request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization") }
        return request
    }

    public static func searchRequest(title: String, year: Int?, apiKey: String) -> URLRequest {
        var query = ["query": title, "include_adult": "false"]
        if let year { query["year"] = String(year) }
        return request(path: "/search/movie", query: query, apiKey: apiKey)
    }

    public static func detailsRequest(id: Int, apiKey: String) -> URLRequest {
        request(path: "/movie/\(id)", apiKey: apiKey)
    }

    public static func imageURL(_ path: String?, size: String = "w500") -> URL? {
        guard let path, !path.isEmpty else { return nil }
        return URL(string: "https://image.tmdb.org/t/p/\(size)\(path)")
    }
}

/// Picks the TMDB search result that best matches a parsed file name.
public enum TitleMatcher {
    public static func normalize(_ title: String) -> String {
        let folded = title.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).lowercased()
            .replacingOccurrences(of: "&", with: " and ")
        let cleaned = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(cleaned).split(separator: " ").joined(separator: " ")
    }

    /// 0...1, based on Levenshtein distance of normalized titles.
    public static func similarity(_ a: String, _ b: String) -> Double {
        let x = Array(normalize(a)), y = Array(normalize(b))
        if x.isEmpty || y.isEmpty { return x == y ? 1 : 0 }
        var previous = Array(0...y.count)
        for i in 1...x.count {
            var current = [i] + Array(repeating: 0, count: y.count)
            for j in 1...y.count {
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1))
            }
            previous = current
        }
        return 1 - Double(previous[y.count]) / Double(max(x.count, y.count))
    }

    public static func score(_ candidate: TMDBMovieSummary, title: String, year: Int?) -> Double {
        var score = max(similarity(candidate.title, title), similarity(candidate.originalTitle ?? "", title))
        if let year, let candidateYear = candidate.year {
            switch abs(year - candidateYear) {
            case 0: score += 0.3
            case 1: score += 0.15
            default: score -= 0.3
            }
        }
        return score
    }

    public static func bestMatch(title: String, year: Int?, in candidates: [TMDBMovieSummary],
                                 threshold: Double = 0.5) -> TMDBMovieSummary? {
        // Earlier results win ties because TMDB orders by popularity.
        var best: (TMDBMovieSummary, Double)?
        for candidate in candidates {
            let value = score(candidate, title: title, year: year)
            if value > (best?.1 ?? -1) { best = (candidate, value) }
        }
        guard let best, best.1 >= threshold else { return nil }
        return best.0
    }
}
