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
            kSecAttrSynchronizable as String: false,
        ]
    }

}
