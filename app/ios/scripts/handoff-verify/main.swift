import Foundation








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

print("handoff: what one device hands the other")

let payload = PlaybackHandoffPayload(
    videoId: "dQw4w9WgXcQ",
    title: "A Song",
    artist: "An Artist",
    positionMs: 90_500,
    isPlaying: true
)
let info = payload.userInfo





check("the payload rides in a single userInfo key", info.count == 1 && info["payload"] is String)
check(
    "the userInfo is a valid property list",
    PropertyListSerialization.propertyList(info, isValidFor: .binary)
)



check("it round-trips through userInfo", PlaybackHandoffPayload(userInfo: info) == payload)
check("the title and artist survive", PlaybackHandoffPayload(userInfo: info)?.title == "A Song"
      && PlaybackHandoffPayload(userInfo: info)?.artist == "An Artist")
check("the position converts from milliseconds", payload.positionSeconds == 90.5)



let paused = PlaybackHandoffPayload(videoId: "abc123", title: "T", artist: "A", positionMs: 0, isPlaying: false)
check("a paused payload keeps its flag", PlaybackHandoffPayload(userInfo: paused.userInfo) == paused)



let rewound = PlaybackHandoffPayload(videoId: "abc123", title: "T", artist: "A", positionMs: -4_000, isPlaying: true)
check("a negative position is clamped", rewound.positionMs == 0 && rewound.positionSeconds == 0)




check("an absent userInfo is not a payload", PlaybackHandoffPayload(userInfo: nil) == nil)
check("an empty userInfo is not a payload", PlaybackHandoffPayload(userInfo: [:]) == nil)


check(
    "another activity's userInfo is not a payload",
    PlaybackHandoffPayload(userInfo: ["foo": "bar"]) == nil
)


check(
    "a corrupted payload is refused",
    PlaybackHandoffPayload(userInfo: ["payload": "not json"]) == nil
)
check(
    "a truncated payload is refused",
    PlaybackHandoffPayload(userInfo: ["payload": #"{"videoId":"abc""#]) == nil
)


let json = (info["payload"] as? String) ?? ""
let future = json.replacingOccurrences(of: "\"version\":1", with: "\"version\":2")
check("a payload from a newer schema is ignored", PlaybackHandoffPayload(userInfo: ["payload": future]) == nil,
      "version 2")



let noTrack = #"{"version":1,"videoId":"","title":"T","artist":"A","positionMs":0,"isPlaying":false}"#
check("a payload with no track id is refused", PlaybackHandoffPayload(userInfo: ["payload": noTrack]) == nil)



let missingField = #"{"version":1,"title":"T","artist":"A","positionMs":0,"isPlaying":false}"#
check("a payload missing its track id is refused",
      PlaybackHandoffPayload(userInfo: ["payload": missingField]) == nil)

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
