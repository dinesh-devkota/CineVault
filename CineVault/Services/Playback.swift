import AVFoundation
import AVKit
import CineVaultCore
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

/// Streams a NAS file into AVPlayer by answering byte-range requests with SMB reads,
/// so playback starts immediately and seeking works without downloading the file.
final class SMBResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "cinevault-smb"
    private static let chunkSize: Int64 = 2 * 1024 * 1024

    private let config: SourceConfig
    private let path: String
    private let queue = DispatchQueue(label: "cinevault.resource-loader")
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var sizeTask: Task<Int64, Error>?

    init(config: SourceConfig, path: String) {
        self.config = config
        self.path = path
    }

    deinit {
        sizeTask?.cancel()
    }

    func makeAsset() -> AVURLAsset {
        var components = URLComponents()
        components.scheme = Self.scheme
        components.host = "nas"
        components.path = "/" + path
        let asset = AVURLAsset(url: components.url!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        let key = ObjectIdentifier(loadingRequest)
        let sizeTask: Task<Int64, Error>
        if let existing = self.sizeTask {
            sizeTask = existing
        } else {
            let config = self.config
            let path = self.path
            sizeTask = Task { try await SMBService.shared.fileSize(config, path: path) }
            self.sizeTask = sizeTask
        }
        tasks[key] = Task { [weak self] in
            await self?.handle(loadingRequest, sizeTask: sizeTask)
            self?.queue.async { self?.tasks.removeValue(forKey: key) }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    private func handle(_ request: AVAssetResourceLoadingRequest, sizeTask: Task<Int64, Error>) async {
        do {
            let size = try await sizeTask.value
            try Task.checkCancellation()
            if let info = request.contentInformationRequest {
                info.contentType = MediaFile.contentType(path: path)
                info.contentLength = size
                info.isByteRangeAccessSupported = true
            }
            if let dataRequest = request.dataRequest {
                guard dataRequest.requestedOffset >= 0 else {
                    throw SMBError.invalidByteRange
                }
                let offset = dataRequest.currentOffset > 0 ? dataRequest.currentOffset : dataRequest.requestedOffset
                let end: Int64
                if dataRequest.requestsAllDataToEndOfResource {
                    end = size
                } else {
                    guard dataRequest.requestedLength >= 0 else { throw SMBError.invalidByteRange }
                    let (requestedEnd, overflow) = dataRequest.requestedOffset.addingReportingOverflow(Int64(dataRequest.requestedLength))
                    guard !overflow else { throw SMBError.invalidByteRange }
                    end = min(size, requestedEnd)
                }
                try await streamBytes(from: offset, to: end) { data in
                    dataRequest.respond(with: data)
                }
            }
            request.finishLoading()
        } catch is CancellationError {
            // AVPlayer cancelled this range (seek or enough buffered).
        } catch {
            request.finishLoading(with: error)
        }
    }

    func streamBytes(from start: Int64, to end: Int64, respond: (Data) -> Void) async throws {
        guard start >= 0, end >= start else { throw SMBError.invalidByteRange }
        var offset = start
        while offset < end {
            try Task.checkCancellation()
            let length = Int(min(Self.chunkSize, end - offset))
            let data = try await SMBService.shared.read(config, path: path, offset: offset, length: length)
            try Task.checkCancellation()
            guard !data.isEmpty else { throw SMBError.unexpectedEndOfFile }
            respond(data)
            offset += Int64(data.count)
        }
    }
}

/// Owns the AVPlayer for one movie and persists resume position / watched state.
enum PlaybackPreparationError: LocalizedError {
    case unsupportedFormat
    case fileUnavailable

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat:
            return "Apple TV's native player cannot play this file's container or audio/video codecs. Use a supported container with compatible codecs."
        case .fileUnavailable:
            return "This file is missing or ignored by its NAS source. Include it again and rescan before playing."
        }
    }
}

@MainActor
final class PlaybackController: ObservableObject {
    let player: AVPlayer
    @Published private(set) var playbackError: String?
    #if CINEVAULT_COMMERCIAL
    let commercialSession: CommercialPlaybackSession
    #endif
    private let loader: SMBResourceLoader
    private let asset: AVURLAsset
    private let movie: Movie
    private var finishedPlayback = false
    private var stopped = false
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var artworkTask: Task<Void, Never>?
    private var itemObservation: NSKeyValueObservation?

    static func isNativeCandidate(_ movie: Movie) -> Bool {
        MediaFile.isPlayable(path: movie.filePath)
    }

    private static func playbackSource(for movie: Movie) throws -> MediaSource? {
        guard isNativeCandidate(movie) else { throw PlaybackPreparationError.unsupportedFormat }
        guard let source = movie.source else { return nil }
        guard !movie.isMissing, !source.isIgnored(filePath: movie.filePath) else { throw PlaybackPreparationError.fileUnavailable }
        return source
    }

    /// Check the actual asset before creating a player or changing playback history.
    static func prepare(movie: Movie, resume: Bool, services: PlaybackServices,
                        sourceConfig: (MediaSource) throws -> SourceConfig = { try $0.config() },
                        loadPlayable: (AVURLAsset) async throws -> Bool = { try await $0.load(.isPlayable) }) async throws -> PlaybackController? {
        guard let source = try playbackSource(for: movie) else { return nil }
        let config = try sourceConfig(source)
        let path = movie.filePath
        let loader = SMBResourceLoader(config: config, path: path)
        let asset = loader.makeAsset()
        return try await withTaskCancellationHandler {
            do {
                let playable = try await loadPlayable(asset)
                try Task.checkCancellation()
                guard playable else { throw PlaybackPreparationError.unsupportedFormat }
                guard let currentSource = try playbackSource(for: movie), currentSource === source,
                      movie.filePath == path, try sourceConfig(currentSource) == config else {
                    throw PlaybackPreparationError.fileUnavailable
                }
                return PlaybackController(movie: movie, resume: resume, services: services, loader: loader, asset: asset)
            } catch {
                asset.cancelLoading()
                throw error
            }
        } onCancel: {
            asset.cancelLoading()
        }
    }

    convenience init?(movie: Movie, resume: Bool, services: PlaybackServices,
          sourceConfig: (MediaSource) throws -> SourceConfig = { try $0.config() }) throws {
        guard let source = try Self.playbackSource(for: movie) else { return nil }
        let loader = SMBResourceLoader(config: try sourceConfig(source), path: movie.filePath)
        self.init(movie: movie, resume: resume, services: services, loader: loader, asset: loader.makeAsset())
    }

    private init(movie: Movie, resume: Bool, services: PlaybackServices, loader: SMBResourceLoader, asset: AVURLAsset) {
        self.movie = movie
        self.loader = loader
        self.asset = asset
        let item = AVPlayerItem(asset: asset)
        item.externalMetadata = Self.metadata(for: movie)
        let player = AVPlayer(playerItem: item)
        self.player = player
        #if CINEVAULT_COMMERCIAL
        commercialSession = services.commercial.makePlaybackSession(
            player: player, mediaID: movie.id.uuidString, resume: resume,
            initialPosition: resume ? services.resumePosition(movie) : 0
        )
        #else
        if resume, movie.playbackPosition > 0 {
            player.seek(to: CMTime(seconds: movie.playbackPosition, preferredTimescale: 600))
        }
        #endif
        itemObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            let message = item.error?.localizedDescription ?? "The native player could not decode this file."
            Task { @MainActor [weak self] in
                guard let self, !self.stopped else { return }
                self.playbackError = "Playback could not continue. Check the NAS connection and the file's codecs. \(message)"
                self.player.pause()
            }
        }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 10, preferredTimescale: 1), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.record(time: time.seconds) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.player.currentItem?.forwardPlaybackEndTime.isValid == true {
                    self.record(time: self.player.currentTime().seconds)
                } else {
                    self.finish()
                }
            }
        }
        if let url = TMDB.imageURL(movie.posterPath, size: "w342") {
            artworkTask = Task { [weak self, weak item] in
                do {
                    let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 15))
                    try Task.checkCancellation()
                    guard let self, !self.stopped, let item, self.player.currentItem === item,
                          let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
                          data.count <= 5 * 1024 * 1024, UIImage(data: data) != nil else { return }
                    let artwork = AVMutableMetadataItem()
                    artwork.identifier = .commonIdentifierArtwork
                    artwork.value = data as NSData
                    item.externalMetadata = Self.metadata(for: self.movie) + [artwork]
                } catch {
                    // Artwork is optional; the title and playback remain available offline.
                }
            }
        }
    }

    static func metadata(for movie: Movie) -> [AVMetadataItem] {
        func item(_ id: AVMetadataIdentifier, _ value: String?) -> AVMetadataItem? {
            guard let value, !value.isEmpty else { return nil }
            let item = AVMutableMetadataItem()
            item.identifier = id
            item.value = value as NSString
            item.extendedLanguageTag = "und"
            return item
        }
        let episode = movie.episodeInfo.map {
            "\(movie.seriesTitle ?? $0.seriesTitle) · Season \(movie.seasonNumber ?? $0.season) · Episode \(movie.episodeNumber ?? $0.episode)"
        }
        let facts = [episode, movie.year.map(String.init), movie.genres.isEmpty ? nil : movie.genres.joined(separator: " · "),
                     movie.runtimeMinutes.map { "\($0) min" }].compactMap { $0 }.joined(separator: " · ")
        return [item(.commonIdentifierTitle, movie.title.isEmpty ? movie.fileName : movie.title),
                item(.iTunesMetadataTrackSubTitle, facts),
                item(.commonIdentifierDescription, movie.overview)].compactMap { $0 }
    }

    func record(time: Double) {
        guard !stopped, !finishedPlayback, time.isFinite, time > 0 else { return }
        let boundary = player.currentItem?.forwardPlaybackEndTime
        let limit = boundary.flatMap { $0.isValid && $0.seconds.isFinite ? $0.seconds : nil }
        let position = limit.map { min(time, $0) } ?? time
        guard position > 0 else { return }
        if let duration = player.currentItem?.duration.seconds, duration.isFinite, duration > 0 {
            movie.duration = duration
            if position / duration > 0.92 {
                finish()
                return
            }
        }
        movie.isWatched = false
        movie.playbackPosition = position
        movie.lastPlayedAt = Date()
    }

    private func finish() {
        guard !stopped else { return }
        finishedPlayback = true
        movie.isWatched = true
        movie.playbackPosition = 0
        movie.lastPlayedAt = Date()
    }

    func stop(saveProgress: Bool = true) {
        guard !stopped else { return }
        #if CINEVAULT_COMMERCIAL
        commercialSession.stop()
        #endif
        if saveProgress { record(time: player.currentTime().seconds) }
        stopped = true
        artworkTask?.cancel()
        artworkTask = nil
        player.pause()
        asset.cancelLoading()
        itemObservation?.invalidate()
        itemObservation = nil
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        timeObserver = nil
        endObserver = nil
        if saveProgress { try? movie.modelContext?.save() }
    }
}

struct PlaybackScreen: View {
    @ObservedObject var controller: PlaybackController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
        #if CINEVAULT_COMMERCIAL
        CommercialPlayerView(session: controller.commercialSession)
            .overlay(alignment: .topLeading) {
                Button { controller.stop(); dismiss() } label: { Label("Back", systemImage: "chevron.left") }
                    .buttonStyle(.bordered).padding(48)
            }
        #else
        PlayerView(controller: controller)
        #endif
        }
        .alert("Playback unavailable", isPresented: Binding(get: { controller.playbackError != nil }, set: { _ in })) {
            Button("Back") { controller.stop(); dismiss() }
        } message: { Text(controller.playbackError ?? "This file could not be played.") }
    }
}

struct PlayerView: UIViewControllerRepresentable {
    let controller: PlaybackController
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let viewController = AVPlayerViewController()
        viewController.player = controller.player
        viewController.transportBarCustomMenuItems = [
            UIAction(title: "Back", image: UIImage(systemName: "chevron.left")) { _ in
                controller.stop()
                dismiss()
            }
        ]
        controller.player.play()
        return viewController
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: ()) {
        uiViewController.player?.pause()
    }
}
