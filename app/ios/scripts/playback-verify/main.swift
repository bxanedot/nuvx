import Foundation
import NuvxShared























let ids = ProcessInfo.processInfo.environment["VIDEO_IDS"]?
    .split(separator: ",").map(String.init) ?? [



        "fJ9rUzIMcZQ",
        "0Vj66wUOMoU",
        "kJQP7kiw5Fk",
    ]

CipherUnlockWiring.install(player: YouTubePlayerJs(solverDirectory: URL(fileURLWithPath: CommandLine.arguments[1])))

ApplePoTokenProvider.solverURL = URL(fileURLWithPath: CommandLine.arguments[1]).appendingPathComponent("po_token.html")
ApplePoTokenWiring.install()

if ProcessInfo.processInfo.environment["VERIFY_PO_TOKEN"] == "1" {
    do {
        guard let visitor = try await Innertube.shared.ensureVisitorData(refresh: false) else { throw PlaybackError.noValue("Missing visitor identity") }
        let minted = await withCheckedContinuation { continuation in
            ApplePoTokenProvider.shared.generate(videoId: ids[0], visitorData: visitor, callback: TokenVerification { player, streaming in
                continuation.resume(returning: !(player ?? "").isEmpty && !(streaming ?? "").isEmpty)
            })
        }
        print(minted ? "PASS upstream web playback verification minted both tokens" : "FAIL upstream web playback verification unavailable")
    } catch { print("FAIL web playback verification bootstrap") }
}

var failures: [String] = []
var served: [(String, String, Int, String)] = []
var unavailable: [String] = []

for (idIndex, id) in ids.enumerated() {
    let began = Date()
    do {
        let json = try await withCheckedThrowingContinuation { c in
            let callback = PlaybackResolve { json, message in
                if let json { c.resume(returning: json) }
                else { c.resume(throwing: PlaybackError.noValue(message)) }
            }
            PlayerBridge.shared.resolve(
                videoId: id, maxKbps: Int32(clamping: Int(Int32.max)), callback: callback
            )
        }
        guard let data = json.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let urlString = payload["url"] as? String, let url = URL(string: urlString)
        else {
            failures.append("\(id): resolved but the payload had no URL in it")
            continue
        }
        if ProcessInfo.processInfo.environment["VERIFY_DURATION"] == "1" {
            guard let seconds = payload["durationSeconds"] as? Int, seconds > 0 else {
                failures.append("\(id): playback payload lost the track duration")
                continue
            }
            print("Duration \(id): \(seconds)s")
        }
        let headers = payload["headers"] as? [String: String] ?? [:]
        let kbps = (payload["kbps"] as? Int) ?? 0
        let mime = (payload["mimeType"] as? String) ?? "?"



        var request = URLRequest(url: url)
        request.setValue("bytes=0-65535", forHTTPHeaderField: "Range")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let took = String(format: "%.1fs", Date().timeIntervalSince(began))
        do {
            let (bytes, response) = try await URLSession.shared.data(for: request)
            let http = response as? HTTPURLResponse
            let got = http?.value(forHTTPHeaderField: "Content-Type") ?? "-"
            let ok = (200...299).contains(http?.statusCode ?? 0) && bytes.count > 4096
            print("ok  \(id)  \(kbps)kbps  \(mime)  served \(http?.statusCode ?? 0) \(got)  \(bytes.count)B  \(took)")
            if !ok {
                failures.append("\(id): resolved, but a 64 KiB range came back \(http?.statusCode ?? 0) with \(bytes.count)B")
                continue
            }
            served.append((id, mime, kbps, got))
            if idIndex == 0 && ProcessInfo.processInfo.environment["REJECT_FIRST_PROFILE"] == "1" {
                PlayerBridge.shared.onPlaybackRefused(url: urlString, responseCode: 403)
                print("Injected a served-URL refusal to exercise fresh upstream recovery")
            }
        } catch {
            failures.append("\(id): resolved, but the range read failed — \(error.localizedDescription)")
            print("FAIL \(id)  \(kbps)kbps  \(mime)  range read failed: \(error.localizedDescription)")
        }
    } catch {
        let raw = "\(error)"





        let trackRefusal = raw.lowercased().contains("unavailable")
            || raw.lowercased().contains("not available")
        if trackRefusal && ProcessInfo.processInfo.environment["STRICT_PLAYBACK"] != "1" && !(ProcessInfo.processInfo.environment["REJECT_FIRST_PROFILE"] == "1" && idIndex > 0 && id == ids[0]) {
            print("skip  \(id)  not available to this network or account")
            unavailable.append(id)
        } else {
            failures.append("\(id): \(raw)")
            print("FAIL \(id)  \(raw)")
        }
    }
}





let gated = failures.filter {
    let said = $0.lowercased()
    return said.contains("sign in") || said.contains("not a bot")
}
let realFailures = failures.filter { !gated.contains($0) }

print("")
if !unavailable.isEmpty {
    print("unavailable here, which is an answer rather than a failure: \(unavailable.joined(separator: ", "))")
}
if !gated.isEmpty {
    print("gated by YouTube right now — every client answered with a sign-in or bot check:")
    gated.forEach { print("  \u{00B7} \($0)") }
}
if served.isEmpty {
    print("")
    print("FAIL  nothing resolved, and nothing refused a stream URL for a reason outside this app")
    realFailures.forEach { print("  \u{00B7} \($0)") }
    exit(1)
}
print("")
print("served \(served.count) of \(ids.count):")
for (id, mime, kbps, contentType) in served {
    print("  \u{00B7} \(id)  \(mime)  \(kbps)kbps  answered \(contentType)")
}
if realFailures.isEmpty {
    print("")
    print("PASS  a stream opened and served a real 64 KiB window; every refusal was a refusal of the track or of this network, not of the app")
} else {
    print("FAIL  \(realFailures.count) problems:")
    realFailures.forEach { print("  \u{00B7} \($0)") }
    exit(1)
}

private enum PlaybackError: Error {
    case noValue(String?)
    var description: String {
        switch self {
        case .noValue(let message): return message ?? "resolve returned neither a URL nor a reason"
        }
    }
}

private final class PlaybackResolve: PlayerBridgeResolveCallback {
    private let handler: (String?, String?) -> Void
    init(_ handler: @escaping (String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, message: String?) { handler(json, message) }
}

final class TokenVerification: NSObject, PlaybackTokenBridgeTokenCallback {
    let reply: (String?, String?) -> Void
    init(_ reply: @escaping (String?, String?) -> Void) { self.reply = reply }
    func onResult(playerToken: String?, streamingToken: String?) { reply(playerToken, streamingToken) }
}
