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


let expired = "Your YouTube Music session has expired. Sign in again."



let sharedFixture = SharedSessionCredential(
    schemaVersion: SharedSessionCredential.currentSchemaVersion,
    cookie: "fake-session-cookie",
    accountName: "Test Listener",
    email: "listener@example.invalid",
    avatarURL: nil,
    pageId: "fake-page",
    dataSyncId: "fake-channel",
    authUser: "0",
    deviceName: "Test iPad",
    createdAt: Date(timeIntervalSinceReferenceDate: 0)
)
let encodedSharedFixture = sharedFixture.encoded()
let decodedSharedFixture = SharedSessionCredential.decode(encodedSharedFixture)
check("shared session payload round-trips a synthetic credential",
      decodedSharedFixture == sharedFixture)
check("shared payload can be adopted as a validated capture",
      decodedSharedFixture?.signInCapture.cookie == "fake-session-cookie"
        && decodedSharedFixture?.signInCapture.loggedIn == true)
check("unsupported shared payload schema is rejected",
      SharedSessionCredential.decode("{\"schemaVersion\":99,\"cookie\":\"fake\"}") == nil)
check("empty shared credentials are not adoptable",
      !SharedSessionCredential(
        schemaVersion: SharedSessionCredential.currentSchemaVersion,
        cookie: "", accountName: "Test Listener", email: "listener@example.invalid",
        avatarURL: nil, pageId: nil, dataSyncId: nil, authUser: nil,
        deviceName: "Test iPad", createdAt: Date(timeIntervalSinceReferenceDate: 0)
      ).isAdoptable)


let searchError = InnertubeSearch.SearchError(message: expired)
check("SearchError.localizedDescription carries the message",
      searchError.localizedDescription == expired,
      searchError.localizedDescription)


check("the shared matcher accepts the store's refusal",
      AuthBridge.shared.isSessionExpired(message: searchError.localizedDescription))
check("the shared matcher accepts the raw text",
      AuthBridge.shared.isSessionExpired(message: expired))


let network = NSError(
    domain: "NuvxTests", code: -1009,
    userInfo: [NSLocalizedDescriptionKey: "Couldn't reach the music service. Check your connection and try again."]
)
check("a network failure does not match",
      !AuthBridge.shared.isSessionExpired(message: network.localizedDescription),
      network.localizedDescription)



struct PlainError: Error { let message: String }
let plain = PlainError(message: expired)
check("a plain Error would not carry the text (why LocalizedError matters)",
      plain.localizedDescription != expired,
      plain.localizedDescription)

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
