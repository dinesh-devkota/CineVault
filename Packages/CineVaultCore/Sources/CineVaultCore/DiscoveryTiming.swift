import Foundation

/// Browsing receives its full window after startup, plus bounded time for late address lookups.
public struct DiscoveryTiming {
    private let requestedAt: TimeInterval
    private let startup: TimeInterval
    private let browse: TimeInterval
    private let resolutionGrace: TimeInterval
    private var startedAt: TimeInterval?
    public var hasStarted: Bool { startedAt != nil }

    public init(requestedAt: TimeInterval, startup: TimeInterval = 10,
                browse: TimeInterval = 15, resolutionGrace: TimeInterval = 8) {
        self.requestedAt = requestedAt; self.startup = startup
        self.browse = browse; self.resolutionGrace = resolutionGrace
    }

    public mutating func started(at now: TimeInterval) {
        if startedAt == nil { startedAt = now }
    }

    public func remaining(at now: TimeInterval, pendingResolution: Bool) -> TimeInterval {
        guard let startedAt else { return max(0, requestedAt + startup - now) }
        let browseEnd = startedAt + browse
        let end = now >= browseEnd && pendingResolution ? browseEnd + resolutionGrace : browseEnd
        return max(0, end - now)
    }
}
