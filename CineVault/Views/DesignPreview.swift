#if DEBUG
import Foundation
import SwiftData
import SwiftUI

/// An in-memory sample library for visual checks; never touches a real library or NAS.
@MainActor
struct DesignPreview: View {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--ui-preview")

    private let container: ModelContainer

    init() {
        do {
            let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
            let container = try ModelContainer(for: MediaSource.self, Movie.self, configurations: configuration)
            let context = container.mainContext
            let source = MediaSource(name: "Preview Library", host: "preview.invalid",
                                     share: "Movies", rootPath: "", username: "")
            context.insert(source)

            let samples: [(title: String, year: Int, genre: String, overview: String)] = [
                ("Midnight Horizon", 2024, "Adventure", "A journey through distant places and the moments that bring us home."),
                ("The Quiet Orbit", 2022, "Science Fiction", "A lone traveller finds an unexpected connection among the stars."),
                ("Beyond the Pines", 2021, "Drama", "Old friends reunite for a weekend far from the noise of the city."),
                ("A Golden Hour", 2020, "Romance", "Two strangers discover the beauty of taking the long way around."),
                ("Ocean Light", 2023, "Documentary", "A voyage across the water, from first light to the last sunset."),
                ("The Last Signal", 2019, "Thriller", "A mysterious message changes the course of an ordinary night."),
            ]
            for (index, sample) in samples.enumerated() {
                let movie = Movie(filePath: "Movies/\(sample.title).mp4",
                                  fileSize: Int64(index + 2) * 1_000_000_000,
                                  source: source)
                movie.setTitle(sample.title)
                movie.parsedTitle = sample.title
                movie.parsedYear = sample.year
                movie.year = sample.year
                movie.genres = [sample.genre]
                movie.overview = sample.overview
                movie.runtimeMinutes = 105 + index * 3
                movie.rating = 7.8
                movie.dateAdded = Date().addingTimeInterval(-Double(index) * 86_400)
                if index == 0 {
                    movie.playbackPosition = 1_800
                    movie.duration = 6_300
                    movie.lastPlayedAt = Date()
                }
                context.insert(movie)
            }
            try context.save()
            self.container = container
        } catch {
            fatalError("Unable to create the in-memory design preview: \(error)")
        }
    }

    var body: some View {
        RootView().modelContainer(container)
    }
}
#endif
