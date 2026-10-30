import Foundation
import Security
import XCTest
@testable import CineVault

private final class FakeKeychainAccess: KeychainAccess {
    var data: Data?
    var copyError: OSStatus?
    var updateError: OSStatus?
    var duplicateOnAdd = false
    var addCount = 0
    var deleteCount = 0

    func copyMatching(_ query: [String: Any], result: inout CFTypeRef?) -> OSStatus {
        if let copyError { return copyError }
        guard let data else { return errSecItemNotFound }
        result = data as NSData
        return errSecSuccess
    }

    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        if let updateError { return updateError }
        guard data != nil else { return errSecItemNotFound }
        data = attributes[kSecValueData as String] as? Data
        return errSecSuccess
    }

    func add(_ attributes: [String: Any]) -> OSStatus {
        addCount += 1
        if duplicateOnAdd {
            data = Data("concurrent".utf8)
            return errSecDuplicateItem
        }
        data = attributes[kSecValueData as String] as? Data
        return errSecSuccess
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        deleteCount += 1
        guard data != nil else { return errSecItemNotFound }
        data = nil
        return errSecSuccess
    }
}

final class CredentialStoreTests: XCTestCase {
    func testFailedUpdatePreservesExistingPassword() throws {
        let access = FakeKeychainAccess()
        access.data = Data("old".utf8)
        access.updateError = errSecInteractionNotAllowed
        let store = CredentialStore(access: access)

        XCTAssertThrowsError(try store.set("new", for: "nas")) {
            XCTAssertEqual($0 as? CredentialError, .keychain(errSecInteractionNotAllowed))
        }
        XCTAssertEqual(try store.get("nas"), "old")
        XCTAssertEqual(access.deleteCount, 0)
        XCTAssertEqual(access.addCount, 0)
    }

    func testAddUpdateAndDelete() throws {
        let access = FakeKeychainAccess()
        let store = CredentialStore(access: access)

        XCTAssertNil(try store.get("nas"))
        try store.set("first", for: "nas")
        try store.set("second", for: "nas")
        XCTAssertEqual(try store.get("nas"), "second")
        XCTAssertEqual(access.addCount, 1)
        try store.set("", for: "nas")
        XCTAssertNil(try store.get("nas"))
        XCTAssertNoThrow(try store.delete("nas"))
    }

    func testConcurrentAddIsUpdatedWithoutDeleting() throws {
        let access = FakeKeychainAccess()
        access.duplicateOnAdd = true
        let store = CredentialStore(access: access)

        try store.set("desired", for: "nas")

        XCTAssertEqual(try store.get("nas"), "desired")
        XCTAssertEqual(access.deleteCount, 0)
    }

    func testReadErrorsAndCorruptDataAreReported() {
        let access = FakeKeychainAccess()
        let store = CredentialStore(access: access)
        access.copyError = errSecInteractionNotAllowed
        XCTAssertThrowsError(try store.get("nas")) {
            XCTAssertEqual($0 as? CredentialError, .keychain(errSecInteractionNotAllowed))
        }
        access.copyError = nil
        access.data = Data([0xff])
        XCTAssertThrowsError(try store.get("nas")) {
            XCTAssertEqual($0 as? CredentialError, .invalidData)
        }
    }
}
