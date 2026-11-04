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
    private let requestData: (URLRequest) async throws -> (Data, URLResponse)
    private let readAPIKey: () throws -> String
    private struct RequestIntent {
        let token: UUID
        let manual: Bool
    }
    private var requests: [UUID: RequestIntent] = [:]
    private var tvCacheKey: String?
    private var tvSearchCache: [String: [TMDBMovieSummary]] = [:]
    private var tvDetailsCache: [Int: TMDBTVDetails] = [:]
    private var tvSeasonCache: [String: TMDBTVSeason] = [:]

    private func clearTVCache() {
        tvCacheKey = nil
        tvSearchCache.removeAll(); tvDetailsCache.removeAll(); tvSeasonCache.removeAll()
    }

    private func prepareTVCache(key: String) {
        if tvCacheKey != key || tvSearchCache.count + tvDetailsCache.count + tvSeasonCache.count >= 512 { clearTVCache() }
        tvCacheKey = key
    }

    init(requestData: @escaping (URLRequest) async throws -> (Data, URLResponse) = {
        try await URLSession.shared.data(for: $0)
    }, readAPIKey: @escaping () throws -> String = {
        try Keychain.get(MetadataService.apiKeyAccount) ?? ""
    }) {
        self.requestData = requestData
        self.readAPIKey = readAPIKey
    }

    /// A response may outlive a rename, reset, or manual re-match.
    private struct MatchState: Equatable {
        let id: Int?
        let title: String
        let year: Int?
        let locked: Bool
        let seriesID: Int?
        let episode: EpisodeInfo?

        init(_ movie: Movie) {
            id = movie.tmdbID
            title = movie.title
            year = movie.year
            locked = movie.isLocked
            seriesID = movie.seriesTMDBID
            episode = movie.episodeInfo
        }
    }

    /// Capture identity while the model is live; deleted SwiftData models can
    /// become detached while a network request or another movie is pending.
    private struct MovieReference {
        let id: UUID
        let context: ModelContext?

        init(_ movie: Movie) {
            id = movie.id
            context = movie.modelContext
        }

        func isAvailable(_ movie: Movie) -> Bool {
            if let context, movie.modelContext !== context { return false }
            return !movie.isDeleted
        }
    }

    private func beginRequest(for reference: MovieReference, manual: Bool) -> UUID? {
        // A scan must not replace a selection the user is waiting to apply.
        if requests[reference.id]?.manual == true, !manual { return nil }
        let token = UUID()
        requests[reference.id] = RequestIntent(token: token, manual: manual)
        return token
    }

    private func finishRequest(for reference: MovieReference, token: UUID) {
        if requests[reference.id]?.token == token { requests.removeValue(forKey: reference.id) }
    }

    func resetToFileName(_ movie: Movie) {
        // Reset is an explicit intent even when title/ID already match the filename.
        requests.removeValue(forKey: movie.id)
        movie.resetToFileName()
    }

    var apiKey: String {
        get throws { try readAPIKey() }
    }

    func setAPIKey(_ value: String) throws {
        try Keychain.set(value.trimmingCharacters(in: .whitespacesAndNewlines), for: Self.apiKeyAccount)
        clearTVCache()
    }

    var hasKey: Bool {
        get throws { !(try apiKey).isEmpty }
    }

    private func load<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        let (data, response) = try await requestData(request)
        try Task.checkCancellation()
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

    func searchTV(title: String, year: Int?) async throws -> [TMDBMovieSummary] {
        try Task.checkCancellation()
        let key = try apiKey
        prepareTVCache(key: key)
        let cacheKey = "\(TitleMatcher.normalize(title))|\(year.map(String.init) ?? "")"
        if let cached = tvSearchCache[cacheKey] { return cached }
        var results = try await load(TMDB.tvSearchRequest(title: title, year: year, apiKey: key), as: TMDBTVSearchResponse.self).results
        if results.isEmpty, year != nil {
            results = try await load(TMDB.tvSearchRequest(title: title, year: nil, apiKey: key), as: TMDBTVSearchResponse.self).results
        }
        let candidates = results.map(\.matchingCandidate)
        if tvCacheKey == key { tvSearchCache[cacheKey] = candidates }
        return candidates
    }

    private func tvDetails(id: Int) async throws -> TMDBTVDetails {
        try Task.checkCancellation()
        let key = try apiKey
        prepareTVCache(key: key)
        if let cached = tvDetailsCache[id] { return cached }
        let details = try await load(TMDB.tvDetailsRequest(id: id, apiKey: key), as: TMDBTVDetails.self)
        if tvCacheKey == key { tvDetailsCache[id] = details }
        return details
    }

    private func tvSeason(id: Int, season: Int) async throws -> TMDBTVSeason {
        try Task.checkCancellation()
        let key = try apiKey
        prepareTVCache(key: key)
        let cacheKey = "\(id)|\(season)"
        if let cached = tvSeasonCache[cacheKey] { return cached }
        let details = try await load(TMDB.tvSeasonRequest(id: id, season: season, apiKey: key), as: TMDBTVSeason.self)
        if tvCacheKey == key { tvSeasonCache[cacheKey] = details }
        return details
    }

    @discardableResult
    func applyTV(seriesID: Int, to movie: Movie, force: Bool = false) async throws -> Bool {
        guard !movie.isDeleted, let info = movie.episodeInfo else { return false }
        let reference = MovieReference(movie)
        guard let token = beginRequest(for: reference, manual: force) else { return false }
        defer { finishRequest(for: reference, token: token) }
        return try await applyTV(seriesID: seriesID, info: info, to: movie, force: force, token: token, reference: reference)
    }

    private func applyTV(seriesID: Int, info: EpisodeInfo, to movie: Movie, force: Bool,
                         token: UUID, reference: MovieReference) async throws -> Bool {
        guard reference.isAvailable(movie) else { return false }
        let original = MatchState(movie)
        let details = try await tvDetails(id: seriesID)
        let season = try await tvSeason(id: seriesID, season: info.season)
        try Task.checkCancellation()
        guard reference.isAvailable(movie), requests[reference.id]?.token == token,
              MatchState(movie) == original else { return false }
        let episode = season.episodes.first { $0.seasonNumber == info.season && $0.episodeNumber == info.episode }
        movie.tmdbID = nil
        movie.seriesTMDBID = details.id
        movie.seriesTitle = details.name.isEmpty ? info.seriesTitle : details.name
        movie.seasonNumber = info.season
        movie.episodeNumber = info.episode
        movie.seasonPosterPath = season.posterPath
        if force || !movie.isLocked {
            if let name = episode?.name, !name.isEmpty { movie.setTitle(name) }
            movie.year = details.year ?? info.year
        }
        let episodeOverview = episode?.overview
        movie.overview = episodeOverview.flatMap { $0.isEmpty ? nil : $0 } ?? details.overview
        movie.tagline = nil
        movie.runtimeMinutes = episode?.runtime ?? details.episodeRunTime?.first { $0 > 0 }
        movie.rating = episode?.voteAverage ?? details.voteAverage
        movie.posterPath = details.posterPath
        movie.backdropPath = episode?.stillPath ?? details.backdropPath
        movie.genres = details.genres?.map(\.name) ?? []
        movie.metadataFetched = true
        if force { movie.isLocked = true }
        return true
    }

    private func matchTV(_ info: EpisodeInfo, movie: Movie, token: UUID, reference: MovieReference) async throws {
        let original = MatchState(movie)
        var seriesID = movie.seriesTMDBID
        if seriesID == nil {
            let results = try await searchTV(title: info.seriesTitle, year: info.year)
            seriesID = TitleMatcher.bestMatch(title: info.seriesTitle, year: info.year, in: results)?.id
        }
        try Task.checkCancellation()
        guard reference.isAvailable(movie), requests[reference.id]?.token == token,
              MatchState(movie) == original else { return }
        if let seriesID {
            _ = try await applyTV(seriesID: seriesID, info: info, to: movie, force: false, token: token, reference: reference)
        } else {
            // Remember the local grouping even without a match; never retain an old movie poster.
            if movie.tmdbID != nil {
                movie.posterPath = nil; movie.backdropPath = nil; movie.overview = nil
                movie.tagline = nil; movie.genres = []; movie.runtimeMinutes = nil; movie.rating = nil
                if !movie.isLocked { movie.setTitle(movie.parsedTitle); movie.year = info.year }
            }
            movie.tmdbID = nil
            movie.seriesTitle = info.seriesTitle
            movie.seasonNumber = info.season
            movie.episodeNumber = info.episode
            movie.metadataFetched = true
        }
    }

    /// Applies TMDB details to a movie. `force` is used by manual re-match.
    @discardableResult
    func apply(tmdbID: Int, to movie: Movie, force: Bool = false) async throws -> Bool {
        guard !movie.isDeleted else { return false }
        let reference = MovieReference(movie)
        guard let token = beginRequest(for: reference, manual: force) else { return false }
        defer { finishRequest(for: reference, token: token) }
        return try await apply(tmdbID: tmdbID, to: movie, force: force, token: token, reference: reference)
    }

    private func apply(tmdbID: Int, to movie: Movie, force: Bool, token: UUID,
                       reference: MovieReference) async throws -> Bool {
        guard reference.isAvailable(movie) else { return false }
        let original = MatchState(movie)
        let details = try await load(TMDB.detailsRequest(id: tmdbID, apiKey: try apiKey), as: TMDBMovieDetails.self)
        guard reference.isAvailable(movie), requests[reference.id]?.token == token,
              MatchState(movie) == original else { return false }
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
        if force { movie.isLocked = true }
        return true
    }

    /// Auto-match: uses a {tmdb-123} tag if present, otherwise title/year search.
    func match(_ movie: Movie) async throws {
        guard !movie.isDeleted else { return }
        guard !MediaFile.isAudio(path: movie.filePath) else { movie.metadataFetched = true; return }
        let reference = MovieReference(movie)
        guard let token = beginRequest(for: reference, manual: false) else { return }
        defer { finishRequest(for: reference, token: token) }
        if let info = movie.episodeInfo {
            try await matchTV(info, movie: movie, token: token, reference: reference)
            return
        }
        let original = MatchState(movie)
        if let id = movie.tmdbID {
            _ = try await apply(tmdbID: id, to: movie, force: false, token: token, reference: reference)
            return
        }
        let title = movie.isLocked ? movie.title : movie.parsedTitle
        let year = movie.isLocked ? movie.year : movie.parsedYear
        guard !title.isEmpty else { return }
        let results = try await search(title: title, year: year)
        guard reference.isAvailable(movie), requests[reference.id]?.token == token,
              MatchState(movie) == original else { return }
        if let best = TitleMatcher.bestMatch(title: title, year: year, in: results) {
            _ = try await apply(tmdbID: best.id, to: movie, force: false, token: token, reference: reference)
        } else {
            movie.metadataFetched = true
        }
    }

    func fetchMissing(in context: ModelContext) async throws -> MetadataFetchSummary? {
        guard try hasKey else { return nil }
        let descriptor = FetchDescriptor<Movie>(predicate: #Predicate { !$0.metadataFetched || $0.seriesTitle == nil })
        let movies = try context.fetch(descriptor).filter {
            !MediaFile.isAudio(path: $0.filePath) && (!$0.metadataFetched ||
                (!$0.isLocked && $0.seriesTitle == nil && $0.episodeInfo != nil))
        }
        return try await update(movies, in: context) { try await self.match($0) }
    }

    func update(_ movies: [Movie], in context: ModelContext,
                match: (Movie) async throws -> Void) async throws -> MetadataFetchSummary {
        clearTVCache()
        let pending = movies.filter { !$0.isDeleted }.map { ($0, MovieReference($0)) }
        var attempted = 0
        var failed = 0
        var firstError: String?
        for (movie, reference) in pending {
            try Task.checkCancellation()
            guard reference.isAvailable(movie) else { continue }
            attempted += 1
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
        return MetadataFetchSummary(attempted: attempted, failed: failed, firstError: firstError)
    }
}
