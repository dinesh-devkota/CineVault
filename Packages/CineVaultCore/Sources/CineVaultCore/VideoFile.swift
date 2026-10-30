import Foundation

/// Decides which files on a share are movies and how they can be played.
public enum VideoFile {
    /// Containers AVPlayer can play natively on tvOS.
    public static let nativeExtensions: Set<String> = ["mp4", "m4v", "mov"]
    /// Containers that are indexed but need a third-party player (e.g. VLCKit).
    public static let otherExtensions: Set<String> = ["mkv", "avi", "wmv", "ts", "m2ts", "webm", "flv", "mpg", "mpeg", "iso"]

    public static func isVideo(path: String) -> Bool {
        let ext = fileExtension(path)
        return nativeExtensions.contains(ext) || otherExtensions.contains(ext)
    }

    public static func isNativelyPlayable(path: String) -> Bool {
        nativeExtensions.contains(fileExtension(path))
    }

    /// Skips samples, trailers, extras and NAS metadata folders/files.
    public static func shouldIndex(path: String, size: Int64, minimumSize: Int64 = 50 * 1024 * 1024) -> Bool {
        guard isVideo(path: path), size >= minimumSize else { return false }
        let parts = path.lowercased().split(separator: "/")
        if parts.contains(where: { shouldSkipDirectory(name: String($0)) }) { return false }
        let stem = ((parts.last.map(String.init) ?? "") as NSString).deletingPathExtension
        if stem.hasPrefix("._") { return false }
        let markers = ["sample", "trailer", "featurette", "behindthescenes", "deleted", "extra"]
        return !markers.contains(where: { marker in
            stem == marker || stem.hasSuffix("-" + marker) || stem.hasSuffix("." + marker) || stem.hasSuffix("_" + marker)
        })
    }

    public static func shouldSkipDirectory(name: String) -> Bool {
        let lower = name.lowercased()
        if lower.hasPrefix(".") || lower.hasPrefix("@") || lower.hasPrefix("#") || lower.hasPrefix("$") { return true }
        return ["sample", "samples", "extras", "featurettes", "trailers", "behind the scenes", "deleted scenes",
                "lost+found", "recycle.bin", "system volume information"].contains(lower)
    }

    /// Uniform Type Identifier passed to AVAssetResourceLoader.
    public static func contentType(path: String) -> String {
        switch fileExtension(path) {
        case "mov": return "com.apple.quicktime-movie"
        case "m4v": return "com.apple.m4v-video"
        default: return "public.mpeg-4"
        }
    }

    static func fileExtension(_ path: String) -> String {
        (path as NSString).pathExtension.lowercased()
    }
}
