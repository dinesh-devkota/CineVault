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
    case all = "All", unwatched = "Unwatched", inProgress = "In Progress", watched = "Watched", hidden = "Hidden", watchlist = "Watchlist"
    var id: String { rawValue }

    func matches(_ movie: Movie) -> Bool {
        switch self {
        case .all: return !movie.isHidden
        case .unwatched: return !movie.isHidden && !movie.isWatched
        case .inProgress: return !movie.isHidden && movie.isInProgress
        case .watched: return !movie.isHidden && movie.isWatched
        case .hidden: return movie.isHidden
        case .watchlist: return !movie.isHidden && movie.isWatchlisted
        }
    }
}

enum LibraryQuery {
    static func visible(_ movies: [Movie], sort: LibrarySort, filter: LibraryFilter,
                        genre: String? = nil, sourceID: UUID? = nil, decade: Int? = nil) -> [Movie] {
        sort.sorted(movies.filter { movie in
            !movie.isMissing && filter.matches(movie)
                && (genre.map { movie.genres.contains($0) } ?? true)
                && (sourceID.map { movie.source?.id == $0 } ?? true)
                && (decade.map { selected in movie.year.map { $0 / 10 * 10 == selected } ?? false } ?? true)
        })
    }

    static func watchlist(_ movies: [Movie]) -> [Movie] {
        visible(movies, sort: .title, filter: .watchlist)
    }

    static func selectedSourceID(_ selection: String, sources: [MediaSource]) -> UUID? {
        guard let id = UUID(uuidString: selection), sources.contains(where: { $0.id == id }) else { return nil }
        return id
    }
}

@MainActor
final class LibraryPreferences: ObservableObject {
    private let defaults: UserDefaults
    @Published var sort: LibrarySort { didSet { defaults.set(sort.rawValue, forKey: "librarySort") } }
    @Published var filter: LibraryFilter { didSet { defaults.set(filter.rawValue, forKey: "libraryFilter") } }
    @Published var genre: String { didSet { defaults.set(genre, forKey: "libraryGenre") } }
    @Published var sourceSelection: String { didSet { defaults.set(sourceSelection, forKey: "librarySourceID") } }
    @Published var decade: Int { didSet { defaults.set(decade, forKey: "libraryDecade") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        sort = defaults.string(forKey: "librarySort").flatMap(LibrarySort.init(rawValue:)) ?? .title
        filter = defaults.string(forKey: "libraryFilter").flatMap(LibraryFilter.init(rawValue:)) ?? .all
        genre = defaults.string(forKey: "libraryGenre") ?? ""
        sourceSelection = defaults.string(forKey: "librarySourceID") ?? ""
        let savedDecade = defaults.integer(forKey: "libraryDecade")
        decade = savedDecade > 0 && savedDecade % 10 == 0 ? savedDecade : 0
    }

    func sourcesChanged(from previousIDs: [UUID], to sources: [MediaSource]) {
        if let selected = UUID(uuidString: sourceSelection), previousIDs.contains(selected),
           LibraryQuery.selectedSourceID(sourceSelection, sources: sources) == nil {
            sourceSelection = ""
        }
    }
}

enum LibrarySearch {
    static func matches(_ movie: Movie, text: String, normalized: String) -> Bool {
        !movie.isMissing && !movie.isHidden &&
            ((!normalized.isEmpty && TitleMatcher.normalize(movie.title).contains(normalized))
             || movie.genres.contains { $0.localizedCaseInsensitiveContains(text) }
             || movie.fileName.localizedCaseInsensitiveContains(text))
    }
}

struct LibraryView: View {
    @Query private var movies: [Movie]
    @Query(sort: \MediaSource.name) private var sources: [MediaSource]
    @StateObject private var preferences = LibraryPreferences()

    private var genres: [String] {
        var values = Set(movies.flatMap(\.genres).filter { !$0.isEmpty })
        if !preferences.genre.isEmpty { values.insert(preferences.genre) }
        return values.sorted()
    }

    private var decades: [Int] {
        var values = Set(movies.filter { !$0.isHidden && !$0.isMissing }.compactMap(\.year)
            .filter { $0 > 0 }.map { $0 / 10 * 10 })
        if preferences.decade > 0 { values.insert(preferences.decade) }
        return values.sorted(by: >)
    }

    private var selectedSourceID: UUID? {
        LibraryQuery.selectedSourceID(preferences.sourceSelection, sources: sources)
    }

    private var sourceBinding: Binding<String> {
        Binding(get: { selectedSourceID?.uuidString ?? "" }, set: { preferences.sourceSelection = $0 })
    }

    private var visible: [Movie] {
        LibraryQuery.visible(movies, sort: preferences.sort, filter: preferences.filter,
                             genre: preferences.genre.isEmpty ? nil : preferences.genre, sourceID: selectedSourceID,
                             decade: preferences.decade == 0 ? nil : preferences.decade)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR COLLECTION")
                            .font(.system(size: 18, weight: .semibold))
                            .tracking(3)
                            .foregroundStyle(CinemaTheme.accent)
                        Text("Movies").font(.system(size: 62, weight: .bold))
                    }
                    Spacer()
                    Text("\(visible.count) titles")
                        .font(.callout)
                        .foregroundStyle(CinemaTheme.secondaryText)
                }

                HStack(spacing: 24) {
                    Picker("Sort", selection: $preferences.sort) {
                        ForEach(LibrarySort.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Picker("Show", selection: $preferences.filter) {
                        ForEach(LibraryFilter.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Picker("Genre", selection: $preferences.genre) {
                        Text("All Genres").tag("")
                        ForEach(genres, id: \.self) { Text($0).tag($0) }
                    }
                    Spacer()
                }
                .pickerStyle(.menu)

                HStack(spacing: 24) {
                    Picker("Source", selection: sourceBinding) {
                        Text("All Sources").tag("")
                        ForEach(sources) { Text($0.name).tag($0.id.uuidString) }
                    }
                    .frame(maxWidth: 560, alignment: .leading)
                    Picker("Decade", selection: $preferences.decade) {
                        Text("All Decades").tag(0)
                        ForEach(decades, id: \.self) { Text("\($0)s").tag($0) }
                    }
                    Spacer()
                }
                .pickerStyle(.menu)

                if movies.isEmpty {
                    ContentUnavailableView("No movies yet", systemImage: "film",
                                           description: Text("Add your NAS in the Sources tab."))
                        .padding(.top, 100)
                } else if visible.isEmpty {
                    ContentUnavailableView("No matching movies", systemImage: "line.3.horizontal.decrease.circle",
                                           description: Text("Try another filter, source, decade or genre."))
                        .padding(.top, 100)
                } else {
                    PosterGrid(movies: visible)
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
        .onChange(of: sources.map(\.id)) { previousIDs, _ in
            preferences.sourcesChanged(from: previousIDs, to: sources)
        }
    }
}

struct HomeView: View {
    @Query(sort: \Movie.dateAdded, order: .reverse) private var movies: [Movie]
    @EnvironmentObject private var scanner: LibraryScanner

    private var available: [Movie] { movies.filter { !$0.isHidden && !$0.isMissing } }
    private var continuing: [Movie] {
        available.filter(\.isInProgress)
            .sorted { ($0.lastPlayedAt ?? .distantPast) > ($1.lastPlayedAt ?? .distantPast) }
    }
    private var featured: Movie? {
        let unwatched = available.filter { !$0.isInProgress && !$0.isWatched }
        return unwatched.first { $0.backdropPath != nil } ?? unwatched.first ?? available.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 48) {
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("YOUR PERSONAL CINEMA")
                            .font(.system(size: 18, weight: .semibold))
                            .tracking(3)
                            .foregroundStyle(CinemaTheme.accent)
                        Text("Welcome to CineVault")
                            .font(.system(size: 54, weight: .bold))
                    }
                    Spacer()
                    if let status = scanner.status {
                        Text(status)
                            .font(.caption)
                            .foregroundStyle(CinemaTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                if available.isEmpty {
                    ContentUnavailableView("Your movie collection starts here", systemImage: "play.rectangle.on.rectangle",
                                           description: Text("Add a NAS source, then add a TMDB key for artwork and details."))
                        .padding(.top, 100)
                } else {
                    ContinueWatchingRow(movies: Array(continuing.prefix(20)))
                    PosterRow(title: "Watchlist", movies: Array(LibraryQuery.watchlist(movies).prefix(20)))
                    if let featured {
                        FeaturedMovieCard(movie: featured)
                    }
                    PosterRow(title: "Recently Added", movies: Array(available.prefix(20)))
                    PosterRow(title: "Unwatched", movies: Array(available.filter { !$0.isWatched }.prefix(20)))
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
    }
}

struct SearchView: View {
    @Query private var movies: [Movie]
    @State private var text = ""

    private var results: [Movie] {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        let needle = TitleMatcher.normalize(text)
        return movies.filter { LibrarySearch.matches($0, text: text, normalized: needle) }
        .sorted { $0.sortTitle < $1.sortTitle }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Text("Search").font(.system(size: 62, weight: .bold))
                if text.isEmpty {
                    ContentUnavailableView("Search your collection", systemImage: "magnifyingglass",
                                           description: Text("Find a movie by title, genre or file name."))
                        .padding(.top, 100)
                } else if results.isEmpty {
                    ContentUnavailableView("No results", systemImage: "magnifyingglass",
                                           description: Text("Try a different search."))
                        .padding(.top, 100)
                } else {
                    Text("\(results.count) results")
                        .foregroundStyle(CinemaTheme.secondaryText)
                    PosterGrid(movies: results)
                }
            }
            .padding(.horizontal, 70)
            .padding(.bottom, 100)
        }
        .background(CinemaBackground())
        .searchable(text: $text, prompt: "Titles, genres, files")
    }
}

struct PosterGrid: View {
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?
    private let columns = [GridItem(.adaptive(minimum: CinemaTheme.posterWidth, maximum: CinemaTheme.posterWidth), spacing: 48)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 55) {
            ForEach(movies) { movie in
                NavigationLink(value: movie) {
                    PosterView(movie: movie, isFocused: focusedMovie == movie.id)
                }
                .buttonStyle(.plain)
                .focused($focusedMovie, equals: movie.id)
            }
        }
        .padding(.vertical, 24)
    }
}

struct PosterRow: View {
    let title: String
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?

    var body: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 42) {
                        ForEach(movies) { movie in
                            NavigationLink(value: movie) {
                                PosterView(movie: movie, isFocused: focusedMovie == movie.id)
                            }
                            .buttonStyle(.plain)
                            .focused($focusedMovie, equals: movie.id)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct FeaturedMovieCard: View {
    let movie: Movie

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                MovieBackdrop(movie: movie)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.95), location: 0),
                        .init(color: .black.opacity(0.75), location: 0.35),
                        .init(color: .black.opacity(0.10), location: 1),
                    ],
                    startPoint: .leading,
                    endPoint: .trailing
                )
                LinearGradient(colors: [.clear, .black.opacity(0.5)],
                               startPoint: .top, endPoint: .bottom)

                VStack(alignment: .leading, spacing: 18) {
                    Text("FEATURED MOVIE")
                        .font(.system(size: 18, weight: .semibold))
                        .tracking(3)
                        .foregroundStyle(CinemaTheme.accent)
                    Text(movie.title)
                        .font(.system(size: 52, weight: .bold))
                        .lineLimit(2)
                    Text(MoviePresentation.metadata(for: movie))
                        .font(.callout)
                        .foregroundStyle(CinemaTheme.secondaryText)
                    if let overview = movie.overview, !overview.isEmpty {
                        Text(overview)
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(2)
                    }
                    NavigationLink(value: movie) {
                        Label("View Movie", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(.black)
                    .padding(.top, 8)
                }
                .frame(maxWidth: 720, alignment: .leading)
                .padding(48)
            }
            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .strokeBorder(.white.opacity(0.10), lineWidth: 1)
            }
        }
        .frame(height: 500)
    }
}

struct ContinueWatchingRow: View {
    let movies: [Movie]
    @FocusState private var focusedMovie: UUID?

    var body: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Continue Watching").font(.system(size: 32, weight: .semibold))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 36) {
                        ForEach(movies) { movie in
                            NavigationLink(value: movie) {
                                ContinueWatchingCard(movie: movie, isFocused: focusedMovie == movie.id)
                            }
                            .buttonStyle(.plain)
                            .focused($focusedMovie, equals: movie.id)
                        }
                    }
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct ContinueWatchingCard: View {
    let movie: Movie
    let isFocused: Bool
    private let width: CGFloat = 400

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MovieBackdrop(movie: movie, usePosterFallback: true)
                .frame(width: width, height: 225)
                .overlay(alignment: .bottom) {
                    LinearGradient(colors: [.clear, .black.opacity(0.75)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(height: 90)
                }
                .overlay(alignment: .bottom) {
                    ProgressView(value: movie.progress)
                        .tint(CinemaTheme.accent)
                        .padding(.horizontal, 14)
                        .padding(.bottom, 12)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(.white.opacity(isFocused ? 0.9 : 0.10), lineWidth: isFocused ? 3 : 1)
                }
            Text(movie.title)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(movie.displayYear.isEmpty ? "Continue watching" : movie.displayYear)
                .font(.system(size: 20))
                .foregroundStyle(CinemaTheme.secondaryText)
        }
        .frame(width: width, alignment: .leading)
        .scaleEffect(isFocused ? 1.04 : 1)
        .animation(.easeOut(duration: 0.18), value: isFocused)
    }
}

struct PosterView: View {
    let movie: Movie
    var isFocused = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                ArtworkFallback(title: movie.title)
                VStack(spacing: 18) {
                    Image(systemName: "film")
                        .font(.system(size: 55, weight: .ultraLight))
                        .foregroundStyle(.white.opacity(0.5))
                    Text(movie.title)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .padding(.horizontal, 18)
                }
                AsyncImage(url: TMDB.imageURL(movie.posterPath, size: "w342")) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Color.clear
                }
            }
            .frame(width: CinemaTheme.posterWidth, height: CinemaTheme.posterHeight)
            .clipped()
            .overlay(alignment: .bottom) {
                if movie.isInProgress {
                    ProgressView(value: movie.progress)
                        .tint(CinemaTheme.accent)
                        .padding(10)
                        .background(.black.opacity(0.45))
                }
            }
            .overlay(alignment: .topTrailing) {
                if movie.isWatched {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.white)
                        .padding(10)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(.white.opacity(isFocused ? 0.9 : 0.10), lineWidth: isFocused ? 3 : 1)
            }
            .shadow(color: .black.opacity(isFocused ? 0.6 : 0.25), radius: isFocused ? 20 : 8, y: 10)

            Text(movie.title)
                .font(.system(size: 23, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(movie.displayYear.isEmpty ? "Movie" : movie.displayYear)
                .font(.system(size: 19))
                .foregroundStyle(CinemaTheme.secondaryText)
        }
        .frame(width: CinemaTheme.posterWidth, alignment: .leading)
        .scaleEffect(isFocused ? 1.04 : 1)
        .animation(.easeOut(duration: 0.18), value: isFocused)
    }
}
