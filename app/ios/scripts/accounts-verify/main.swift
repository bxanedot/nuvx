import Foundation
import NuvxShared












var failures = 0
var checks = 0

func check(_ label: String, _ ok: Bool, _ detail: String = "") {
    checks += 1
    if ok {
        print("  ok   \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    } else {
        failures += 1
        print("  FAIL \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    }
}

func profile(
    _ id: String, _ name: String, pageId: String? = nil,
    dataSyncId: String? = nil, authUser: String? = nil
) -> YouTubeProfile {
    YouTubeProfile(
        profileId: id, name: name, handle: "@\(name.lowercased())",
        avatar: nil, pageId: pageId, dataSyncId: dataSyncId,
        authUser: authUser, isBrandAccount: pageId != nil
    )
}

func store() -> AccountStore { AccountStore.shared }
func innertube() -> Innertube { Innertube.shared }






let secrets = HarnessSecretStore()
SecretStoreBridge.shared.setImpl(value: secrets)




let savedCookie = innertube().cookie
func reset() {
    for account in store().accounts() { store().forget(accountId: account.accountId) }
    AuthBridge.shared.applyCookie(cookieHeader: nil)
    innertube().adoptPageScope(pageId: nil, dataSyncId: nil, authUser: nil)
}
reset()
defer {
    reset()
    AuthBridge.shared.applyCookie(cookieHeader: savedCookie)
}

print("accounts: the store, and the headers a selection produces")
if secrets.usingKeychain {
    print("  · credentials in the Keychain, as the app stores them")
} else {
    print("  · NOTE: this binary is not entitled for the data-protection keychain, so the")
    print("    store\'s logic is checked against a isolated in-memory substitute. That is a")
    print("    property of an unentitled harness, not of the app — see HarnessSecretStore.")
    check("a probe value survives the secret store", {
        secrets.put(key: "harness.roundtrip", value: "x")
        let back = secrets.get(key: "harness.roundtrip")
        secrets.put(key: "harness.roundtrip", value: nil)
        return back == "x"
    }())
}



check("a fresh store has no accounts", store().accounts().isEmpty)
check("a fresh store has no selection", store().activeSelection() == nil)
check("no selection leaves the shell's own identity", innertube().cookie == nil)




let cookieA = "SID=aaa; SAPISID=secret-a; __Secure-3PAPISID=secret-a"
store().record(
    accountId: "acct-1",
    cookie: cookieA,
    name: "Listener",
    email: "b@example.com",
    profiles: [
        profile("personal", "Listener", dataSyncId: "ds-personal"),
        profile("brand", "Acme Records", pageId: "UCbrand123", dataSyncId: "ds-brand", authUser: "1"),
    ]
)
check("the account is stored", store().accounts().count == 1)
check("both channels are stored", store().accounts().first?.profiles.count == 2)
check("recording applied the cookie to the request path",
      innertube().cookie == cookieA, "cookie set: \(innertube().cookie != nil)")



let first = store().activeSelection()
check("the first channel is selected by default", first?.profile.profileId == "personal",
      first?.profile.profileId ?? "none")


store().select(accountId: "acct-1", profileId: "brand")
let brand = store().activeSelection()
check("selecting the brand channel takes", brand?.profile.profileId == "brand",
      brand?.profile.profileId ?? "none")
check("the brand channel's pageId is what the override holds",
      brand?.profile.pageId == "UCbrand123", brand?.profile.pageId ?? "none")
check("the brand channel's authUser is what the override holds",
      brand?.profile.authUser == "1", brand?.profile.authUser ?? "none")
check("the cookie survived the switch", innertube().cookie == cookieA)





let blob = PlatformSettings.shared.getSecret(key: "account_sessions") ?? ""
check("the stored blob carries both cookies' account and the selection",
      blob.contains("acct-1") && blob.contains("brand") && blob.contains("personal"),
      "\(blob.count) bytes")
check("the stored blob holds the cookie, not a reference to it",
      blob.contains("SAPISID=secret-a"))
check("the store reads the blob back with the selection on the brand channel",
      store().accounts().first?.activeProfileId == "brand",
      store().accounts().first?.activeProfileId ?? "none")


check("stepping forward from the last one does nothing",
      !store().step(forward: true), "already on the last")
store().select(accountId: "acct-1", profileId: "personal")
check("stepping forward moves to the next", store().step(forward: true))
check("…and lands on the brand channel",
      store().activeSelection()?.profile.profileId == "brand")
check("stepping back returns", store().step(forward: false))
check("…to the personal channel",
      store().activeSelection()?.profile.profileId == "personal")
check("stepping back off the first does nothing", !store().step(forward: false))



let cookieB = "SID=bbb; SAPISID=secret-b; __Secure-3PAPISID=secret-b"
store().record(
    accountId: "acct-2", cookie: cookieB, name: "Second", email: "s@example.com",
    profiles: [profile("s2p", "Second")]
)
check("both accounts are stored", store().accounts().count == 2)
check("insertion order is kept", store().accounts().map(\.accountId) == ["acct-1", "acct-2"],
      store().accounts().map(\.accountId).joined(separator: ", "))
store().select(accountId: "acct-2", profileId: "s2p")
check("selecting the second account switches the cookie",
      innertube().cookie == cookieB)
check("stepping forward off the end of the last account does nothing",
      !store().step(forward: true))
store().select(accountId: "acct-2", profileId: "s2p")




check("stepping backward crosses to the previous account's last channel",
      store().step(forward: false)
          && store().activeSelection()?.profile.profileId == "brand",
      store().activeSelection()?.profile.profileId ?? "none")
check("…and the cookie followed the account back",
      innertube().cookie == cookieA,
      innertube().cookie == cookieA ? "the first account" : "the second account is still active")




store().select(accountId: "acct-1", profileId: "nonexistent")
check("selecting an unknown channel falls back to the account's first",
      store().activeSelection()?.profile.profileId == "personal",
      store().activeSelection()?.profile.profileId ?? "none")
store().select(accountId: "acct-1", profileId: "personal")
let plain = store().activeSelection()
check("a channel with no pageId and no dataSyncId is still selectable",
      plain?.profile.profileId == "personal")



let before = store().accounts().map(\.accountId)
store().forget(accountId: "")
check("forgetting an unknown account changes nothing",
      store().accounts().map(\.accountId) == before,
      store().accounts().map(\.accountId).joined(separator: ", "))


store().forget(accountId: "acct-2")
check("one account is left", store().accounts().count == 1)
store().forget(accountId: "acct-1")
check("forgetting the last account empties the store", store().accounts().isEmpty)
check("and clears the cookie from the request path", innertube().cookie == nil)
check("and leaves no selection", store().activeSelection() == nil)
check("and clears the stored blob", PlatformSettings.shared.getSecret(key: "account_sessions") == nil)

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)




















final class HarnessSecretStore: SecretStoreBridgeImpl {

    private var values: [String: String] = [:]
    var usingKeychain: Bool { false }
    func get(key: String) -> String? { values[key] }
    func put(key: String, value: String?) { values[key] = value }
    func read(key: String) -> SecretRead { SecretRead(value: values[key], available: true) }
    func write(key: String, value: String?) -> Bool { values[key] = value; return true }
}
