import CineVaultCore
import SwiftData
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

struct MovieDetailView: View {
    @Bindable var movie: Movie
    @EnvironmentObject private var playbackServices: PlaybackServices
    @State private var playback: PlaybackController?
    @State private var editing = false
    @State private var unsupported = false
    @State private var missingSource = false

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
                    Text("CINEVAULT  /  MOVIE")
                        .font(.system(size: 18, weight: .semibold))
                        .tracking(3)
                        .foregroundStyle(CinemaTheme.accent)
                    Text(movie.title)
                        .font(.system(size: 72, weight: .bold))
                        .lineLimit(2)
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
        .alert("Format not supported", isPresented: $unsupported) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Apple TV's built-in player can't play .\((movie.fileName as NSString).pathExtension) files. Remux it to MP4 (e.g. with ffmpeg -c copy) on your NAS and rescan.")
        }
        .alert("Source unavailable", isPresented: $missingSource) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This movie no longer has a NAS source. Add the source again and rescan before playing.")
        }
    }

    private var playbackControls: some View {
        HStack(spacing: 24) {
            if playbackServices.canResume(movie) {
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
                movie.isWatched.toggle()
                playbackServices.resetProgress(movie)
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
        guard VideoFile.isNativelyPlayable(path: movie.filePath) else {
            unsupported = true
            return
        }
        guard let controller = PlaybackController(movie: movie, resume: resume, services: playbackServices) else {
            missingSource = true
            return
        }
        playback = controller
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
            Section("Details") {
                TextField("Title", text: $title)
                TextField("Year", text: $year).keyboardType(.numberPad)
                Button("Save Title") {
                    movie.setTitle(title)
                    movie.year = Int(year)
                    movie.isLocked = true
                    try? movie.modelContext?.save()
                    dismiss()
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
                    playbackServices.resetProgress(movie)
                    movie.isWatched = false
                }
                Button("Reset to File Name") {
                    movie.isLocked = false
                    movie.tmdbID = nil
                    movie.setTitle(movie.parsedTitle)
                    movie.year = movie.parsedYear
                    movie.metadataFetched = false
                    Task { try? await MetadataService.shared.match(movie) }
                    dismiss()
                }
            }
        }
        .navigationTitle("Edit")
        .onAppear {
            title = movie.title
            year = movie.year.map(String.init) ?? ""
            query = movie.title
        }
    }

    private func search() {
        guard MetadataService.shared.hasKey else {
            message = "Add a TMDB key in Settings first."
            return
        }
        Task {
            do {
                results = try await MetadataService.shared.search(title: query, year: nil)
                message = results.isEmpty ? "No results." : nil
            } catch {
                message = error.localizedDescription
            }
        }
    }

    private func rematch(_ result: TMDBMovieSummary) {
        Task {
            do {
                try await MetadataService.shared.apply(tmdbID: result.id, to: movie, force: true)
                movie.isLocked = true
                try? movie.modelContext?.save()
                dismiss()
            } catch {
                message = error.localizedDescription
            }
        }
    }
}
