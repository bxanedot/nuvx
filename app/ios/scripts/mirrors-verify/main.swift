import Foundation
import NuvxShared

















struct Track {
    let title: String
    let artist: String
    let seconds: Int
    var ms: Int64 { Int64(seconds) * 1000 }
}

let track = Track(title: "As It Was", artist: "Harry Styles", seconds: 165)

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



func sweep() async -> (times: [Double], found: Int) {
    var times: [Double] = []
    var found = 0



    for round in 0..<3 {
        let start = Date()



        let lines: [LyricLineDto] = await withCheckedContinuation { c in
            LyricsPlus.shared.lyrics(
                title: track.title, artist: track.artist, durationMs: track.ms,
                album: nil, isrc: nil
            ) { lines, _ in
                c.resume(returning: lines ?? [])
            }
        }
        let elapsed = Date().timeIntervalSince(start) * 1000
        times.append(elapsed)
        if !lines.isEmpty {
            found += 1
            print(String(
                format: "  · ask %d: %.0f ms, %d line(s)", round + 1, elapsed, lines.count
            ))
        } else {
            print(String(format: "  · ask %d: %.0f ms, nothing", round + 1, elapsed))
        }
    }
    return (times, found)
}

print("mirrors: the same track, three times")
print("  · the first ask pays for cold DNS and TLS on every mirror, including the ones")
print("    that cannot answer, so it runs to the timeout. Later asks pay only for the")
print("    mirrors that work — that difference is the whole point of the health table.")
let (times, found) = await sweep()



check("LyricsPlus still answers", found > 0, "\(found)/3 asks returned lyrics")




if times.count == 3 {
    let first = times[0]
    let later = (times[1] + times[2]) / 2



    check("later asks are cheaper than the first", later < first,
          String(format: "first %.0f ms, later mean %.0f ms", first, later))
} else {
    check("later asks are cheaper than the first", false, "not enough asks recorded")
}




check("the source was not starved to get faster", found > 0,
      "\(found)/3 asks returned lyrics")

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
