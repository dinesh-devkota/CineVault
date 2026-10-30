import Combine
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

/// Public builds are self-contained; official builds supply a private access module.
@MainActor
final class PlaybackServices: ObservableObject {
    #if CINEVAULT_COMMERCIAL
    let commercial = CommercialServices()
    private var observation: AnyCancellable?

    init() {
        observation = commercial.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
    }
    #endif

    func refresh() async {
        #if CINEVAULT_COMMERCIAL
        await commercial.refresh()
        #endif
    }

    func canResume(_ movie: Movie) -> Bool {
        #if CINEVAULT_COMMERCIAL
        return commercial.canResume(mediaID: movie.id.uuidString) || movie.isInProgress
        #else
        return movie.isInProgress
        #endif
    }

    func resumePosition(_ movie: Movie) -> Double {
        #if CINEVAULT_COMMERCIAL
        return commercial.resumePosition(mediaID: movie.id.uuidString, fallback: movie.playbackPosition)
        #else
        return movie.playbackPosition
        #endif
    }

    func resetProgress(_ movie: Movie) {
        #if CINEVAULT_COMMERCIAL
        commercial.discardResume(mediaID: movie.id.uuidString)
        #endif
        movie.playbackPosition = 0
    }
}
