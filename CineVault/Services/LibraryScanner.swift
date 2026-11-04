import CineVaultCore
import Foundation
import SwiftData

/// Walks a NAS source and syncs Movie rows with the files found.
@MainActor
final class LibraryScanner: ObservableObject {
    @Published private(set) var isScanning = false
    @Published var status: String?
    private let scanVideos: (SourceConfig) async throws -> [RemoteFile]
    private let scanWithProgress: ((SourceConfig, MediaScanReporter) async throws -> [RemoteFile])?
    private let saveContext: (ModelContext) throws -> Void
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(scanVideos: ((SourceConfig) async throws -> [RemoteFile])? = nil,
         scanWithProgress: ((SourceConfig, MediaScanReporter) async throws -> [RemoteFile])? = nil,
         saveContext: @escaping (ModelContext) throws -> Void = { try $0.save() }) {
        self.saveContext = saveContext
        self.scanVideos = scanVideos ?? { try await SMBService.shared.scanVideos($0) }
        if let scanWithProgress { self.scanWithProgress = scanWithProgress }
        else if scanVideos == nil {
            self.scanWithProgress = { config, report in
                try await SMBService.shared.scanMedia(config, onProgress: report)
            }
        } else { self.scanWithProgress = nil }
    }

    func scan(_ source: MediaSource, context: ModelContext) async {
        if isScanning {
            await withCheckedContinuation { waiters.append($0) }
        } else {
            isScanning = true
        }
        defer {
            if waiters.isEmpty {
                isScanning = false
            } else {
                waiters.removeFirst().resume()
            }
        }
        guard !Task.isCancelled else { return }
        status = "Scanning \(source.name)…"
        do {
            let config = try source.config()
            let state = LibraryScanState(source: source, context: context, config: config, saveContext: saveContext)
            let files: [RemoteFile]
            if let scanWithProgress {
                files = try await scanWithProgress(config) { progress in
                    try state.apply(progress.files)
                    self.status = "Scanning \(source.name): \(progress.found) items in \(progress.folders) folders…"
                }
            } else { files = try await scanVideos(config) }
            try Task.checkCancellation()
            try state.verifySource()
            var existing = Dictionary(source.movies.map { ($0.filePath, $0) }, uniquingKeysWith: { a, _ in a })
            let previous = existing.values.map { ($0, $0.isMissing, $0.fileSize) }
            let previousScanDate = source.lastScanDate
            var finalInsertions: [Movie] = []
            for file in files {
                if let movie = existing.removeValue(forKey: file.path) {
                    movie.isMissing = false
                    movie.fileSize = file.size
                    continue
                }
                finalInsertions.append(state.insert(file))
            }
            for movie in existing.values { movie.isMissing = true }
            source.lastScanDate = Date()
            do { try saveContext(context) }
            catch {
                for (movie, missing, size) in previous { movie.isMissing = missing; movie.fileSize = size }
                source.lastScanDate = previousScanDate
                for movie in finalInsertions { context.delete(movie) }
                throw error
            }
            let scanStatus = "\(source.name): \(files.count) items (\(state.added) new). Updating categories and artwork…"
            status = scanStatus
            do {
                let result = try await MetadataService.shared.fetchMissing(in: context)
                let counts = Dictionary(grouping: source.movies.filter { !$0.isMissing }, by: \.mediaKind)
                let complete = "\(source.name): \(counts[.movie]?.count ?? 0) movies, \(counts[.documentary]?.count ?? 0) documentaries, \(counts[.tvShow]?.count ?? 0) TV episodes, \(counts[.music]?.count ?? 0) music items (\(state.added) new)"
                status = complete
                if let failure = result?.failureMessage {
                    status = "\(complete). \(failure)"
                }
            } catch {
                status = "\(scanStatus). Metadata update failed: \(error.localizedDescription)"
            }
        } catch is CancellationError {
            status = "Scan cancelled for \(source.name)."
        } catch {
            status = "Scan failed for \(source.name): \(error.localizedDescription). Items already found are kept; retry the scan."
        }
    }

    func scanAll(context: ModelContext) async {
        do {
            let sources = try context.fetch(FetchDescriptor<MediaSource>())
            for source in sources { await scan(source, context: context) }
        } catch {
            status = "Could not load NAS sources: \(error.localizedDescription)"
        }
    }
}

/// SwiftData state stays on the main actor while the SMB actor walks the network.
@MainActor
private final class LibraryScanState {
    let source: MediaSource
    let context: ModelContext
    let config: SourceConfig
    private(set) var added = 0
    private var indexed: [String: Movie]
    private let saveContext: (ModelContext) throws -> Void
    init(source: MediaSource, context: ModelContext, config: SourceConfig,
         saveContext: @escaping (ModelContext) throws -> Void) {
        self.source = source; self.context = context; self.config = config
        self.saveContext = saveContext
        indexed = Dictionary(source.movies.map { ($0.filePath, $0) }, uniquingKeysWith: { a, _ in a })
    }
    func verifySource() throws {
        try Task.checkCancellation()
        guard !source.isDeleted, source.modelContext != nil else { throw SMBError.sourceChanged }
        guard try source.config() == config else { throw SMBError.sourceChanged }
    }
    func apply(_ files: [RemoteFile]) throws {
        try verifySource()
        guard !files.isEmpty else { return }
        var previous: [(Movie, Bool, Int64)] = []
        var inserted: [Movie] = []
        for file in files {
            if let movie = indexed[file.path], !movie.isDeleted {
                previous.append((movie, movie.isMissing, movie.fileSize))
                movie.isMissing = false; movie.fileSize = file.size
            } else { inserted.append(insert(file)) }
        }
        do { try saveContext(context) }
        catch {
            for (movie, missing, size) in previous.reversed() { movie.isMissing = missing; movie.fileSize = size }
            for movie in inserted { indexed.removeValue(forKey: movie.filePath); context.delete(movie) }
            added -= inserted.count
            throw error
        }
    }
    @discardableResult
    func insert(_ file: RemoteFile) -> Movie {
        let movie = Movie(filePath: file.path, fileSize: file.size, source: source)
        let parsed = FilenameParser.parse(path: file.path)
        let music = MediaFile.kind(path: file.path) == .music
        let title = music ? (movie.fileName as NSString).deletingPathExtension : parsed.title
        movie.parsedTitle = title
        movie.parsedYear = music ? nil : parsed.year
        movie.tmdbID = music ? nil : parsed.tmdbID
        movie.setTitle(title.isEmpty ? movie.fileName : title)
        movie.year = music ? nil : parsed.year
        movie.metadataFetched = music
        context.insert(movie)
        indexed[file.path] = movie
        added += 1
        return movie
    }
}
