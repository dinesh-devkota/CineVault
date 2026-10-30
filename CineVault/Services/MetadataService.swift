import CineVaultCore
import Foundation
import SwiftData

enum MetadataError: LocalizedError {
    case invalidResponse
    case httpStatus(Int)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "TMDB returned an invalid response."
        case .httpStatus(401), .httpStatus(403):
            return "TMDB rejected the API key. Check it in Settings."
        case .httpStatus(let status):
            return "TMDB request failed (HTTP \(status))."
        }
    }
}

struct MetadataFetchSummary {
    let attempted: Int
    let failed: Int
    let firstError: String?

    var failureMessage: String? {
        guard failed > 0 else { return nil }
        return "Metadata failed for \(failed) of \(attempted) movies. \(firstError ?? "Try again.")"
    }
}

/// Fetches posters and details from TMDB using the user's API key.
@MainActor
final class MetadataService {
    static let shared = MetadataService()
    static let apiKeyAccount = "tmdb-api-key"

    var apiKey: String {
        get throws { try Keychain.get(Self.apiKeyAccount) ?? "" }
    }

    func setAPIKey(_ value: String) throws {
        try Keychain.set(value.trimmingCharacters(in: .whitespacesAndNewlines), for: Self.apiKeyAccount)
    }

    var hasKey: Bool {
        get throws { !(try apiKey).isEmpty }
    }

    private func load<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw MetadataError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw MetadataError.httpStatus(http.statusCode) }
        return try TMDB.decoder.decode(T.self, from: data)
    }

    func search(title: String, year: Int?) async throws -> [TMDBMovieSummary] {
        let key = try apiKey
        var results = try await load(TMDB.searchRequest(title: title, year: year, apiKey: key), as: TMDBSearchResponse.self).results
        if results.isEmpty, year != nil {
            results = try await load(TMDB.searchRequest(title: title, year: nil, apiKey: key), as: TMDBSearchResponse.self).results
        }
        return results
    }

    /// Applies TMDB details to a movie. `force` is used by manual re-match.
    func apply(tmdbID: Int, to movie: Movie, force: Bool = false) async throws {
        let details = try await load(TMDB.detailsRequest(id: tmdbID, apiKey: try apiKey), as: TMDBMovieDetails.self)
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

    func fetchMissing(in context: ModelContext) async throws -> MetadataFetchSummary? {
        guard try hasKey else { return nil }
        let descriptor = FetchDescriptor<Movie>(predicate: #Predicate { !$0.metadataFetched })
        let movies = try context.fetch(descriptor)
        return try await update(movies, in: context) { try await self.match($0) }
    }

    func update(_ movies: [Movie], in context: ModelContext,
                match: (Movie) async throws -> Void) async throws -> MetadataFetchSummary {
        var failed = 0
        var firstError: String?
        for movie in movies {
            try Task.checkCancellation()
            do {
                try await match(movie)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                try Task.checkCancellation()
                failed += 1
                if firstError == nil { firstError = error.localizedDescription }
                continue
            }
            try context.save()
        }
        return MetadataFetchSummary(attempted: movies.count, failed: failed, firstError: firstError)
    }
}
