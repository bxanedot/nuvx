import Foundation
import NuvxShared


















let ids = ProcessInfo.processInfo.environment["VIDEO_IDS"]?
    .split(separator: ",").map(String.init) ?? [

        "JGwWNGJdvx8",


        "xTvyyoF_LZY",
    ]

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

func describe(_ side: ResolvedVideoSide) -> String {
    let picture = side.width.flatMap { w in side.height.map { h in "\(w)×\(h)" } } ?? "no picture size"
    let aspect = side.aspectRatio.map { String(format: "%.2f", $0) } ?? "?"
    let itag = side.itag.map(String.init) ?? "?"
    let codecs = side.codecs ?? "?"
    let container = side.mimeType ?? "?"
    let bitrate = side.bitrate.map { "\($0 / 1000)kbps" } ?? "?"
    let heights = side.availableVideoHeights.map(String.init).joined(separator: ",")
    let n = side.hasNParameter ? "n=YES" : "n=no"
    let type = side.musicVideoType ?? "type=?"
    return "\(side.hasVideoURL ? "url present" : "no AVC/MP4 side") · \(picture) aspect \(aspect) · itag \(itag) · "
        + "\(container) \(codecs) \(bitrate) · heights [\(heights)] · \(n) · \(side.clientName)/\(side.profileId) · \(type)"
}



func servedWindow(_ url: URL) async -> (status: Int?, type: String?, bytes: Int) {
    var request = URLRequest(url: url)
    request.setValue("bytes=0-65535", forHTTPHeaderField: "Range")
    request.timeoutInterval = 30
    do {
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        return (http?.statusCode, http?.value(forHTTPHeaderField: "Content-Type"), data.count)
    } catch {
        return (nil, String(describing: error), 0)
    }
}

print("video side: a real music video and an art track, AVC/MP4 only")

for (index, id) in ids.enumerated() {
    let expectsMusicVideo = index == 0
    let label = expectsMusicVideo ? "a real music video (\(id))" : "an art track (\(id))"
    print("  \(label)")
    do {
        let began = Date()
        let side = try await InnertubeStreamResolver.shared.resolveVideo(videoId: id)
        let took = String(format: "%.1fs", Date().timeIntervalSince(began))
        print("    \(describe(side)) — \(took)")

        if expectsMusicVideo {
            check("the music video yields a ready AVC/MP4 video side",
                  side.hasVideoURL && !side.hasNParameter && side.isAVCInMP4,
                  side.hasVideoURL ? (side.isAVCInMP4 ? "AVC/MP4" : "not AVC/MP4") : "no AVC/MP4 side")
            check("its picture is a music video, not an art track",
                  side.looksLikeMusicVideo,
                  side.aspectRatio.map { "aspect \(String(format: "%.2f", $0))" } ?? "no width/height")
            if let string = side.url, let url = URL(string: string) {
                let window = await servedWindow(url)
                check("the rendition is served",
                      (window.status == 206 || window.status == 200) && window.bytes > 0
                        && (window.type ?? "").lowercased().contains("video/mp4"),
                      "\(window.status.map(String.init) ?? "no status") · \(window.type ?? "no type") · \(window.bytes)B")
            } else {
                check("the rendition is served", false, "no URL to read")
            }
        } else {


            let qualifies = side.hasVideoURL && side.looksLikeMusicVideo
            check("the art track does not qualify as a music video", !qualifies,
                  qualifies ? "aspect \(String(format: "%.2f", side.aspectRatio ?? 0))" :
                    (side.hasVideoURL ? "aspect \(String(format: "%.2f", side.aspectRatio ?? 0))" : "no video side"))
        }
    } catch {


        if expectsMusicVideo {
            check("the music video yields a ready AVC/MP4 video side", false, error.localizedDescription)
        } else {
            check("the art track does not qualify as a music video", true,
                  "no video side: \(error.localizedDescription)")
        }
    }
}

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
