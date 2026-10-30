import CineVaultCore
import SwiftData
import SwiftUI

enum LibrarySort: String, CaseIterable, Identifiable {
    case title = "Title", recentlyAdded = "Recently Added", year = "Year", rating = "Rating", lastPlayed = "Last Played"
    var id: String { rawValue }

    func sorted(_ movies: [Movie]) -> [Movie] {
        switch self {
        case .title: return movies.sorted { $0.sortTitle < $1.sortTitle }
        case .recentlyAdded: return movies.sorted { $0.dateAdded > $1.dateAdded }
        case .year: return movies.sorted { ($0.year ?? 0) > ($1.year ?? 0) }
        case .rating: return movies.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
        case .lastPlayed: return movies.sorted { ($0.lastPlayedAt ?? .distantPast) > ($1.lastPlayedAt ?? .distantPast) }
        }
    }
}

enum LibraryFilter: String, CaseIterable, Identifiable {
    case all = "All", unwatched = "Unwatched", inProgress = "In Progress", watched = "Watched", hidden = "Hidden"
    var id: String { rawValue }

    func matches(_ movie: Movie) -> Bool {
        switch self {
        case .all: return !movie.isHidden
        case .unwatched: return !movie.isHidden && !movie.isWatched
        case .inProgress: return !movie.isHidden && movie.isInProgress
        case .watched: return !movie.isHidden && movie.isWatched
        case .hidden: return movie.isHidden
        }
    }
}

struct LibraryView: View {
    @Query private var movies: [Movie]
    @AppStorage("librarySort") private var sort: LibrarySort = .title
    @AppStorage("libraryFilter") private var filter: LibraryFilter = .all
    @State private var genre: String?

    private var genres: [String] {
        Array(Set(movies.flatMap(\.genres))).sorted()
    }

    private var visible: [Movie] {
        sort.sorted(movies.filter { !$0.isMissing && filter.matches($0) && (genre == nil || $0.genres.contains(genre!)) })
    }

    var body: some View {
        ScrollView {
            HStack(spacing: 30) {
                Picker("Sort", selection: $sort) {
                    ForEach(LibrarySort.allCases) { Text($0.rawValue).tag($0) }
                }
                Picker("Show", selection: $filter) {
                    ForEach(LibraryFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                Picker("Genre", selection: $genre) {
                    Text("All Genres").tag(String?.none)
                    ForEach(genres, id: \.self) { Text($0).tag(String?.some($0)) }
                }
                Spacer()
                Text("\(visible.count) movies").foregroundStyle(.secondary)
            }
            .pickerStyle(.menu)
            .padding(.bottom, 30)
            PosterGrid(movies: visible)
        }
        .overlay {
            if movies.isEmpty {
                ContentUnavailableView("No movies yet", systemImage: "film",
                                       description: Text("Add your NAS in the Sources tab."))
            }
        }
    }
}

struct HomeView: View {
    @Query(sort: \Movie.dateAdded, order: .reverse) private var movies: [Movie]
    @EnvironmentObject private var scanner: LibraryScanner

    private var available: [Movie] { movies.filter { !$0.isHidden && !$0.isMissing } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                if let status = scanner.status {
                    Text(status).font(.caption).foregroundStyle(.secondary)
                }
                let continuing = available.filter(\.isInProgress)
                    .sorted { ($0.lastPlayedAt ?? .distantPast) > ($1.lastPlayedAt ?? .distantPast) }
                PosterRow(title: "Continue Watching", movies: Array(continuing.prefix(20)))
                PosterRow(title: "Recently Added", movies: Array(available.prefix(20)))
                PosterRow(title: "Unwatched", movies: Array(available.filter { !$0.isWatched }.shuffled().prefix(20)))
            }
        }
        .overlay {
            if movies.isEmpty {
                ContentUnavailableView("Welcome to CineVault", systemImage: "play.rectangle.on.rectangle",
                                       description: Text("Add a NAS share in Sources, and a TMDB key in Settings for posters."))
            }
        }
    }
}

struct SearchView: View {
    @Query private var movies: [Movie]
    @State private var text = ""

    private var results: [Movie] {
        guard !text.isEmpty else { return [] }
        let needle = TitleMatcher.normalize(text)
        return movies.filter { movie in
            !movie.isMissing && (TitleMatcher.normalize(movie.title).contains(needle)
                || movie.genres.contains { $0.localizedCaseInsensitiveContains(text) }
                || movie.fileName.localizedCaseInsensitiveContains(text))
        }
        .sorted { $0.sortTitle < $1.sortTitle }
    }

    var body: some View {
        ScrollView { PosterGrid(movies: results) }
            .searchable(text: $text, prompt: "Titles, genres, files")
    }
}

struct PosterGrid: View {
    let movies: [Movie]
    private let columns = Array(repeating: GridItem(.fixed(260), spacing: 50), count: 6)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 60) {
            ForEach(movies) { movie in
                NavigationLink(value: movie) { PosterView(movie: movie) }
                    .buttonStyle(.borderless)
            }
        }
    }
}

struct PosterRow: View {
    let title: String
    let movies: [Movie]

    var body: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading) {
                Text(title).font(.headline)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 50) {
                        ForEach(movies) { movie in
                            NavigationLink(value: movie) { PosterView(movie: movie) }
                                .buttonStyle(.borderless)
                        }
                    }
                    .padding(.vertical, 30)
                }
                .scrollClipDisabled()
            }
            }
    }
}

struct PosterView: View {
    let movie: Movie

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            AsyncImage(url: TMDB.imageURL(movie.posterPath, size: "w342")) { image in
                image.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                ZStack {
                    LinearGradient(colors: [.indigo, .black], startPoint: .top, endPoint: .bottom)
                    Text(movie.title).font(.headline).multilineTextAlignment(.center).padding()
                }
            }
            .frame(width: 260, height: 390)
            .clipped()
            .overlay(alignment: .bottom) {
                if movie.isInProgress {
                    ProgressView(value: movie.progress).tint(.orange).padding(8)
                }
            }
            .overlay(alignment: .topTrailing) {
                if movie.isWatched {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.white, .green).padding(8)
                }
            }
            Text(movie.title).font(.caption).lineLimit(1)
            Text(movie.displayYear).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(width: 260)
    }
}
