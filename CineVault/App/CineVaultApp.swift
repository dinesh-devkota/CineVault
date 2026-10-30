import SwiftData
import SwiftUI

@main
struct CineVaultApp: App {
    @StateObject private var scanner = LibraryScanner()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(scanner)
        }
        .modelContainer(for: [MediaSource.self, Movie.self])
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
        .task { await scanner.scanAll(context: context) }
    }
}

extension View {
    func movieDestination() -> some View {
        navigationDestination(for: Movie.self) { MovieDetailView(movie: $0) }
    }
}
