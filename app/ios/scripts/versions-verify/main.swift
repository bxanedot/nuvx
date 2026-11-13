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

let settings = PlatformSettings.shared
let store = "original_version_pins"
let real = "dQw4w9WgXcQ"
let other = "oHg5SJYRHA0"



let saved = settings.getString(key: store, default: "")
defer {
    settings.putString(key: store, value: saved)
    for id in [real, other] { OriginalVersion.shared.unpin(videoId: id) }
}
settings.putString(key: store, value: "")
OriginalVersion.shared.unpin(videoId: real)
OriginalVersion.shared.unpin(videoId: other)


check("a fresh id is not pinned",
      !OriginalVersion.shared.isPinned(videoId: real))


OriginalVersion.shared.pin(videoId: real)
check("a pinned id reads back as pinned",
      OriginalVersion.shared.isPinned(videoId: real))
check("pinning one id does not pin another",
      !OriginalVersion.shared.isPinned(videoId: other))





let written = settings.getString(key: store, default: "")
check("the pin reached the store", written == real, "stored: \(written.isEmpty ? "<empty>" : written)")



let ids = OriginalVersion.shared.pinnedIds()
check("the menu's pin list contains it", ids.contains(real), "\(ids.sorted())")



OriginalVersion.shared.pin(videoId: real)
let twice = settings.getString(key: store, default: "")
check("pinning twice stores one entry", twice == real, "stored: \(twice)")


OriginalVersion.shared.pin(videoId: other)
let both = settings.getString(key: store, default: "")
check("two pins store as two ordered lines",
      both == "\(real)\n\(other)" || both == "\(other)\n\(real)", "stored: \(both.replacingOccurrences(of: "\n", with: " | "))")




OriginalVersion.shared.pin(videoId: "")
check("an empty id does not become a pin",
      !OriginalVersion.shared.isPinned(videoId: ""),
      "pins: \(OriginalVersion.shared.pinnedIds().sorted())")




check("nil is not pinned", !OriginalVersion.shared.isPinned(videoId: nil))
check("nil did not become a pin named null",
      !OriginalVersion.shared.isPinned(videoId: "null"),
      "pins: \(OriginalVersion.shared.pinnedIds().sorted())")


OriginalVersion.shared.unpin(videoId: real)
check("an unpinned id reads back as not pinned",
      !OriginalVersion.shared.isPinned(videoId: real))
check("the unpinned id left the store",
      !settings.getString(key: store, default: "").contains(real),
      "stored: \(settings.getString(key: store, default: "").replacingOccurrences(of: "\n", with: " | "))")



OriginalVersion.shared.unpin(videoId: "9bZkp7q19f0")
check("unpinning an absent id leaves the others alone",
      OriginalVersion.shared.isPinned(videoId: other),
      "pins: \(OriginalVersion.shared.pinnedIds().sorted())")








OriginalVersion.shared.unpin(videoId: other)
settings.putString(key: store, value: "\r\n  \(real)  \n\n")
check("the store is read once, not per query",
      !OriginalVersion.shared.isPinned(videoId: real),
      "the live set is authoritative once loaded")

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
