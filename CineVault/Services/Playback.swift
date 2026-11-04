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
                info.contentType = VideoFile.contentType(path: path)
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
@MainActor
final class PlaybackController: ObservableObject {
    let player: AVPlayer
    #if CINEVAULT_COMMERCIAL
    let commercialSession: CommercialPlaybackSession
    #endif
    private let loader: SMBResourceLoader
    private let movie: Movie
    private var finishedPlayback = false
    private var stopped = false
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?

    init?(movie: Movie, resume: Bool, services: PlaybackServices,
          sourceConfig: (MediaSource) throws -> SourceConfig = { try $0.config() }) throws {
        guard let source = movie.source else { return nil }
        self.movie = movie
        loader = SMBResourceLoader(config: try sourceConfig(source), path: movie.filePath)
        let item = AVPlayerItem(asset: loader.makeAsset())
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
    }

    private static func metadata(for movie: Movie) -> [AVMetadataItem] {
        func item(_ id: AVMetadataIdentifier, _ value: String?) -> AVMetadataItem? {
            guard let value else { return nil }
            let item = AVMutableMetadataItem()
            item.identifier = id
            item.value = value as NSString
            item.extendedLanguageTag = "und"
            return item
        }
        return [item(.commonIdentifierTitle, movie.title), item(.commonIdentifierDescription, movie.overview)].compactMap { $0 }
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

    func stop() {
        guard !stopped else { return }
        #if CINEVAULT_COMMERCIAL
        commercialSession.stop()
        #endif
        record(time: player.currentTime().seconds)
        stopped = true
        player.pause()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        timeObserver = nil
        endObserver = nil
        try? movie.modelContext?.save()
    }
}

struct PlaybackScreen: View {
    let controller: PlaybackController

    var body: some View {
        #if CINEVAULT_COMMERCIAL
        CommercialPlayerView(session: controller.commercialSession)
        #else
        PlayerView(controller: controller)
        #endif
    }
}

struct PlayerView: UIViewControllerRepresentable {
    let controller: PlaybackController

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let viewController = AVPlayerViewController()
        viewController.player = controller.player
        controller.player.play()
        return viewController
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: ()) {
        uiViewController.player?.pause()
    }
}
