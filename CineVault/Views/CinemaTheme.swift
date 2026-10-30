import CineVaultCore
import Foundation
import SwiftUI

enum CinemaTheme {
    static let accent = Color(red: 1.0, green: 0.60, blue: 0.22)
    static let background = Color(red: 0.045, green: 0.055, blue: 0.075)
    static let secondaryText = Color.white.opacity(0.66)
    static let posterWidth: CGFloat = 240
    static let posterHeight: CGFloat = 360

    static func artworkColors(for title: String) -> [Color] {
        let palette = [
            Color(red: 0.14, green: 0.33, blue: 0.38),
            Color(red: 0.31, green: 0.18, blue: 0.28),
            Color(red: 0.30, green: 0.25, blue: 0.15),
            Color(red: 0.15, green: 0.23, blue: 0.38),
            Color(red: 0.18, green: 0.30, blue: 0.23),
            Color(red: 0.34, green: 0.18, blue: 0.15),
        ]
        let index = title.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % palette.count }
        return [palette[index], background]
    }
}

struct CinemaBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                Color(red: 0.10, green: 0.14, blue: 0.17),
                CinemaTheme.background,
                .black,
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }
}

struct MovieBackdrop: View {
    let movie: Movie
    var usePosterFallback = false

    private var artworkPath: String? {
        movie.backdropPath ?? (usePosterFallback ? movie.posterPath : nil)
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(
                    colors: CinemaTheme.artworkColors(for: movie.title),
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Image(systemName: "film.stack")
                    .font(.system(size: 120, weight: .ultraLight))
                    .foregroundStyle(.white.opacity(0.08))

                AsyncImage(url: TMDB.imageURL(artworkPath, size: "w1280")) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Color.clear
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
            }
        }
    }
}

enum MoviePresentation {
    static func metadata(for movie: Movie) -> String {
        var parts: [String] = []
        if let year = movie.year { parts.append(String(year)) }
        if let runtime = movie.runtimeMinutes, runtime > 0 {
            parts.append(runtime >= 60 ? "\(runtime / 60)h \(runtime % 60)m" : "\(runtime)m")
        }
        if let rating = movie.rating, rating > 0 {
            parts.append(String(format: "★ %.1f", rating))
        }
        if let genre = movie.genres.first { parts.append(genre) }
        return parts.joined(separator: "   ·   ")
    }
}
