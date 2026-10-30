import Foundation
import SwiftData
import SwiftUI

@main
struct CineVaultApp: App {
    @StateObject private var scanner = LibraryScanner()
    @StateObject private var playbackServices = PlaybackServices()

    var body: some Scene {
        WindowGroup {
            Group {
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
            .environmentObject(playbackServices)
        }
    }
}

struct RootView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var scanner: LibraryScanner
    @EnvironmentObject private var playbackServices: PlaybackServices

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
            await playbackServices.refresh()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await playbackServices.refresh() }
            }
        }
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
