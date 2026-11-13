import Foundation
import Security
















enum Keychain {
    private static let namespace: String = {
        #if DEBUG

        if let identifier = Bundle.main.bundleIdentifier,
           identifier.hasPrefix("com.bxane.nuvx.queuevalidation") {
            return identifier
        }
        #endif
        return "com.bxane.nuvx"
    }()
    private static let service = namespace + ".credentials"
    private static let synchronizableService = namespace







    static func get(_ account: String) -> String? {
        read(account).value
    }


    static func read(_ account: String) -> (value: String?, available: Bool) {
        var result: AnyObject?
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return (nil, true) }
        guard status == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else { return (nil, false) }
        return (value, true)
    }



    @discardableResult
    static func put(_ account: String, _ value: String?) -> Bool {
        guard let value, !value.isEmpty else { return clear(account) }
        let data = Data(value.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let update = SecItemUpdate(baseQuery(account) as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else {
            NSLog("[Nuvx] Keychain update failed for %@: %d", account, update)
            return false
        }
        var item = baseQuery(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecAttrSynchronizable as String] = false
        let added = SecItemAdd(item as CFDictionary, nil)
        if added == errSecSuccess { return true }
        NSLog("[Nuvx] Keychain add failed for %@: %d", account, added)
        return false
    }

    @discardableResult
    static func clear(_ account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account) as CFDictionary)
        if status == errSecSuccess || status == errSecItemNotFound { return true }
        NSLog("[Nuvx] Keychain delete failed for %@: %d", account, status)
        return false
    }



    static func getSynchronizable(_ account: String) -> String? {
        var query = synchronizableQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }



    @discardableResult
    static func putSynchronizable(_ account: String, _ value: String) -> Bool {
        let data = Data(value.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let update = SecItemUpdate(synchronizableQuery(account) as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }
        var item = synchronizableQuery(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecDuplicateItem else { return added == errSecSuccess }
        return SecItemUpdate(synchronizableQuery(account) as CFDictionary, attributes as CFDictionary) == errSecSuccess
    }

    @discardableResult
    static func clearSynchronizable(_ account: String) -> Bool {
        let status = SecItemDelete(synchronizableQuery(account) as CFDictionary)


        return status == errSecSuccess || status == errSecItemNotFound
            || status == errSecNotAvailable || status == errSecMissingEntitlement
    }







    static func isLocked(_ account: String) -> Bool {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        return SecItemCopyMatching(query as CFDictionary, &result) == errSecInteractionNotAllowed
    }

    private static func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func synchronizableQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: synchronizableService,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: true,
        ]
    }
}
