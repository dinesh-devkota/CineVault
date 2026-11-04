import CineVaultCore
import SwiftUI

struct TVShowGroup: Identifiable {
    let id: String
    let title: String
    let episodes: [Movie]
    struct Revision: Equatable {
        let id: UUID
        let path: String
        let title: String?
        let tmdbID: Int?
        let year: Int?
        let season: Int?
        let episode: Int?
    }
    static func revision(_ movie: Movie) -> Revision {
        Revision(id: movie.id, path: movie.filePath, title: movie.seriesTitle,
                 tmdbID: movie.seriesTMDBID, year: movie.year,
                 season: movie.seasonNumber, episode: movie.episodeNumber)
    }
    var posterPath: String? { episodes.compactMap(\.posterPath).first }
    var seasons: [Int] {
        Set(episodes.map(Self.season)).sorted {
            if $0 == -1 { return false }
            if $1 == -1 { return true }
            return $0 < $1
        }
    }
    static func season(_ movie: Movie) -> Int { movie.seasonNumber ?? movie.episodeInfo?.season ?? -1 }
    static func episode(_ movie: Movie) -> Int { movie.episodeNumber ?? movie.episodeInfo?.episode ?? Int.max }
    static func seasonTitle(_ value: Int) -> String {
        value == 0 ? "Specials" : value < 0 ? "Other episodes" : "Season \(value)"
    }
    func episodes(in season: Int) -> [Movie] {
        episodes.filter { Self.season($0) == season }.sorted {
            let left = Self.episode($0), right = Self.episode($1)
            return left == right ? $0.filePath < $1.filePath : left < right
        }
    }

    /// The caller owns filtering and ordering, including the Hidden and Watchlist views.
    static func groups(_ episodes: [Movie]) -> [TVShowGroup] {
        func aliases(_ movie: Movie) -> [String] {
            return [movie.episodeInfo, EpisodeParser.parse(path: movie.filePath)].compactMap { $0 }
                .filter { !$0.seriesTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.map {
                    $0.seriesTitle.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
                        .split(whereSeparator: \.isWhitespace).joined(separator: " ") + "|" + ($0.year.map(String.init) ?? "unknown")
                }
        }
        let prepared = episodes.map { ($0, aliases($0)) }
        var known: [String: Set<Int>] = [:]
        for (movie, keys) in prepared {
            if let id = movie.seriesTMDBID {
                for alias in keys { known[alias, default: []].insert(id) }
            }
        }
        var order: [String] = []
        var grouped: [String: [Movie]] = [:]
        for (movie, keys) in prepared {
            let candidates = Set(keys.flatMap { Array(known[$0] ?? []) })
            let id = movie.seriesTMDBID ?? (candidates.count == 1 ? candidates.first : nil)
            let key = id.map { "tmdb:\($0)" } ?? keys.first.map { "title:\($0)" } ?? "file:\(movie.id)"
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(movie)
        }
        return order.map { key in
            let items = grouped[key] ?? []
            let title = items.compactMap(\.seriesTitle).first(where: { !$0.isEmpty })
                ?? items.compactMap { $0.episodeInfo?.seriesTitle }.first ?? "Unidentified show"
            return TVShowGroup(id: key, title: title, episodes: items)
        }
    }
}

struct TVShowBrowserView: View {
    let episodes: [Movie]
    var initialShowID: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var selectedShowID: String?
    @State private var selectedSeason: Int?
    @State private var selectedEpisodeID: UUID?
    @State private var initialized = false
    @State private var shows: [TVShowGroup] = []
    @FocusState private var focusedItem: String?

    private var selectedShow: TVShowGroup? {
        shows.first { $0.id == selectedShowID }
            ?? shows.first { show in selectedEpisodeID.map { id in show.episodes.contains { $0.id == id } } ?? false }
    }
    private var posterColumns: [GridItem] {
        [GridItem(.adaptive(minimum: CinemaTheme.posterWidth, maximum: CinemaTheme.posterWidth), spacing: 48)]
    }

    var body: some View {
        Group {
            if selectedShowID == nil { content }
            else { content.onExitCommand { goBack() } }
        }
        .onAppear {
            if !initialized {
                initialized = true
                shows = TVShowGroup.groups(episodes)
                selectedShowID = initialShowID
                selectedEpisodeID = shows.first { $0.id == initialShowID }?.episodes.first?.id
            }
        }
        .onChange(of: episodes.map(TVShowGroup.revision)) { _, _ in
            shows = TVShowGroup.groups(episodes)
        }
        .onChange(of: shows.map(\.id)) { _, _ in
            if let show = selectedShow { selectedShowID = show.id }
            else { selectedShowID = nil; selectedSeason = nil; selectedEpisodeID = nil }
        }
        .onChange(of: selectedShow?.seasons) { _, values in
            if let selectedSeason, values?.contains(selectedSeason) != true { self.selectedSeason = nil }
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 28) {
            if selectedShowID != nil || initialShowID != nil {
                HStack(spacing: 28) {
                    Button("Back") { goBack() }
                    if let show = selectedShow {
                        Text(selectedSeason.map { "\(show.title) · \(TVShowGroup.seasonTitle($0))" } ?? show.title)
                            .font(.system(size: 32, weight: .semibold))
                    }
                }
            }
            if let show = selectedShow, let season = selectedSeason {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 400, maximum: 400), spacing: 48)], spacing: 48) {
                    ForEach(show.episodes(in: season)) { episode in
                        let key = episode.id.uuidString
                        let number = TVShowGroup.episode(episode)
                        NavigationLink(value: episode) {
                            TVShowArtwork(title: number == Int.max ? episode.title : "E\(number) · \(episode.title)",
                                subtitle: episode.isWatched ? "Watched" : episode.isInProgress ? "In progress" : "Episode",
                                path: episode.backdropPath, wide: true, focused: focusedItem == key)
                        }
                        .buttonStyle(.plain).focused($focusedItem, equals: key)
                    }
                }
            } else if let show = selectedShow {
                LazyVGrid(columns: posterColumns, spacing: 55) {
                    ForEach(show.seasons, id: \.self) { season in
                        let items = show.episodes(in: season)
                        let key = "season:\(season)"
                        Button { selectedSeason = season } label: {
                            TVShowArtwork(title: TVShowGroup.seasonTitle(season), subtitle: "\(items.count) episodes",
                                path: items.compactMap(\.seasonPosterPath).first, focused: focusedItem == key)
                        }
                        .buttonStyle(.plain).focused($focusedItem, equals: key)
                    }
                }
            } else {
                LazyVGrid(columns: posterColumns, spacing: 55) {
                    ForEach(shows) { show in
                        Button {
                            selectedShowID = show.id
                            selectedEpisodeID = show.episodes.first?.id
                            selectedSeason = nil
                        } label: {
                            TVShowArtwork(title: show.title, subtitle: "\(show.seasons.count) seasons",
                                path: show.posterPath, focused: focusedItem == show.id)
                        }
                        .buttonStyle(.plain).focused($focusedItem, equals: show.id)
                    }
                }
            }
        }
        .padding(.vertical, 24)
    }

    private func goBack() {
        if selectedSeason != nil { selectedSeason = nil }
        else if initialShowID != nil { dismiss() }
        else { selectedShowID = nil; selectedEpisodeID = nil }
    }
}

struct TVShowRow: View {
    let episodes: [Movie]
    @State private var shows: [TVShowGroup] = []
    @FocusState private var focusedShow: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("TV Shows").font(.system(size: 32, weight: .semibold))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 42) {
                    ForEach(shows) { show in
                        NavigationLink {
                            ScrollView {
                                TVShowBrowserView(episodes: episodes, initialShowID: show.id)
                                    .padding(.horizontal, 70).padding(.bottom, 100)
                            }
                            .background(CinemaBackground())
                        } label: {
                            TVShowArtwork(title: show.title, subtitle: "\(show.seasons.count) seasons",
                                path: show.posterPath, focused: focusedShow == show.id)
                        }
                        .buttonStyle(.plain).focused($focusedShow, equals: show.id)
                    }
                }
                .padding(.vertical, 24)
            }
            .scrollClipDisabled()
        }
        .onChange(of: episodes.map(TVShowGroup.revision), initial: true) { _, _ in
            shows = Array(TVShowGroup.groups(episodes).prefix(20))
        }
    }
}

private struct TVShowArtwork: View {
    let title: String
    let subtitle: String
    let path: String?
    var wide = false
    var focused = false
    private var width: CGFloat { wide ? 400 : CinemaTheme.posterWidth }
    private var height: CGFloat { wide ? 225 : CinemaTheme.posterHeight }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                ArtworkFallback(title: title)
                VStack(spacing: 16) {
                    Image(systemName: wide ? "play.rectangle" : "tv").font(.system(size: 50, weight: .ultraLight))
                    Text(title).font(.headline).multilineTextAlignment(.center).lineLimit(3).padding(.horizontal, 18)
                }
                .foregroundStyle(CinemaTheme.secondaryText)
                AsyncImage(url: TMDB.imageURL(path, size: wide ? "w780" : "w342")) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: { Color.clear }
            }
            .frame(width: width, height: height).clipped()
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(focused ? 0.9 : 0.1), lineWidth: focused ? 3 : 1) }
            Text(title).font(.system(size: 23, weight: .semibold)).foregroundStyle(.white).lineLimit(2)
            Text(subtitle).font(.system(size: 19)).foregroundStyle(CinemaTheme.secondaryText)
        }
        .frame(width: width, alignment: .leading)
        .scaleEffect(focused ? 1.04 : 1).animation(.easeOut(duration: 0.18), value: focused)
    }
}
