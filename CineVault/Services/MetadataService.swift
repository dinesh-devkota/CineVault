import CineVaultCore
import Foundation
import SwiftData

/// Fetches posters and details from TMDB using the user's API key.
@MainActor
final class MetadataService {
    static let shared = MetadataService()
    static let apiKeyAccount = "tmdb-api-key"

    var apiKey: String {
        get { Keychain.get(Self.apiKeyAccount) ?? "" }
        set { Keychain.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), for: Self.apiKeyAccount) }
    }

    var hasKey: Bool { !apiKey.isEmpty }

    private func load<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return try TMDB.decoder.decode(T.self, from: data)
    }

    func search(title: String, year: Int?) async throws -> [TMDBMovieSummary] {
        let key = apiKey
        var results = try await load(TMDB.searchRequest(title: title, year: year, apiKey: key), as: TMDBSearchResponse.self).results
        if results.isEmpty, year != nil {
            results = try await load(TMDB.searchRequest(title: title, year: nil, apiKey: key), as: TMDBSearchResponse.self).results
        }
        return results
    }

    /// Applies TMDB details to a movie. `force` is used by manual re-match.
    func apply(tmdbID: Int, to movie: Movie, force: Bool = false) async throws {
        let details = try await load(TMDB.detailsRequest(id: tmdbID, apiKey: apiKey), as: TMDBMovieDetails.self)
        movie.tmdbID = details.id
        if force || !movie.isLocked {
            movie.setTitle(details.title)
            movie.year = details.year ?? movie.year
        }
        movie.overview = details.overview
        movie.tagline = details.tagline
        movie.runtimeMinutes = details.runtime
        movie.rating = details.voteAverage
        movie.posterPath = details.posterPath
        movie.backdropPath = details.backdropPath
        movie.genres = details.genres?.map(\.name) ?? []
        movie.metadataFetched = true
    }

    /// Auto-match: uses a {tmdb-123} tag if present, otherwise title/year search.
    func match(_ movie: Movie) async throws {
        if let id = movie.tmdbID {
            try await apply(tmdbID: id, to: movie)
            return
        }
        let title = movie.isLocked ? movie.title : movie.parsedTitle
        let year = movie.isLocked ? movie.year : movie.parsedYear
        guard !title.isEmpty else { return }
        let results = try await search(title: title, year: year)
        if let best = TitleMatcher.bestMatch(title: title, year: year, in: results) {
            try await apply(tmdbID: best.id, to: movie)
        } else {
            movie.metadataFetched = true
        }
    }

    func fetchMissing(in context: ModelContext) async {
        guard hasKey else { return }
        let descriptor = FetchDescriptor<Movie>(predicate: #Predicate { !$0.metadataFetched })
        let movies = (try? context.fetch(descriptor)) ?? []
        for movie in movies {
            try? await match(movie)
            try? context.save()
        }
    }
}
