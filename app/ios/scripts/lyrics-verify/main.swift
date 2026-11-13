import Foundation
import NuvxShared

















struct Track {
    let title: String
    let artist: String
    let album: String?
    let seconds: Int
    var ms: Int64 { Int64(seconds) * 1000 }





    var videoId: String?
}


func searchVideoId(_ track: Track) async -> String? {
    do {
        let json = try await withCheckedThrowingContinuation { c in
            let callback = LyricsVerifySearch { json, _, _ in
                if let json { c.resume(returning: json) } else { c.resume(throwing: HarnessError.noValue) }
            }
            SearchBridge.shared.search(
                query: "\(track.artist) \(track.title) lyrics", scope: "songs", callback: callback
            )
        }
        guard let data = json.data(using: .utf8),


              let hits = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return nil }


        for hit in hits {
            guard let kind = hit["kind"] as? String, kind != "browse",
                  let videoId = hit["videoId"] as? String, !videoId.isEmpty
            else { continue }
            return videoId
        }
        return nil
    } catch {
        return nil
    }
}

private enum HarnessError: Error { case noValue }

private final class LyricsVerifySearch: SearchBridgeSearchCallback {
    private let handler: (String?, String?, String?) -> Void
    init(_ handler: @escaping (String?, String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, continuation: String?, message: String?) {
        handler(json, continuation, message)
    }
}

private final class LyricsVerifyResult: LyricsBridgeAttributedLyricsCallback {
    private let handler: ([LyricLineDto]) -> Void
    init(_ handler: @escaping ([LyricLineDto]) -> Void) { self.handler = handler }
    func onResult(source: String, sourceLabel: String, lines: [LyricLineDto]) { handler(lines) }
}

private final class LyricsVerifySourceState: LyricsBridgeSourceStateCallback {
    private let handler: (String, LyricsBridge.LyricsProviderState) -> Void
    init(_ handler: @escaping (String, LyricsBridge.LyricsProviderState) -> Void) { self.handler = handler }
    func onSourceState(source: String, sourceLabel: String, state: LyricsBridge.LyricsProviderState, lines: [LyricLineDto]) {
        handler(source, state)
    }
}

private final class BlockedFlag {
    private let lock = NSLock()
    private var stored = false
    func mark() { lock.lock(); stored = true; lock.unlock() }
    var value: Bool { lock.lock(); defer { lock.unlock() }; return stored }
}



var tracks: [Track] = [
    Track(title: "Bohemian Rhapsody", artist: "Queen", album: "A Night at the Opera", seconds: 355, videoId: nil),
    Track(title: "Blinding Lights", artist: "The Weeknd", album: "After Hours", seconds: 200, videoId: nil),
    Track(title: "Kal Ho Naa Ho", artist: "Shahrukh Khan", album: "Kal Ho Naa Ho", seconds: 296, videoId: nil),
    Track(title: "Lemon", artist: "Kenshi Yonezu", album: "BOOTLEG", seconds: 256, videoId: nil),
    Track(title: "起风了", artist: "买辣椒也用券", album: "起风了", seconds: 325, videoId: nil),
]





for index in tracks.indices {
    tracks[index].videoId = await searchVideoId(tracks[index])
}
let withVideo = tracks.filter { $0.videoId != nil }.count
print("video ids found for \(withVideo) of \(tracks.count) tracks")

let storedSources = (AppSettings.shared.lyricsSources.value as? String) ?? ""
let storedSynced = (AppSettings.shared.syncedLyrics.value as? Bool) ?? false

func restore() {
    AppSettings.shared.setLyricsSources(value: storedSources)
    AppSettings.shared.setSyncedLyrics(value: storedSynced)
}

func lyrics(for track: Track) async -> (lines: [LyricLineDto], blocked: Bool) {
    await withCheckedContinuation { continuation in
        let blocked = BlockedFlag()
        let result = LyricsVerifyResult { lines in
            continuation.resume(returning: (lines, blocked.value))
        }
        let state = LyricsVerifySourceState { source, state in
            guard source == "SIMP_MUSIC" else { return }
            if case .blocked = state { blocked.mark() }
        }
        LyricsBridge.shared.fetchWithSourceStates(
            title: track.title, artist: track.artist, durationMs: track.ms,
            album: track.album, videoId: track.videoId, localPath: nil,
            callback: result, onSourceState: state
        )
    }
}

let began = Date()
var failures: [String] = []
var answered: [String] = []
var dead: [String] = []
var unavailableSources: [String] = []
var thin_: [String] = []
var blockedSources: [String] = []



let sources: [(name: String, label: String, declaredWordSynced: Bool)] =
    LyricsSource.entries.map { ($0.name, $0.label, $0.wordSynced) }


let needsKey: Set<String> = ["PAXSENIX", "PAXSENIX_SPOTIFY", "PAXSENIX_MUSIXMATCH"]













let goneUpstream: [String: String] = [
    "MUSIXMATCH": "401 upgrade \u{2014} the web app id gets no token",
    "BETTER_LYRICS_PORTATO": "404 \u{2014} the endpoint is gone",
]



let unavailableUpstream: [String: String] = [
    "MEGALOBIZ": "404/503 \u{2014} the search page it scrapes is not serving",
]









let thinCoverage: [String: String] = [
    "UNISON": "200 with success:false \u{2014} the database has not heard of them",
    "YOUTUBE_TRANSCRIPT": "no cues \u{2014} these videos have no transcript in YouTube Music",
]

print("sources: \(sources.count)   tracks: \(tracks.count)   api keys in this build: none")
print("")

for source in sources {
    AppSettings.shared.setLyricsSources(value: source.name)
    var hits = 0
    var best = 0
    var wordSynced = false
    var slowest = 0.0
    var providerBlocked = false
    for track in tracks {
        let started = Date()
        let result = await lyrics(for: track)
        let lines = result.lines
        providerBlocked = providerBlocked || result.blocked
        slowest = max(slowest, Date().timeIntervalSince(started))
        guard !lines.isEmpty else { continue }
        hits += 1
        best = max(best, lines.count)
        if lines.contains(where: { !$0.words.isEmpty }) { wordSynced = true }
    }
    let verdict = providerBlocked
        ? "BLOCKED"
        : (hits > 0
            ? "\(hits)/\(tracks.count)"
            : (unavailableUpstream[source.name] != nil ? "UNAVAILABLE" : "nothing"))
    let note = needsKey.contains(source.name)
        ? " \u{00B7} needs a key"
        : (unavailableUpstream[source.name] ?? goneUpstream[source.name] ?? thinCoverage[source.name]).map { " \u{00B7} \($0)" } ?? ""
    print(pad(source.label, 22) + pad(source.name, 24) + pad(verdict, 12)
        + pad("\(best) lines", 11) + pad(String(format: "%.1fs", slowest), 9)
        + (wordSynced ? "word-synced" : "line-synced") + note)
    if providerBlocked {
        blockedSources.append("\(source.name): provider blocks third-party apps")
    } else if hits > 0 {
        answered.append(source.label)
    } else if let unavailable = unavailableUpstream[source.name] {
        unavailableSources.append("\(source.name): \(unavailable)")
    } else if let gone = goneUpstream[source.name] {
        dead.append("\(source.name): \(gone)")
    } else if let thin = thinCoverage[source.name] {
        thin_.append("\(source.name): \(thin)")
    } else if !needsKey.contains(source.name) {
        failures.append("\(source.name) (\(source.label)) answered for none of \(tracks.count) tracks")
    }
}

restore()

print("")
print("answered: \(answered.count) of \(sources.count)   in \(Int(Date().timeIntervalSince(began)))s")
if !dead.isEmpty {
    print("")
    print("dead upstream, checked against the service itself:")
    dead.forEach { print("  \u{00B7} \($0)") }
}
if !unavailableSources.isEmpty {
    print("")
    print("unavailable upstream:")
    unavailableSources.forEach { print("  \u{00B7} \($0)") }
}
if !thin_.isEmpty {
    print("")
    print("alive, and nothing for these tracks:")
    thin_.forEach { print("  \u{00B7} \($0)") }
}
if !blockedSources.isEmpty {
    print("")
    print("provider access blocked:")
    blockedSources.forEach { print("  · \($0)") }
}
if failures.isEmpty {
    print("")
    print("PASS  no unexplained empty provider; blocked, key-required, thin, and unavailable sources are reported separately")
} else {
    print("FAIL  \(failures.count) sources answered for nothing:")
    failures.forEach { print("  \u{00B7} \($0)") }
    exit(1)
}



func pad(_ text: String, _ width: Int) -> String {
    let count = text.count
    return count >= width ? text + " " : text + String(repeating: " ", count: width - count)
}
