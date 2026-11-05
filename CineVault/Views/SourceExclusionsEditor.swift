import CineVaultCore
import SwiftUI

/// Edits paths relative to one selected scan root, without changing the NAS.
struct SourceExclusionsEditor: View {
    @Binding var folders: [String]
    @Binding var files: [String]
    let configuration: SourceConfig
    @State private var manualPath = ""
    @State private var manualIsFolder = true
    @State private var errorText: String?

    var body: some View {
        Section {
            Text("Paths are relative to \(configuration.rootPath.isEmpty ? configuration.share : configuration.rootPath). Ignoring only hides items from this library; files and playback history are kept.")
                .font(.caption).foregroundStyle(.secondary)
            NavigationLink("Browse folders and files to ignore") {
                NASPathPicker(configuration: configuration, mode: .ignore) { path, isFolder in
                    add(path, folder: isFolder)
                }
            }
            .disabled(configuration.host.isEmpty || configuration.share.isEmpty)
            ForEach(folders, id: \.self) { folder in
                Button("Stop ignoring folder: \(folder)") { folders.removeAll { $0 == folder } }
            }
            ForEach(files, id: \.self) { file in
                Button("Stop ignoring file: \(file)") { files.removeAll { $0 == file } }
            }
            Picker("Ignore", selection: $manualIsFolder) {
                Text("Folder and subfolders").tag(true)
                Text("One file").tag(false)
            }
            TextField("Relative path (e.g. Movies/Extras)", text: $manualPath)
            Button("Add ignored path") { add(manualPath, folder: manualIsFolder) }
                .disabled(manualPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let errorText { Text(errorText).foregroundStyle(.red) }
            if !folders.isEmpty || !files.isEmpty {
                Text("Save to apply. Stop ignoring and rescan to restore items.").font(.caption).foregroundStyle(.secondary)
            }
        } header: { Text("Ignored folders and files") }
    }

    private func add(_ path: String, folder: Bool) {
        guard let normalized = folder ? ScanExclusions.normalizeFolder(path: path) : ScanExclusions.normalizeFile(path: path) else {
            errorText = IgnoredFolderError.invalidPath.localizedDescription
            return
        }
        if folder {
            if !folders.contains(where: { $0.caseInsensitiveCompare(normalized) == .orderedSame }) { folders.append(normalized) }
        } else {
            if !files.contains(where: { $0.caseInsensitiveCompare(normalized) == .orderedSame }) { files.append(normalized) }
        }
        manualPath = ""; errorText = nil
    }
}

/// A bounded, read-only browser. Ignore mode cannot navigate above the selected scan root.
@MainActor
struct NASPathPicker: View {
    enum Mode { case scanRoot, ignore }
    let configuration: SourceConfig
    let mode: Mode
    let onSelect: (String, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var path = ""
    @State private var entries: [NASDirectoryEntry] = []
    @State private var busy = false
    @State private var ready = false
    @State private var loaded = false
    @State private var errorText: String?
    @State private var requestID = UUID()
    @State private var request: Task<Void, Never>?

    private var basePath: String {
        mode == .scanRoot ? "" : configuration.rootPath.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/").joined(separator: "/")
    }
    private var canGoUp: Bool { path.split(separator: "/").count > basePath.split(separator: "/").count }

    var body: some View {
        List {
            Section {
                Button("Back") { dismiss() }
                Text("\(configuration.share) / \(path.isEmpty ? "Top level" : path)")
                if busy { ProgressView("Reading folder…") }
                if let errorText { Text(errorText).foregroundStyle(.red) }
                if mode == .scanRoot {
                    Button(path.isEmpty ? "Use share root" : "Use this folder") { choose(path, directory: true) }
                        .disabled(!ready || busy)
                } else if canGoUp {
                    Button("Ignore this folder and its subfolders") { choose(path, directory: true) }
                        .disabled(!ready || busy)
                }
                if canGoUp {
                    Button("Up") { browse(path.split(separator: "/").dropLast().joined(separator: "/")) }.disabled(busy)
                }
                if mode == .scanRoot && !path.isEmpty {
                    Button("Open share root") { browse("") }.disabled(busy)
                }
                Button("Refresh folder") { browse(path) }.disabled(busy)
            }
            Section("Folders") {
                ForEach(entries.filter(\.isDirectory)) { entry in
                    let child = [path, entry.name].filter { !$0.isEmpty }.joined(separator: "/")
                    Button("Open \(entry.name)") { browse(child) }.disabled(busy)
                    if mode == .ignore {
                        Button("Ignore folder: \(entry.name) (including subfolders)") { choose(child, directory: true) }.disabled(busy)
                    }
                }
            }
            if mode == .ignore {
                Section("Files") {
                    ForEach(entries.filter { !$0.isDirectory }) { entry in
                        Button("Ignore file: \(entry.name)") {
                            choose([path, entry.name].filter { !$0.isEmpty }.joined(separator: "/"), directory: false)
                        }.disabled(busy)
                    }
                }
            }
            if ready && entries.isEmpty { Text("This folder is empty.").foregroundStyle(.secondary) }
        }
        .navigationTitle(mode == .scanRoot ? "Choose scan folder" : "Choose item to ignore")
        .onExitCommand { dismiss() }
        .onAppear {
            if !loaded {
                loaded = true
                let original = configuration.rootPath.trimmingCharacters(in: .whitespacesAndNewlines)
                if original.isEmpty || original == "/" {
                    browse("")
                } else if let normalized = ScanExclusions.normalizeFolder(path: original) {
                    browse(normalized)
                } else {
                    errorText = "Enter a folder within this share before browsing."
                }
            }
        }
        .onDisappear { cancel() }
    }

    private func choose(_ selectedPath: String, directory: Bool) {
        let selected = mode == .scanRoot ? selectedPath : selectedPath.split(separator: "/")
            .dropFirst(basePath.split(separator: "/").count).joined(separator: "/")
        guard mode == .scanRoot || !selected.isEmpty else { return }
        onSelect(selected, directory)
        dismiss()
    }

    private func cancel() {
        requestID = UUID(); request?.cancel(); request = nil; busy = false
    }

    private func browse(_ selectedPath: String) {
        cancel()
        let token = requestID
        let config = SourceConfig(id: UUID(), host: configuration.host, port: configuration.port,
            share: configuration.share, rootPath: selectedPath, username: configuration.username, password: configuration.password)
        let service = SMBService(timeout: 10)
        path = selectedPath; ready = false; busy = true; errorText = nil; entries = []
        request = Task { @MainActor in
            do {
                let result = try await service.listEntries(config)
                try Task.checkCancellation()
                if requestID == token { entries = result; ready = true }
            } catch is CancellationError { /* The next folder or closed picker owns the state. */ }
            catch {
                if requestID == token && !Task.isCancelled { errorText = error.localizedDescription }
            }
            await service.reset(config)
            if requestID == token { busy = false; request = nil }
        }
    }
}
