import Foundation

/// Literal ignored folders relative to a source's selected scan root. Never changes remote files.
public enum ScanExclusions {
    /// Canonical display/storage form, or nil for an unsafe or root-wide rule.
    public static func normalizeFolder(path: String) -> String? {
        components(path, allowRoot: false)?.joined(separator: "/")
    }

    /// Canonical relative filename, with the same validation as a folder rule.
    public static func normalizeFile(path: String) -> String? {
        components(path, allowRoot: false)?.joined(separator: "/")
    }

    /// `path` is share-relative. Call before listing a directory's children.
    public static func isDirectoryExcluded(path: String, rootPath: String, ignoredFolders: [String]) -> Bool {
        matches(directory: components(path, allowRoot: true), rootPath: rootPath, rules: ignoredFolders)
    }

    /// Folder rules match parents; file rules match only the exact relative filename.
    public static func isFileExcluded(path: String, rootPath: String, ignoredFolders: [String], ignoredFiles: [String] = []) -> Bool {
        guard let file = components(path, allowRoot: false) else { return false }
        if matches(directory: Array(file.dropLast()), rootPath: rootPath, rules: ignoredFolders) { return true }
        guard let root = components(rootPath, allowRoot: true), startsWith(file, prefix: root) else { return false }
        let relative = Array(file.dropFirst(root.count))
        return ignoredFiles.contains { rule in
            guard let ignored = components(rule, allowRoot: false) else { return false }
            return relative.count == ignored.count && startsWith(relative, prefix: ignored)
        }
    }

    private static func matches(directory: [String]?, rootPath: String, rules: [String]) -> Bool {
        guard let directory, !rules.isEmpty, let root = components(rootPath, allowRoot: true),
              startsWith(directory, prefix: root) else { return false }
        let relative = Array(directory.dropFirst(root.count))
        return rules.contains { rule in
            guard let ignored = components(rule, allowRoot: false) else { return false }
            return startsWith(relative, prefix: ignored)
        }
    }

    private static func startsWith(_ path: [String], prefix: [String]) -> Bool {
        path.count >= prefix.count && prefix.indices.allSatisfy { path[$0].lowercased() == prefix[$0].lowercased() }
    }

    private static func components(_ path: String, allowRoot: Bool) -> [String]? {
        let slashed = path.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\", with: "/")
        let stripped = slashed.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        // A single leading separator is a convenience; UNC hosts and URI/drive paths are not relative.
        if !stripped.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, slashed.hasPrefix("//") { return nil }
        if stripped.range(of: #"^[A-Za-z][A-Za-z0-9+.-]*:"#, options: .regularExpression) != nil || stripped.contains("://") { return nil }
        let parts = stripped.split(separator: "/").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if parts.isEmpty { return allowRoot ? [] : nil }
        if parts.joined(separator: "/").range(of: #"^[A-Za-z][A-Za-z0-9+.-]*:"#, options: .regularExpression) != nil { return nil }
        if parts.contains(where: { part in
            part == "." || part == ".." || part.unicodeScalars.contains { $0.value < 32 || (127...159).contains($0.value) }
        }) { return nil }
        // Wildcards otherwise remain literal: "Extras*" cannot match "Extras" or "Extras 2".
        if parts.allSatisfy({ part in part.allSatisfy { $0 == "*" || $0 == "?" } }) { return nil }
        return parts
    }
}
