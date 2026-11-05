import Combine
import Foundation
import XCTest
@testable import CineVault

@MainActor
final class NASDiscoveryTests: XCTestCase {
    func testResolvesAnAdvertisedSMBServiceAndRetainsResultsWhenStopped() async throws {
        let name = "CineVault Test \(UUID().uuidString.prefix(8))"
        let publisher = NetService(domain: "local.", type: "_smb._tcp.", name: name, port: 1445)
        publisher.publish()
        defer { publisher.stop() }
        let discovery = NASDiscovery()
        let found = expectation(description: "Advertised SMB host and port resolve")
        var resolved: DiscoveredNAS?
        let subscription = discovery.$devices.sink { devices in
            if resolved == nil, let device = devices.first(where: { $0.name == name }) {
                resolved = device
                found.fulfill()
            }
        }
        defer { subscription.cancel(); discovery.stop() }
        discovery.start()
        await fulfillment(of: [found], timeout: 40)
        let device = try XCTUnwrap(resolved)
        XCTAssertFalse(device.host.isEmpty)
        XCTAssertEqual(device.port, 1445)
        discovery.stop()
        XCTAssertFalse(discovery.searching)
        XCTAssertTrue(discovery.devices.contains(device))
    }

    func testStopAndRefreshClearSearchState() {
        let discovery = NASDiscovery()
        discovery.start()
        XCTAssertTrue(discovery.searching)
        discovery.stop()
        XCTAssertFalse(discovery.searching)
        discovery.start()
        XCTAssertTrue(discovery.searching)
        XCTAssertTrue(discovery.devices.isEmpty)
        discovery.stop()
    }

    func testFirstScanFindsADeviceAdvertisedAfterTheOldTenSecondCutoff() async throws {
        let name = "CineVault Late Test \(UUID().uuidString.prefix(8))"
        let publisher = NetService(domain: "local.", type: "_smb._tcp.", name: name, port: 1445)
        let discovery = NASDiscovery()
        let found = expectation(description: "First scan receives late NAS announcement")
        var didFind = false
        let subscription = discovery.$devices.sink { devices in
            if !didFind, devices.contains(where: { $0.name == name }) {
                didFind = true
                found.fulfill()
            }
        }
        discovery.start()
        let delayedPublish = Task {
            do { try await Task.sleep(for: .seconds(12)) } catch { return }
            publisher.publish()
        }
        defer { delayedPublish.cancel(); publisher.stop(); subscription.cancel(); discovery.stop() }
        await fulfillment(of: [found], timeout: 40)
        XCTAssertTrue(discovery.devices.contains { $0.name == name && $0.port == 1445 })
    }
}
