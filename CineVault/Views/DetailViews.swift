import CineVaultCore
import SwiftData
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

struct MovieDetailView: View {
    @Bindable var movie: Movie
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var playbackServices: PlaybackServices
    @State private var playback: PlaybackController?
    @State private var editing = false
    @State private var unsupported = false
    @State private var missingSource = false
    @State private var credentialUnavailable = false
    @State private var credentialMessage = ""
    @State private var watchlistError: String?
    @State private var isPreparing = false
    @State private var preparationTask: Task<Void, Never>?
    @State private var preparationID = UUID()
    @State private var loadingError: String?

    var body: some View {
        ZStack(alignment: .topLeading) {
            MovieBackdrop(movie: movie)
                .ignoresSafeArea()
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.95), location: 0),
                    .init(color: .black.opacity(0.83), location: 0.35),
                    .init(color: .black.opacity(0.18), location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            .ignoresSafeArea()
            LinearGradient(colors: [.black.opacity(0.18), .clear, CinemaTheme.background],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Button { dismiss() } label: { Label("Back", systemImage: "chevron.left") }
                        .buttonStyle(.bordered)
                    Text("CINE-MEDIA  /  \(movie.mediaKind.label.uppercased())")
                        .font(.system(size: 18, weight: .semibold))
                        .tracking(3)
                        .foregroundStyle(CinemaTheme.accent)
                    Text(movie.title)
                        .font(.system(size: 72, weight: .bold))
                        .lineLimit(2)
                    if let episode = movie.episodeInfo {
                        Text("\(movie.seriesTitle ?? episode.seriesTitle) · Season \(movie.seasonNumber ?? episode.season) · Episode \(movie.episodeNumber ?? episode.episode)")
                            .font(.title3).foregroundStyle(CinemaTheme.secondaryText)
                    }
                    Text(MoviePresentation.metadata(for: movie))
                        .font(.title3)
                        .foregroundStyle(CinemaTheme.secondaryText)

                    if let tagline = movie.tagline, !tagline.isEmpty {
                        Text(tagline)
                            .font(.title3)
                            .italic()
                            .foregroundStyle(.white.opacity(0.85))
                    }
                    if let overview = movie.overview, !overview.isEmpty {
                        Text(overview)
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.90))
                            .lineLimit(5)
                            .frame(maxWidth: 820, alignment: .leading)
                    }

                    #if CINEVAULT_COMMERCIAL
                    CommercialPlaybackNotice(services: playbackServices.commercial)
                    #endif
                    playbackControls
                        .padding(.top, 16)
                    personalActions
                    fileDetails
                        .padding(.top, 24)
                }
                .padding(.horizontal, 90)
                .padding(.top, 90)
                .padding(.bottom, 100)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .sheet(isPresented: $editing) { NavigationStack { EditMovieView(movie: movie) } }
        .fullScreenCover(item: $playback) { controller in
            PlaybackScreen(controller: controller)
                .ignoresSafeArea()
                .onDisappear { controller.stop() }
        }
        .onDisappear { cancelPreparation() }
        .alert("Format not supported", isPresented: $unsupported) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(PlaybackPreparationError.unsupportedFormat.localizedDescription)
        }
        .alert("Source unavailable", isPresented: $missingSource) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This movie no longer has a NAS source. Add the source again and rescan before playing.")
        }
        .alert("NAS credentials unavailable", isPresented: $credentialUnavailable) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(credentialMessage)
        }
        .alert("Unable to load media", isPresented: Binding(get: { loadingError != nil }, set: { if !$0 { loadingError = nil } })) {
            Button("OK", role: .cancel) { loadingError = nil }
        } message: { Text(loadingError ?? "The file could not be loaded.") }
    }

    private var playbackControls: some View {
        HStack(spacing: 24) {
            if movie.isMissing || movie.source?.isIgnored(filePath: movie.filePath) == true {
                Text("File unavailable. Include it again and rescan before playing.")
                    .font(.callout).foregroundStyle(CinemaTheme.secondaryText)
            } else if !PlaybackController.isNativeCandidate(movie) {
                Button("Format information") { unsupported = true }.buttonStyle(.bordered)
            } else if isPreparing {
                ProgressView("Checking media…")
                Button("Cancel loading") { cancelPreparation() }.buttonStyle(.bordered)
            } else if playbackServices.canResume(movie) {
                Button { play(resume: true) } label: {
                    Label("Resume \(format(playbackServices.resumePosition(movie)))", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)

                Button { play(resume: false) } label: {
                    Label("Start Over", systemImage: "backward.end.fill")
                }
                .buttonStyle(.bordered)
            } else {
                Button { play(resume: false) } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)
            }
            Button {
                if playbackServices.resetProgress(movie) {
                    movie.isWatched.toggle()
                }
            } label: {
                Label(movie.isWatched ? "Mark Unwatched" : "Mark Watched",
                      systemImage: movie.isWatched ? "eye.slash" : "checkmark")
            }
            .buttonStyle(.bordered)
            Button { editing = true } label: {
                Label("Edit", systemImage: "pencil")
            }
            .buttonStyle(.bordered)
        }
    }

    private var personalActions: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                let wasWatchlisted = movie.isWatchlisted
                movie.isWatchlisted.toggle()
                do {
                    try movie.modelContext?.save()
                    watchlistError = nil
                } catch {
                    movie.isWatchlisted = wasWatchlisted
                    watchlistError = "Could not save the watchlist: \(error.localizedDescription)"
                }
            } label: {
                Label(movie.isWatchlisted ? "Remove from Watchlist" : "Add to Watchlist",
                      systemImage: movie.isWatchlisted ? "bookmark.fill" : "bookmark")
            }
            .buttonStyle(.bordered)
            if let watchlistError {
                Text(watchlistError).font(.caption).foregroundStyle(.red)
            }
        }
    }

    private var fileDetails: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("IN YOUR LIBRARY")
                .font(.system(size: 17, weight: .semibold))
                .tracking(2)
                .foregroundStyle(CinemaTheme.secondaryText)
            HStack(spacing: 20) {
                if let source = movie.source {
                    Label(source.name, systemImage: "externaldrive")
                }
                Text(ByteCountFormatter.string(fromByteCount: movie.fileSize, countStyle: .file))
                if movie.isWatched {
                    Label("Watched", systemImage: "checkmark.circle.fill")
                }
                if movie.isHidden {
                    Label("Hidden", systemImage: "eye.slash")
                }
            }
            .font(.caption)
            Text(movie.filePath)
                .font(.caption2)
                .foregroundStyle(CinemaTheme.secondaryText)
                .lineLimit(2)
        }
    }

    private func format(_ seconds: Double) -> String {
        let total = Int(seconds)
        return total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%d:%02d", total / 60, total % 60)
    }

    private func play(resume: Bool) {
        guard PlaybackController.isNativeCandidate(movie) else {
            unsupported = true
            return
        }
        cancelPreparation()
        let token = preparationID
        isPreparing = true
        loadingError = nil
        preparationTask = Task { @MainActor in
            defer {
                if preparationID == token { isPreparing = false; preparationTask = nil }
            }
            do {
                guard let controller = try await PlaybackController.prepare(movie: movie, resume: resume, services: playbackServices) else {
                    if preparationID == token && !Task.isCancelled { missingSource = true }
                    return
                }
                guard preparationID == token, !Task.isCancelled else { controller.stop(saveProgress: false); return }
                playback = controller
            } catch is CancellationError {
                // Leaving the detail view or a replacement request owns the UI now.
            } catch let error as CredentialError {
                if preparationID == token && !Task.isCancelled {
                    credentialMessage = error.localizedDescription; credentialUnavailable = true
                }
            } catch let error as PlaybackPreparationError {
                if preparationID == token && !Task.isCancelled {
                    switch error {
                    case .unsupportedFormat: unsupported = true
                    case .fileUnavailable: loadingError = error.localizedDescription
                    }
                }
            } catch {
                if preparationID == token && !Task.isCancelled {
                    loadingError = "Could not read or prepare this file. Check the NAS connection and the file. \(error.localizedDescription)"
                }
            }
        }
    }

    private func cancelPreparation() {
        preparationID = UUID()
        preparationTask?.cancel()
        preparationTask = nil
        isPreparing = false
    }
}

extension PlaybackController: Identifiable {}

/// Rename, re-match against TMDB, hide or reset a movie.
struct EditMovieView: View {
    @Bindable var movie: Movie
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var playbackServices: PlaybackServices
    @State private var title = ""
    @State private var year = ""
    @State private var query = ""
    @State private var results: [TMDBMovieSummary] = []
    @State private var message: String?

    var body: some View {
        Form {
            Section { Button("Back") { dismiss() } }
            Section("Details") {
                TextField("Title", text: $title)
                TextField("Year", text: $year).keyboardType(.numberPad)
                Button("Save Title") {
                    movie.setTitle(title)
                    movie.year = Int(year)
                    movie.isLocked = true
                    do {
                        try movie.modelContext?.save()
                        dismiss()
                    } catch {
                        message = "Could not save the movie: \(error.localizedDescription)"
                    }
                }
            }
            Section {
                TextField("Search TMDB", text: $query)
                Button("Search") { search() }
                ForEach(results, id: \.id) { result in
                    Button {
                        rematch(result)
                    } label: {
                        HStack {
                            AsyncImage(url: TMDB.imageURL(result.posterPath, size: "w92")) { $0.resizable() } placeholder: { Color.gray }
                                .frame(width: 60, height: 90)
                            VStack(alignment: .leading) {
                                Text(result.title)
                                Text(result.year.map(String.init) ?? "").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: {
                Text("Fix Match")
            } footer: {
                if let message { Text(message) }
            }
            Section("Library") {
                Toggle("Hidden from library", isOn: $movie.isHidden)
                Button("Reset Progress") {
                    if playbackServices.resetProgress(movie) {
                        movie.isWatched = false
                    }
                }
                #if CINEVAULT_COMMERCIAL
                if let error = playbackServices.commercial.storageError {
                    Text(error).foregroundStyle(.red)
                }
                #endif
                Button("Reset to File Name") {
                    MetadataService.shared.resetToFileName(movie)
                    Task {
                        do {
                            if try MetadataService.shared.hasKey {
                                try await MetadataService.shared.match(movie)
                            }
                            try movie.modelContext?.save()
                            dismiss()
                        } catch {
                            message = "Could not reset the movie: \(error.localizedDescription)"
                        }
                    }
                }
            }
        }
        .navigationTitle("Edit")
        .onAppear {
            title = movie.title
            year = movie.year.map(String.init) ?? ""
            query = movie.seriesTitle ?? movie.episodeInfo?.seriesTitle ?? movie.title
        }
    }

    private func search() {
        Task {
            do {
                guard try MetadataService.shared.hasKey else {
                    message = "Add a TMDB key in Settings first."
                    return
                }
                results = if movie.episodeInfo != nil {
                    try await MetadataService.shared.searchTV(title: query, year: nil)
                } else {
                    try await MetadataService.shared.search(title: query, year: nil)
                }
                message = results.isEmpty ? "No results." : nil
            } catch {
                message = error.localizedDescription
            }
        }
    }

    private func rematch(_ result: TMDBMovieSummary) {
        Task {
            do {
                let applied: Bool
                if movie.episodeInfo != nil {
                    applied = try await MetadataService.shared.applyTV(seriesID: result.id, to: movie, force: true)
                } else {
                    applied = try await MetadataService.shared.apply(tmdbID: result.id, to: movie, force: true)
                }
                guard applied else { return }
                try movie.modelContext?.save()
                dismiss()
            } catch {
                message = error.localizedDescription
            }
        }
    }
}
