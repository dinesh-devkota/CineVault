import AMSMB2
import CineVaultCore
import Foundation

struct RemoteFile: Sendable {
    let path: String
    let size: Int64
}

enum SMBError: LocalizedError, Equatable {
    case invalidServer
    case invalidFileSize
    case sourceChanged
    case unexpectedEndOfFile

    var errorDescription: String? {
        switch self {
        case .invalidServer: return "Invalid server address."
        case .invalidFileSize: return "The NAS file is empty or its size could not be read."
        case .sourceChanged: return "The NAS source changed while connecting. Retry."
        case .unexpectedEndOfFile: return "The NAS file ended unexpectedly. Rescan the source and retry."
        }
    }
}

/// Owns one connected SMB2Manager per source and exposes the few operations the app needs.
actor SMBService {
    static let shared = SMBService()

    private let timeout: TimeInterval?
    private let connect: (SMB2Manager, String) async throws -> Void
    private var managers: [SourceConfig: SMB2Manager] = [:]
    private var generations: [UUID: UInt64] = [:]

    init(timeout: TimeInterval? = nil,
         connect: @escaping (SMB2Manager, String) async throws -> Void = { manager, share in
             try await manager.connectShare(name: share)
         }) {
        self.timeout = timeout
        self.connect = connect
    }

    private func manager(for config: SourceConfig) async throws -> SMB2Manager {
        if let existing = managers[config] { return existing }
        let generation = generations[config.id, default: 0]
        let credential = config.username.isEmpty ? nil
            : URLCredential(user: config.username, password: config.password, persistence: .forSession)
        guard let url = config.serverURL, let manager = SMB2Manager(url: url, credential: credential) else {
            throw SMBError.invalidServer
        }
        if let timeout { manager.timeout = timeout }
        try await connect(manager, config.share)
        try Task.checkCancellation()
        guard generations[config.id, default: 0] == generation else { throw SMBError.sourceChanged }
        if let existing = managers[config] { return existing }
        managers[config] = manager
        return manager
    }

    func reset(_ config: SourceConfig) async {
        generations[config.id, default: 0] &+= 1
        // In-flight operations retain their manager until their file handles close.
        // Disconnecting here frees those handles underneath AMSMB2's reads.
        // Its context disconnects on deinit after the final operation releases it.
        managers.removeValue(forKey: config)
    }

    func reset(sourceID: UUID) async {
        generations[sourceID, default: 0] &+= 1
        let stale = managers.filter { $0.key.id == sourceID }
        for config in stale.keys { managers.removeValue(forKey: config) }
    }

    /// Retries once with a fresh connection (NAS may drop idle sessions).
    func withManager<T>(_ config: SourceConfig, _ body: (SMB2Manager) async throws -> T) async throws -> T {
        try Task.checkCancellation()
        var usedManager: SMB2Manager?
        do {
            let manager = try await manager(for: config)
            usedManager = manager
            return try await body(manager)
        } catch is CancellationError {
            throw CancellationError()
        } catch SMBError.sourceChanged {
            throw SMBError.sourceChanged
        } catch {
            try Task.checkCancellation()
            if let usedManager, managers[config] === usedManager {
                // Other reads may still be using this connection.
                managers.removeValue(forKey: config)
            }
            try Task.checkCancellation()
            return try await body(try await manager(for: config))
        }
    }

    func testConnection(_ config: SourceConfig) async throws {
        await reset(config)
        _ = try await withManager(config) { try await $0.contentsOfDirectory(atPath: config.rootPath) }
    }

    func listShares(_ config: SourceConfig) async throws -> [String] {
        let credential = config.username.isEmpty ? nil
            : URLCredential(user: config.username, password: config.password, persistence: .forSession)
        guard let url = config.serverURL, let manager = SMB2Manager(url: url, credential: credential) else {
            throw SMBError.invalidServer
        }
        if let timeout { manager.timeout = timeout }
        return try await manager.listShares().map { $0.name }
    }

    /// Recursively lists indexable video files below the source's root folder.
    func scanVideos(_ config: SourceConfig) async throws -> [RemoteFile] {
        try await withManager(config) { manager in
            var results: [RemoteFile] = []
            var pending = [config.rootPath]
            while let dir = pending.popLast() {
                try Task.checkCancellation()
                let entries = try await manager.contentsOfDirectory(atPath: dir)
                for entry in entries {
                    guard let name = entry[.nameKey] as? String else { continue }
                    let path = (entry[.pathKey] as? String) ?? (dir as NSString).appendingPathComponent(name)
                    if (entry[.isDirectoryKey] as? Bool) == true {
                        if !VideoFile.shouldSkipDirectory(name: name) { pending.append(path) }
                    } else {
                        let size = (entry[.fileSizeKey] as? NSNumber)?.int64Value ?? 0
                        if VideoFile.shouldIndex(path: path, size: size) {
                            results.append(RemoteFile(path: path, size: size))
                        }
                    }
                }
            }
            return results
        }
    }

    func read(_ config: SourceConfig, path: String, offset: Int64, length: Int) async throws -> Data {
        try await withManager(config) { manager in
            let lower = UInt64(offset)
            return try await manager.contents(atPath: path, range: lower..<(lower + UInt64(length)), progress: nil)
        }
    }

    func fileSize(_ config: SourceConfig, path: String) async throws -> Int64 {
        try await withManager(config) { manager in
            let attributes = try await manager.attributesOfItem(atPath: path)
            guard let size = (attributes[.fileSizeKey] as? NSNumber)?.int64Value, size > 0 else {
                throw SMBError.invalidFileSize
            }
            return size
        }
    }
}
