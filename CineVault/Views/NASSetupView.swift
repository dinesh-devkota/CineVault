import SwiftData
import SwiftUI

@MainActor
struct NASSetupView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var scanner: LibraryScanner
    @StateObject private var discovery = NASDiscovery()
    @State private var smb = SMBService(timeout: 10)
    private enum Step { case server, shares, folders }
    @State private var step: Step = .server
    @State private var name = ""
    @State private var host = ""
    @State private var port = "445"
    @State private var username = ""
    @State private var password = ""
    @State private var share = ""
    @State private var path = ""
    @State private var ignoredFolders: [String] = []
    @State private var ignoredFiles: [String] = []
    @State private var exclusionsNotice: String?
    @State private var shares: [String] = []
    @State private var folders: [String] = []
    @State private var folderReady = false
    @State private var showCredentials = false
    @State private var showManualShare = false
    @State private var errorText: String?
    @State private var busy = false
    @State private var requestID = UUID()
    @State private var request: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            content.navigationTitle("Add NAS")
                .onExitCommand { goBack() }
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop(); cancelRequest(); password = "" }
    }

    private var content: some View {
        List {
            Section {
                Button(step == .server ? "Cancel" : "Back") { goBack() }
                if busy { ProgressView("Connecting…") }
                if let errorText { Text(errorText).foregroundStyle(.red) }
            }
            if step == .server {
                Section("Devices on your network") {
                    if discovery.searching { Text("Looking for devices…") }
                    if let error = discovery.error { Text(error).foregroundStyle(.secondary) }
                    ForEach(discovery.devices) { device in
                        Button {
                            invalidate(clearSelection: true)
                            name = device.name; host = device.host; port = String(device.port)
                            username = ""; password = ""; showCredentials = false; showManualShare = false
                            connect()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(device.name)
                                Text(device.host).font(.caption).foregroundStyle(.secondary)
                            }
                        }.disabled(busy)
                    }
                    if !discovery.searching && discovery.devices.isEmpty && discovery.error == nil {
                        Text("No devices advertised. Enter your NAS address below.").foregroundStyle(.secondary)
                    }
                    Button(discovery.searching ? "Searching…" : "Refresh devices") { discovery.start() }
                        .disabled(busy || discovery.searching)
                }
                Section("NAS connection") {
                    TextField("Library name", text: $name)
                    TextField("NAS address", text: Binding(get: { host }, set: {
                        host = $0; username = ""; password = ""; invalidate(clearSelection: true)
                    }))
                    TextField("Port", text: Binding(get: { port }, set: {
                        port = $0; invalidate(clearSelection: true)
                    })).keyboardType(.numberPad)
                    Button("Find shared folders") { connect() }
                }.disabled(busy)
            } else {
                Section {
                    Text(name.isEmpty ? host : name)
                    Text(host).foregroundStyle(.secondary)
                }
            }
            Section {
                Button(showCredentials ? "Hide sign-in" : "Sign in") { showCredentials.toggle() }.disabled(busy)
                if showCredentials {
                    Text("Sign in with your NAS account. Leave both fields blank for guest access.").foregroundStyle(.secondary)
                    TextField("Username", text: Binding(get: { username }, set: { username = $0; invalidate() })).disabled(busy)
                    SecureField("Password", text: Binding(get: { password }, set: { password = $0; invalidate() })).disabled(busy)
                    Button("Find shared folders with these credentials") { connect() }.disabled(busy)
                }
            }
            if step == .shares {
                Section("Shared folders") {
                    ForEach(shares, id: \.self) { item in
                        Button(item) { browse(share: item, path: "") }.disabled(busy)
                    }
                    if shares.isEmpty && errorText == nil && !busy {
                        Text("No shared folders returned. You can enter one manually.").foregroundStyle(.secondary)
                    }
                }
            }
            if step != .folders {
                Section {
                    Button("Enter a share manually") { showManualShare.toggle() }
                    if showManualShare {
                        TextField("Share name", text: Binding(get: { share }, set: {
                            if share != $0 { clearExclusions() }; share = $0; invalidate()
                        }))
                        TextField("Folder within share (optional)", text: Binding(get: { path }, set: {
                            if path != $0 { clearExclusions() }; path = $0; invalidate()
                        }))
                        Button("Open folder") { browse(share: share, path: path) }
                    }
                }.disabled(busy)
            }
            if step == .folders {
                Section("Choose your media folder") {
                    Text("\(share) / \(path.isEmpty ? "Top level" : path)")
                    Text("Choose the parent folder once. All its subfolders will be scanned.").foregroundStyle(.secondary)
                    Button("Use this folder & scan") { save() }
                        .disabled(busy || !folderReady || scanner.isScanning)
                    if !path.isEmpty {
                        Button("Up") { browse(share: share, path: path.split(separator: "/").dropLast().joined(separator: "/")) }.disabled(busy)
                    }
                    Button("Refresh folder") { browse(share: share, path: path) }.disabled(busy)
                    ForEach(folders, id: \.self) { folder in
                        Button(folder) { browse(share: share, path: [path, folder].filter { !$0.isEmpty }.joined(separator: "/")) }.disabled(busy)
                    }
                    if folderReady && folders.isEmpty && errorText == nil {
                        Text("No subfolders. You can use this folder.").foregroundStyle(.secondary)
                    }
                    if scanner.isScanning { Text("Wait for the current library scan to finish.") }
                }
                if let exclusionsNotice { Text(exclusionsNotice).font(.caption).foregroundStyle(.secondary) }
                SourceExclusionsEditor(folders: $ignoredFolders, files: $ignoredFiles,
                    configuration: SourceConfig(id: requestID, host: host, port: Int(port), share: share, rootPath: path,
                        username: username, password: password, ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles))
                    .disabled(busy || !folderReady)
            }
        }
    }

    private func cancelRequest() {
        requestID = UUID()
        request?.cancel(); request = nil; busy = false
    }

    private func invalidate(clearSelection: Bool = false) {
        cancelRequest()
        shares = []; folders = []; folderReady = false; errorText = nil
        if clearSelection { clearExclusions(); share = ""; path = "" }
    }

    private func clearExclusions() {
        if !ignoredFolders.isEmpty || !ignoredFiles.isEmpty {
            ignoredFolders = []; ignoredFiles = []
            exclusionsNotice = "The scan folder changed. Choose ignored paths again for the new folder."
        }
    }

    private func goBack() {
        let wasBusy = busy
        cancelRequest(); errorText = nil
        switch step {
        case .folders: step = .shares; folders = []; folderReady = false
        case .shares: step = .server; discovery.start()
        case .server: if !wasBusy { dismiss() }
        }
    }

    private func configuration(requireShare: Bool) -> SourceConfig? {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
        let number = Int(cleanPort)
        guard !cleanHost.isEmpty else { errorText = "Enter a NAS address or select a device."; return nil }
        guard cleanPort.isEmpty || number.map({ (1...65535).contains($0) }) == true else {
            errorText = "Enter a port between 1 and 65535."; return nil
        }
        let cleanShare = share.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !requireShare || !cleanShare.isEmpty else { errorText = "Select or enter a shared folder."; return nil }
        let cleanPath = path.replacingOccurrences(of: "\\", with: "/").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !cleanPath.split(separator: "/").contains("..") else { errorText = "Choose a folder within this share."; return nil }
        return SourceConfig(id: UUID(), host: cleanHost, port: number, share: cleanShare, rootPath: cleanPath,
            username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password,
            ignoredFolders: ignoredFolders, ignoredFiles: ignoredFiles)
    }

    private func run(_ config: SourceConfig, operation: @escaping @MainActor (UUID) async throws -> Void) {
        cancelRequest()
        discovery.stop()
        let token = requestID
        let service = smb
        busy = true; errorText = nil
        request = Task { @MainActor in
            do { try await operation(token) }
            catch is CancellationError { /* A replacement request owns the screen. */ }
            catch {
                if requestID == token && !Task.isCancelled {
                    errorText = error.localizedDescription; showCredentials = true; showManualShare = true
                    step = .server
                }
            }
            await service.reset(sourceID: config.id)
            if requestID == token { busy = false; request = nil }
        }
    }

    private func connect() {
        guard let config = configuration(requireShare: false) else { return }
        shares = []; folders = []; folderReady = false
        run(config) { token in
            let result = try await smb.listShares(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            clearExclusions()
            shares = result; share = ""; path = ""; showManualShare = result.isEmpty; step = .shares
        }
    }

    private func browse(share selectedShare: String, path selectedPath: String) {
        if selectedShare != share || selectedPath != path { clearExclusions() }
        share = selectedShare; path = selectedPath
        guard let config = configuration(requireShare: true) else { return }
        folders = []; folderReady = false; step = .folders
        run(config) { token in
            let result = try await smb.listFolders(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            folders = result; path = config.rootPath; folderReady = true
        }
    }

    private func save() {
        guard folderReady, !busy, !scanner.isScanning, let config = configuration(requireShare: true) else { return }
        let savedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        run(config) { token in
            _ = try await smb.listFolders(config)
            try Task.checkCancellation()
            guard requestID == token else { return }
            let source = MediaSource(name: savedName.isEmpty ? config.host : savedName,
                host: config.host, port: config.port, share: config.share, rootPath: config.rootPath, username: config.username)
            try source.applyIgnoredPaths(folders: config.ignoredFolders, files: config.ignoredFiles)
            try source.setPassword(config.password)
            context.insert(source)
            do { try context.save() }
            catch {
                let saveError = error
                context.delete(source)
                do { try Keychain.delete("source-\(source.id.uuidString)") }
                catch { throw NSError(domain: "NASSetup", code: 1, userInfo: [NSLocalizedDescriptionKey:
                    "The source could not be saved, and its temporary saved password could not be removed."]) }
                throw saveError
            }
            Task { @MainActor in await scanner.scan(source, context: context) }
            dismiss()
        }
    }
}
