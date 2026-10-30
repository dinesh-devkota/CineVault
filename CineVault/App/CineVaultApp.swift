import Foundation
import SwiftData
import SwiftUI

@main
struct CineVaultApp: App {
    @StateObject private var scanner = LibraryScanner()

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if DesignPreview.isEnabled {
                DesignPreview()
                    .environmentObject(scanner)
            } else {
                RootView()
                    .environmentObject(scanner)
                    .modelContainer(for: [MediaSource.self, Movie.self])
            }
            #else
            RootView()
                .environmentObject(scanner)
                .modelContainer(for: [MediaSource.self, Movie.self])
            #endif
        }
    }
}

struct RootView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var scanner: LibraryScanner

    var body: some View {
        TabView {
            NavigationStack { HomeView().movieDestination() }.tabItem { Text("Home") }
            NavigationStack { LibraryView().movieDestination() }.tabItem { Text("Movies") }
            NavigationStack { SearchView().movieDestination() }.tabItem { Image(systemName: "magnifyingglass") }
            NavigationStack { SourcesView() }.tabItem { Text("Sources") }
            NavigationStack { SettingsView() }.tabItem { Image(systemName: "gearshape") }
        }
        .tint(CinemaTheme.accent)
        .preferredColorScheme(.dark)
        .task {
            #if DEBUG
            if DesignPreview.isEnabled { return }
            #endif
            await scanner.scanAll(context: context)
        }
    }
}

extension View {
    func movieDestination() -> some View {
        navigationDestination(for: Movie.self) { MovieDetailView(movie: $0) }
    }
}
