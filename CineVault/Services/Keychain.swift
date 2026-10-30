import Foundation
import Security

enum CredentialError: LocalizedError, Equatable {
    case keychain(OSStatus)
    case invalidData

    var errorDescription: String? {
        switch self {
        case .keychain(let status):
            return "Secure credential storage failed (\(status)). Please retry."
        case .invalidData:
            return "The saved credential could not be decoded."
        }
    }
}

protocol KeychainAccess {
    func copyMatching(_ query: [String: Any], result: inout CFTypeRef?) -> OSStatus
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus
    func add(_ attributes: [String: Any]) -> OSStatus
    func delete(_ query: [String: Any]) -> OSStatus
}

struct SystemKeychainAccess: KeychainAccess {
    func copyMatching(_ query: [String: Any], result: inout CFTypeRef?) -> OSStatus {
        SecItemCopyMatching(query as CFDictionary, &result)
    }

    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    }

    func add(_ attributes: [String: Any]) -> OSStatus {
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        SecItemDelete(query as CFDictionary)
    }
}

struct CredentialStore {
    private let service: String
    private let access: any KeychainAccess

    init(service: String = "com.cinevault.credentials", access: any KeychainAccess = SystemKeychainAccess()) {
        self.service = service
        self.access = access
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func get(_ account: String) throws -> String? {
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = access.copyMatching(request, result: &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw CredentialError.keychain(status) }
        guard let data = item as? Data, let value = String(data: data, encoding: .utf8) else {
            throw CredentialError.invalidData
        }
        return value
    }

    func set(_ value: String, for account: String) throws {
        guard !value.isEmpty else { try delete(account); return }
        let base = query(account)
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8)]
        var status = access.update(base, attributes: attributes)
        if status == errSecItemNotFound {
            var item = base
            item[kSecValueData as String] = Data(value.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = access.add(item)
            if status == errSecDuplicateItem {
                status = access.update(base, attributes: attributes)
            }
        }
        guard status == errSecSuccess else { throw CredentialError.keychain(status) }
    }

    func delete(_ account: String) throws {
        let status = access.delete(query(account))
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CredentialError.keychain(status)
        }
    }
}

enum Keychain {
    private static let store = CredentialStore()

    static func get(_ account: String) throws -> String? { try store.get(account) }
    static func set(_ value: String, for account: String) throws { try store.set(value, for: account) }
    static func delete(_ account: String) throws { try store.delete(account) }
}
