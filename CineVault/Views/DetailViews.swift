import CineVaultCore
import SwiftData
import SwiftUI

struct MovieDetailView: View {
    @Bindable var movie: Movie
    @State private var playback: PlaybackController?
    @State private var editing = false
    @State private var unsupported = false

    var body: some View {
        ZStack {
            AsyncImage(url: TMDB.imageURL(movie.backdropPath, size: "w1280")) { image in
                image.resizable().aspectRatio(contentMode: .fill)
            } placeholder: { Color.black }
            .ignoresSafeArea()
            .overlay(LinearGradient(colors: [.black.opacity(0.95), .black.opacity(0.4)], startPoint: .leading, endPoint: .trailing).ignoresSafeArea())

            HStack(alignment: .top, spacing: 60) {
                PosterView(movie: movie)
                VStack(alignment: .leading, spacing: 20) {
                    Text(movie.title).font(.largeTitle).bold()
                    Text(subtitle).foregroundStyle(.secondary)
                    if let tagline = movie.tagline, !tagline.isEmpty { Text(tagline).italic() }
                    if let overview = movie.overview {
                        Text(overview).lineLimit(6).font(.callout)
                    }
                    HStack(spacing: 30) {
                        if movie.isInProgress {
                            Button { play(resume: true) } label: {
                                Label("Resume \(format(movie.playbackPosition))", systemImage: "play.fill")
                            }
                        }
                        Button { play(resume: false) } label: {
                            Label(movie.isInProgress ? "Play from Start" : "Play", systemImage: "play.fill")
                        }
                        Button {
                            movie.isWatched.toggle()
                            movie.playbackPosition = 0
                        } label: {
                            Label(movie.isWatched ? "Mark Unwatched" : "Mark Watched",
                                  systemImage: movie.isWatched ? "eye.slash" : "checkmark")
                        }
                        Button { editing = true } label: { Label("Edit", systemImage: "pencil") }
                    }
                    .padding(.top, 20)
                    Text(movie.filePath).font(.caption2).foregroundStyle(.tertiary)
                }
                Spacer()
            }
            .padding(80)
        }
        .sheet(isPresented: $editing) { NavigationStack { EditMovieView(movie: movie) } }
        .fullScreenCover(item: $playback) { controller in
            PlayerView(controller: controller)
                .ignoresSafeArea()
                .onDisappear { controller.stop() }
        }
        .alert("Format not supported", isPresented: $unsupported) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Apple TV's built-in player can't play .\((movie.fileName as NSString).pathExtension) files. Remux it to MP4 (e.g. with ffmpeg -c copy) on your NAS and rescan.")
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        if let year = movie.year { parts.append(String(year)) }
        if let runtime = movie.runtimeMinutes, runtime > 0 { parts.append("\(runtime / 60)h \(runtime % 60)m") }
        if let rating = movie.rating, rating > 0 { parts.append(String(format: "★ %.1f", rating)) }
        if !movie.genres.isEmpty { parts.append(movie.genres.joined(separator: ", ")) }
        parts.append(ByteCountFormatter.string(fromByteCount: movie.fileSize, countStyle: .file))
        return parts.joined(separator: " · ")
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
        playback = PlaybackController(movie: movie, resume: resume)
    }
}

extension PlaybackController: Identifiable {}

/// Rename, re-match against TMDB, hide or reset a movie.
struct EditMovieView: View {
    @Bindable var movie: Movie
    @Environment(\.dismiss) private var dismiss
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
                Button("Reset Progress") { movie.playbackPosition = 0; movie.isWatched = false }
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
