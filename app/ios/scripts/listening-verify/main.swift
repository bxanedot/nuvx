import Foundation
import NuvxShared



enum ArtistFacts {
    static var genresEnabled = false
}















var failures = 0

func check(_ label: String, _ condition: Bool) {
    if condition {
        print("  ok   \(label)")
    } else {
        print("  FAIL \(label)")
        failures += 1
    }
}




func play(_ store: ListeningStore, id: String = "vid1", title: String = "Yellow",
          on day: Date) {
    for step in 0..<10 {
        store.onSample(
            id: id, title: title, artist: "Coldplay", album: "Parachutes",
            albumId: "album", artistId: "artist", art: nil, duration: 60,
            at: day.addingTimeInterval(Double(step) * 4)
        )
    }
    store.onStopped()
}

func day(_ year: Int, _ month: Int, _ dayOfMonth: Int, hour: Int = 20) -> Date {
    var components = DateComponents()
    components.year = year
    components.month = month
    components.day = dayOfMonth
    components.hour = hour
    return Calendar.current.date(from: components)!
}

func near(_ a: Double, _ b: Double, _ tolerance: Double = 1) -> Bool {
    abs(a - b) <= tolerance
}

ListeningStore.persistenceDisabled = true




func withStore(_ body: (ListeningStore) -> Void) {
    let folder = FileManager.default.temporaryDirectory
        .appendingPathComponent("nuvx-listening-\(UUID().uuidString)", isDirectory: true)
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    ListeningStore.folderOverride = folder
    defer { try? FileManager.default.removeItem(at: folder) }
    body(ListeningStore())
}

print("listening store")

withStore { _ in
    let folder = ListeningStore.folderOverride!
    var track = ListeningStore.Track(id: "cached", title: "Older title", artist: "Artist", album: nil,
        albumId: nil, artistId: nil, art: "https://art.invalid/old", ms: 1, plays: 1, last: 100)
    let older = ListeningStore.StoredBucket(month: "2026-01", tracks: [track])
    track.title = "Current title"; track.art = "https://art.invalid/current"; track.last = 200
    let newer = ListeningStore.StoredBucket(month: "2026-02", tracks: [track])
    let first = folder.appendingPathComponent("2026-01.json"), second = folder.appendingPathComponent("2026-02.json")
    let firstBytes = try! JSONEncoder().encode(older), secondBytes = try! JSONEncoder().encode(newer)
    try! firstBytes.write(to: first); try! secondBytes.write(to: second)
    let snapshot = ListeningStore.savedTrackMetadata()
    check("offline artwork migration reads the latest saved track metadata", snapshot["cached"]?.title == track.title && snapshot["cached"]?.art == track.art)
    check("background metadata snapshot leaves listening files unchanged", (try! Data(contentsOf: first)) == firstBytes && (try! Data(contentsOf: second)) == secondBytes)
}



let thursday = day(2026, 4, 2)
let monday = day(2026, 3, 30)
let tuesday = day(2026, 3, 31)
let previousWeek = day(2026, 3, 20)

withStore { store in
    play(store, on: monday)
    play(store, on: tuesday)

    let week = store.summary(period: .thisWeek, now: thursday)



    check("two days are 72 seconds, not three days' worth", near(week.totalMs, 72_000))
    check("two plays", week.totalPlays == 2)
    check("the song has both days", near(week.songs.first?.ms ?? 0, 72_000))
    check("the song has both plays", week.songs.first?.plays == 2)
    check("one song, not one per day", week.distinctSongs == 1)
    check("an artist from both days", near(week.artists.first?.ms ?? 0, 72_000))
}



withStore { store in
    play(store, on: tuesday)
    play(store, on: thursday)
    let week = store.summary(period: .thisWeek, now: thursday)
    check("a week crossing months counts both sides", near(week.totalMs, 72_000))
    check("and both plays", week.totalPlays == 2)
}


withStore { store in
    play(store, on: previousWeek)
    play(store, on: monday)
    let week = store.summary(period: .thisWeek, now: thursday)
    let month = store.summary(period: .thisMonth, now: thursday)
    let year = store.summary(period: .thisYear, now: thursday)
    check("the week leaves out last week", near(week.totalMs, 36_000))
    check("the month is April only", near(month.totalMs, 0))
    check("the year has everything", near(year.totalMs, 72_000))
}


withStore { store in
    play(store, on: monday)
    play(store, on: monday.addingTimeInterval(3600))
    let week = store.summary(period: .thisWeek, now: thursday)
    check("two sessions in one day are 72 seconds", near(week.totalMs, 72_000))
}



withStore { store in
    play(store, on: previousWeek.addingTimeInterval(20 * 3600))
    play(store, on: tuesday.addingTimeInterval(9 * 3600))
    let week = store.summary(period: .thisWeek, now: thursday)
    let hour = Calendar.current.component(.hour, from: tuesday.addingTimeInterval(9 * 3600))
    check("the week's hours are its own", near(week.hourOfDay.reduce(0, +), 36_000))
    check("and peak where it was played", week.hourOfDay.indices.max(by: { week.hourOfDay[$0] < week.hourOfDay[$1] }) == hour)
}

print("")
if failures == 0 {
    print("all listening checks passed")
} else {
    print("\(failures) listening checks failed")
    exit(1)
}
