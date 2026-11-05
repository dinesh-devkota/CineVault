import XCTest
@testable import CineVaultCore

final class DiscoveryTimingTests: XCTestCase {
    func testColdStartupDoesNotConsumeBrowsingWindow() {
        var timing = DiscoveryTiming(requestedAt: 0)
        XCTAssertFalse(timing.hasStarted)
        timing.started(at: 8)
        XCTAssertEqual(timing.remaining(at: 8, pendingResolution: false), 15)
        XCTAssertEqual(timing.remaining(at: 18, pendingResolution: false), 5)
        XCTAssertEqual(timing.remaining(at: 23, pendingResolution: false), 0)
    }

    func testLateLookupCanFinishAfterBrowseDeadline() {
        var timing = DiscoveryTiming(requestedAt: 0)
        timing.started(at: 0)
        XCTAssertEqual(timing.remaining(at: 14, pendingResolution: true), 1)
        XCTAssertEqual(timing.remaining(at: 15, pendingResolution: true), 8)
        XCTAssertEqual(timing.remaining(at: 17, pendingResolution: true), 6)
        XCTAssertEqual(timing.remaining(at: 17, pendingResolution: false), 0)
    }

    func testMissingStartupAndStalledLookupsHaveBoundedTimeouts() {
        let missing = DiscoveryTiming(requestedAt: 0)
        XCTAssertEqual(missing.remaining(at: 10, pendingResolution: true), 0)
        var started = DiscoveryTiming(requestedAt: 0)
        started.started(at: 2)
        XCTAssertEqual(started.remaining(at: 25, pendingResolution: true), 0)
        started.started(at: 30)
        XCTAssertEqual(started.remaining(at: 30, pendingResolution: true), 0)
    }
}
