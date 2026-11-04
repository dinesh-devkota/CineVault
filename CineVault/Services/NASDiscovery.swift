import Combine
import Foundation

struct DiscoveredNAS: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let port: Int
}

/// Resolves advertised SMB services to stable Bonjour hostnames for AMSMB2.
@MainActor
final class NASDiscovery: NSObject, ObservableObject,
    @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
    @Published private(set) var devices: [DiscoveredNAS] = []
    @Published private(set) var searching = false
    @Published private(set) var error: String?
    private var browser: NetServiceBrowser?
    private var services: [String: NetService] = [:]
    private var resolved: [String: DiscoveredNAS] = [:]
    private var deadline: Task<Void, Never>?
    private var generation: UInt = 0

    func start() {
        stop()
        devices = []
        resolved = [:]
        error = nil
        searching = true
        let current = NetServiceBrowser()
        browser = current
        current.delegate = self
        current.schedule(in: .main, forMode: .common)
        current.searchForServices(ofType: "_smb._tcp.", inDomain: "local.")
        let token = generation
        deadline = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(10)) } catch { return }
            guard let self, self.generation == token else { return }
            self.stop()
        }
    }

    /// Retains final results for selection, while stopping all network work.
    func stop() {
        generation &+= 1
        searching = false
        deadline?.cancel()
        deadline = nil
        let previous = browser
        browser = nil
        previous?.delegate = nil
        previous?.stop()
        previous?.remove(from: .main, forMode: .common)
        for service in services.values { detach(service) }
        services.removeAll()
    }

    private func key(_ service: NetService) -> String {
        [service.domain, service.type, service.name].joined(separator: "\u{0}").lowercased()
    }

    private func detach(_ service: NetService) {
        service.delegate = nil
        service.stop()
        service.remove(from: .main, forMode: .common)
    }

    private func publish() {
        var seen = Set<String>()
        devices = resolved.values.sorted {
            let comparison = $0.name.localizedStandardCompare($1.name)
            return comparison == .orderedSame ? $0.id < $1.id : comparison == .orderedAscending
        }.filter { seen.insert($0.id).inserted }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard self.browser === browser, searching else { return }
        let identifier = key(service)
        guard services[identifier] == nil else { return }
        services[identifier] = service
        service.delegate = self
        service.schedule(in: .main, forMode: .common)
        service.resolve(withTimeout: 6)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        guard self.browser === browser, searching else { return }
        let identifier = key(service)
        if let previous = services.removeValue(forKey: identifier) { detach(previous) }
        resolved.removeValue(forKey: identifier)
        publish()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        guard self.browser === browser else { return }
        error = "Could not search for a NAS. Check Local Network access and that both devices are on the same network, or enter the address manually."
        stop()
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        guard self.browser === browser else { return }
        stop()
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let identifier = key(sender)
        guard searching, services[identifier] === sender else { return }
        guard var host = sender.hostName?.trimmingCharacters(in: .whitespacesAndNewlines),
              !host.isEmpty, (1...65535).contains(sender.port) else {
            resolutionFailed(sender)
            return
        }
        if host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty else { resolutionFailed(sender); return }
        resolved[identifier] = DiscoveredNAS(id: "\(host.lowercased()):\(sender.port)",
            name: sender.name.isEmpty ? host : sender.name, host: host, port: sender.port)
        error = nil
        publish()
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolutionFailed(sender)
    }

    private func resolutionFailed(_ sender: NetService) {
        let identifier = key(sender)
        guard searching, services[identifier] === sender else { return }
        services.removeValue(forKey: identifier)
        detach(sender)
        error = "A NAS was found, but its address could not be resolved. Try again or enter its address manually."
    }
}
