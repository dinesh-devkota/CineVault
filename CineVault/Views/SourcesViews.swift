import SwiftData
import SwiftUI

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

    private var config: SourceConfig {
        SourceConfig(id: source?.id ?? UUID(), host: host.trimmingCharacters(in: .whitespaces), port: Int(port),
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
                Button("List Shares") { run { shares = try await SMBService.shared.listShares(config) } }
                Button("Test Connection") {
                    run {
                        try await SMBService.shared.testConnection(config)
                        message = "Connected successfully."
                    }
                }
                Button("Save & Scan") { save() }.disabled(host.isEmpty || share.isEmpty)
                if source != nil {
                    Button("Delete Source", role: .destructive) { delete() }
                }
            } footer: {
                if busy { ProgressView() } else if let message { Text(message) }
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
        password = source.password
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
        let c = config
        let target = source ?? MediaSource(name: "", host: "", share: "", rootPath: "", username: "")
        target.name = name.isEmpty ? c.host : name
        target.host = c.host
        target.port = c.port
        target.share = c.share
        target.rootPath = c.rootPath
        target.username = c.username
        if source == nil { context.insert(target) }
        target.password = password
        try? context.save()
        Task {
            await SMBService.shared.reset(c)
            await scanner.scan(target, context: context)
        }
        dismiss()
    }

    private func delete() {
        guard let source else { return }
        Keychain.delete("source-\(source.id.uuidString)")
        context.delete(source)
        try? context.save()
        dismiss()
    }
}

struct SettingsView: View {
    @Environment(\.modelContext) private var context
    @State private var apiKey = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                SecureField("TMDB API key or read access token", text: $apiKey)
                Button("Save Key & Fetch Missing Metadata") {
                    MetadataService.shared.apiKey = apiKey
                    message = "Fetching metadata…"
                    Task {
                        await MetadataService.shared.fetchMissing(in: context)
                        message = "Done."
                    }
                }
                Button("Refresh All Metadata") {
                    Task {
                        let movies = (try? context.fetch(FetchDescriptor<Movie>())) ?? []
                        movies.forEach { $0.metadataFetched = false }
                        await MetadataService.shared.fetchMissing(in: context)
                        message = "Refreshed \(movies.count) movies."
                    }
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
        .onAppear { apiKey = MetadataService.shared.apiKey }
    }
}
