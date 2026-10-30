import SwiftData
import SwiftUI
#if CINEVAULT_COMMERCIAL
import CineVaultCommercial
#endif

struct SourcesView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var scanner: LibraryScanner
    @Query(sort: \MediaSource.name) private var sources: [MediaSource]
    @State private var editing: MediaSource?
    @State private var adding = false

    var body: some View {
        List {
            Section {
                ForEach(sources) { source in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(source.name)
                            Text("smb://\(source.host)/\(source.share)/\(source.rootPath)")
                                .font(.caption).foregroundStyle(.secondary)
                            Text("\(source.movies.count) movies · last scan \(source.lastScanDate?.formatted(date: .abbreviated, time: .shortened) ?? "never")")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Scan") { Task { await scanner.scan(source, context: context) } }
                            .disabled(scanner.isScanning)
                        Button("Edit") { editing = source }
                    }
                }
            } footer: {
                if let status = scanner.status { Text(status) }
            }
            Button("Add NAS Source…") { adding = true }
            Button("Rescan All") { Task { await scanner.scanAll(context: context) } }
                .disabled(scanner.isScanning || sources.isEmpty)
        }
        .navigationTitle("Sources")
        .sheet(isPresented: $adding) { NavigationStack { SourceEditorView(source: nil) } }
        .sheet(item: $editing) { source in NavigationStack { SourceEditorView(source: source) } }
    }
}

struct SourceEditorView: View {
    let source: MediaSource?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var scanner: LibraryScanner

    @State private var name = ""
    @State private var host = ""
    @State private var port = ""
    @State private var share = ""
    @State private var rootPath = ""
    @State private var username = ""
    @State private var password = ""
    @State private var shares: [String] = []
    @State private var message: String?
    @State private var busy = false
    @State private var draftID = UUID()
    @State private var credentialReadFailed = false
    @State private var deletedSourceID: UUID?

    private var config: SourceConfig {
        SourceConfig(id: source?.id ?? draftID, host: host.trimmingCharacters(in: .whitespaces), port: Int(port),
                     share: share, rootPath: rootPath.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")),
                     username: username, password: password)
    }

    var body: some View {
        Form {
            Section("Server") {
                TextField("Name (e.g. Living Room NAS)", text: $name)
                TextField("Host or IP (e.g. 192.168.1.20)", text: $host)
                TextField("Port (optional, default 445)", text: $port).keyboardType(.numberPad)
                TextField("Username (empty for guest)", text: $username)
                SecureField("Password", text: $password)
            }
            Section("Folder") {
                TextField("Share (e.g. media)", text: $share)
                if !shares.isEmpty {
                    Picker("Pick a share", selection: $share) {
                        ForEach(shares, id: \.self) { Text($0).tag($0) }
                    }
                }
                TextField("Folder inside share (e.g. Movies)", text: $rootPath)
            }
            Section {
                if let deletedSourceID {
                    Button("Retry Removing Stored Password") { removePassword(for: deletedSourceID) }
                } else {
                    Button("List Shares") { run { shares = try await SMBService.shared.listShares(config) } }
                        .disabled(busy || scanner.isScanning)
                    Button("Test Connection") {
                        run {
                            try await SMBService.shared.testConnection(config)
                            message = "Connected successfully."
                        }
                    }
                    .disabled(busy || scanner.isScanning)
                    Button("Save & Scan") { save() }
                        .disabled(config.host.isEmpty || share.isEmpty || busy || scanner.isScanning || credentialReadFailed)
                    if source != nil {
                        Button("Delete Source", role: .destructive) { delete() }
                            .disabled(busy || scanner.isScanning)
                    }
                }
            } footer: {
                if busy {
                    ProgressView()
                } else if let message {
                    Text(message)
                } else if scanner.isScanning {
                    Text("Wait for the current scan to finish.")
                }
            }
        }
        .navigationTitle(source == nil ? "Add NAS" : "Edit Source")
        .onAppear(perform: load)
    }

    private func load() {
        guard let source else { return }
        name = source.name
        host = source.host
        port = source.port.map(String.init) ?? ""
        share = source.share
        rootPath = source.rootPath
        username = source.username
        do {
            password = try source.password()
        } catch {
            credentialReadFailed = true
            message = "Could not read the saved password: \(error.localizedDescription)"
        }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        busy = true
        message = nil
        Task {
            do { try await work() } catch { message = "Failed: \(error.localizedDescription)" }
            busy = false
        }
    }

    private func save() {
        guard !scanner.isScanning, !busy, !credentialReadFailed else { return }
        let c = config
        let target = source ?? MediaSource(name: "", host: "", share: "", rootPath: "", username: "")
        if source == nil { target.id = c.id }
        let oldPassword: String
        do {
            oldPassword = try target.password()
            try target.setPassword(password)
        } catch {
            message = "Could not save the password: \(error.localizedDescription)"
            return
        }
        let oldValues = (target.name, target.host, target.port, target.share, target.rootPath, target.username)
        target.name = name.isEmpty ? c.host : name
        target.host = c.host
        target.port = c.port
        target.share = c.share
        target.rootPath = c.rootPath
        target.username = c.username
        if source == nil { context.insert(target) }
        do {
            try context.save()
        } catch {
            if source == nil {
                context.delete(target)
            } else {
                (target.name, target.host, target.port, target.share, target.rootPath, target.username) = oldValues
            }
            do {
                try target.setPassword(oldPassword)
                message = "Could not save the source: \(error.localizedDescription)"
            } catch let restoreError {
                message = "Could not save the source, and its password could not be restored: \(restoreError.localizedDescription)"
            }
            return
        }
        Task {
            await SMBService.shared.reset(sourceID: target.id)
            await scanner.scan(target, context: context)
        }
        dismiss()
    }

    private func delete() {
        guard let source, !scanner.isScanning, !busy else { return }
        let id = source.id
        context.delete(source)
        do {
            try context.save()
            deletedSourceID = id
            Task { await SMBService.shared.reset(sourceID: id) }
            removePassword(for: id)
        } catch {
            message = "Could not delete the source: \(error.localizedDescription)"
        }
    }

    private func removePassword(for id: UUID) {
        do {
            try Keychain.delete("source-\(id.uuidString)")
            dismiss()
        } catch {
            message = "Source removed, but its saved password could not be deleted: \(error.localizedDescription)"
        }
    }
}

struct SettingsView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var playbackServices: PlaybackServices
    @State private var apiKey = ""
    @State private var message: String?
    @State private var credentialReadFailed = false

    var body: some View {
        Form {
            #if CINEVAULT_COMMERCIAL
            Section("Playback") {
                NavigationLink("Playback & Purchases") {
                    CommercialAccountView(services: playbackServices.commercial)
                }
            }
            #endif
            Section {
                SecureField("TMDB API key or read access token", text: $apiKey)
                Button("Save Key & Fetch Missing Metadata") {
                    do {
                        try MetadataService.shared.setAPIKey(apiKey)
                        if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            message = "TMDB key removed."
                        } else {
                            message = "Fetching metadata…"
                            Task { await fetchMetadata(refreshAll: false) }
                        }
                    } catch {
                        message = "Could not save the TMDB key: \(error.localizedDescription)"
                    }
                }
                .disabled(credentialReadFailed)
                Button("Refresh All Metadata") {
                    message = "Refreshing metadata…"
                    Task { await fetchMetadata(refreshAll: true) }
                }
            } header: {
                Text("Metadata (themoviedb.org)")
            } footer: {
                Text(message ?? "Get a free key at themoviedb.org → Settings → API. Posters and details are fetched automatically after each scan.")
            }
            Section("About") {
                Text("CineVault plays MP4/M4V/MOV directly from your NAS over SMB. MKV/AVI files are listed but need to be remuxed to MP4 to play.")
                    .font(.caption)
                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.").font(.caption)
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            do {
                apiKey = try MetadataService.shared.apiKey
                credentialReadFailed = false
            } catch {
                credentialReadFailed = true
                message = "Could not read the TMDB key: \(error.localizedDescription)"
            }
        }
    }

    private func fetchMetadata(refreshAll: Bool) async {
        do {
            guard try MetadataService.shared.hasKey else {
                message = "Add a TMDB key in Settings first."
                return
            }
            var count: Int?
            if refreshAll {
                let movies = try context.fetch(FetchDescriptor<Movie>())
                movies.forEach { $0.metadataFetched = false }
                try context.save()
                count = movies.count
            }
            let result = try await MetadataService.shared.fetchMissing(in: context)
            message = result?.failureMessage ?? count.map { "Refreshed \($0) movies." } ?? "Metadata update complete."
        } catch {
            message = "Could not update metadata: \(error.localizedDescription)"
        }
    }
}
