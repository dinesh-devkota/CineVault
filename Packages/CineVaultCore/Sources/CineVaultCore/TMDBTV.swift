import Foundation

public struct TMDBTVSummary: Decodable, Sendable {
    public let id: Int
    public let name: String
    public let originalName: String?
    public let firstAirDate: String?
    public let posterPath: String?
    public var matchingCandidate: TMDBMovieSummary {
        var value = TMDBMovieSummary(id: id, title: name, releaseDate: firstAirDate)
        value.originalTitle = originalName
        value.posterPath = posterPath
        return value
    }
}

public struct TMDBTVSearchResponse: Decodable, Sendable { public let results: [TMDBTVSummary] }

public struct TMDBTVDetails: Decodable, Sendable {
    public let id: Int
    public let name: String
    public let overview: String?
    public let firstAirDate: String?
    public let posterPath: String?
    public let backdropPath: String?
    public let voteAverage: Double?
    public let episodeRunTime: [Int]?
    public let genres: [TMDBMovieDetails.Genre]?
    public var year: Int? { firstAirDate.flatMap { Int($0.prefix(4)) } }
}

public struct TMDBTVEpisode: Decodable, Sendable {
    public let episodeNumber: Int
    public let seasonNumber: Int
    public let name: String
    public let overview: String?
    public let runtime: Int?
    public let stillPath: String?
    public let voteAverage: Double?
}

public struct TMDBTVSeason: Decodable, Sendable {
    public let seasonNumber: Int
    public let posterPath: String?
    public let episodes: [TMDBTVEpisode]
}
