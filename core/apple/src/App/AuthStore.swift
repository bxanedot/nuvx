import Foundation
import Security
import NuvxShared






enum AuthStore {
    private static let account = "ytmusic.cookie"
    private static let sharedAccount = "ytmusic.session.shared"

    static var cookie: String? {
        get { Keychain.get(account) }
    }

    @discardableResult
    static func save(_ cookie: String) -> Bool { Keychain.put(account, cookie) }




    static var isLocked: Bool {
        Keychain.isLocked(account) || Keychain.isLocked("account_sessions")
    }



    @discardableResult
    static func clear() -> Bool { Keychain.clear(account) }

    static var sharedSession: SharedSessionCredential? {
        SharedSessionCredential.decode(Keychain.getSynchronizable(sharedAccount))
    }

    @discardableResult
    static func saveSharedSession(_ credential: SharedSessionCredential) -> Bool {
        guard let encoded = credential.encoded() else { return false }
        guard Keychain.putSynchronizable(sharedAccount, encoded) else { return false }


        return Keychain.getSynchronizable(sharedAccount) == encoded
    }

    @discardableResult
    static func clearSharedSession() -> Bool {
        Keychain.clearSynchronizable(sharedAccount)
    }
}









enum SecretStoreWiring {
    static func install() {
        SecretStoreBridge.shared.setImpl(value: Impl())
    }

    private final class Impl: SecretStoreBridgeImpl {
        func get(key: String) -> String? { Keychain.get(key) }

        func put(key: String, value: String?) { Keychain.put(key, value) }
        func read(key: String) -> SecretRead {
            let result = Keychain.read(key)
            return SecretRead(value: result.value, available: result.available)
        }
        func write(key: String, value: String?) -> Bool { Keychain.put(key, value) }
    }
}
