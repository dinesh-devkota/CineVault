import CineVaultCore
import Foundation
import SwiftData

/// Walks a NAS source and syncs Movie rows with the files found.
@MainActor
final class LibraryScanner: ObservableObject {
    @Published private(set) var isScanning = false
    @Published var status: String?
    private let scanVideos: (SourceConfig) async throws -> [RemoteFile]
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(scanVideos: @escaping (SourceConfig) async throws -> [RemoteFile] = {
        try await SMBService.shared.scanVideos($0)
    }) {
        self.scanVideos = scanVideos
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
            let files = try await scanVideos(source.config())
            var existing = Dictionary(source.movies.map { ($0.filePath, $0) }, uniquingKeysWith: { a, _ in a })
            var added = 0
            for file in files {
                if let movie = existing.removeValue(forKey: file.path) {
                    movie.isMissing = false
                    movie.fileSize = file.size
                    continue
                }
                let movie = Movie(filePath: file.path, fileSize: file.size, source: source)
                let parsed = FilenameParser.parse(path: file.path)
                movie.parsedTitle = parsed.title
                movie.parsedYear = parsed.year
                movie.tmdbID = parsed.tmdbID
                movie.setTitle(parsed.title.isEmpty ? movie.fileName : parsed.title)
                movie.year = parsed.year
                context.insert(movie)
                added += 1
            }
            for movie in existing.values { movie.isMissing = true }
            source.lastScanDate = Date()
            try context.save()
            let scanStatus = "\(source.name): \(files.count) movies (\(added) new)"
            status = scanStatus
            do {
                let result = try await MetadataService.shared.fetchMissing(in: context)
                if let failure = result?.failureMessage {
                    status = "\(scanStatus). \(failure)"
                }
            } catch {
                status = "\(scanStatus). Metadata update failed: \(error.localizedDescription)"
            }
        } catch {
            status = "Scan failed for \(source.name): \(error.localizedDescription)"
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
