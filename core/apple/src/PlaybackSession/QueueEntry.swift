import Foundation



struct QueueEntry: Identifiable, Hashable, Sendable, Codable {
    let id: String
    var title: String
    var artist: String
    var source: String
    var thumbnailUrl: String?
    var durationText: String?
    var albumName: String?
    var artworkData: Data?









    var isLocal: Bool
    var fromAutoplay: Bool = false
    var artistId: String? = nil
    var albumId: String? = nil
    var setVideoId: String? = nil

    var contextOrder: Int? = nil








    var isExplicit: Bool? = nil






    var isVideo: Bool = false

    var videoId: String? {
        if source.hasPrefix("yt:") { return String(source.dropFirst(3)) }
        if isLocal { return nil }
        return id
    }



    var itemText: String {
        [title, artist, albumName ?? ""]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }



    func sameAlbum(as other: QueueEntry) -> Bool {
        if let a = albumId, let b = other.albumId, !a.isEmpty, a == b { return true }
        if let a = albumName, let b = other.albumName, !a.isEmpty, a == b, artist == other.artist {
            return true
        }
        return false
    }

    static func youtube(
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String? = nil,
        durationText: String? = nil,
        albumName: String? = nil,
        artistId: String? = nil,
        albumId: String? = nil,
        setVideoId: String? = nil,
        fromAutoplay: Bool = false
    ) -> QueueEntry {
        QueueEntry(
            id: videoId, title: title, artist: artist, source: "yt:\(videoId)",
            thumbnailUrl: thumbnailUrl, durationText: durationText, albumName: albumName,
            artworkData: nil, isLocal: false, fromAutoplay: fromAutoplay,
            artistId: artistId, albumId: albumId, setVideoId: setVideoId
        )
    }

    var durationSeconds: Double { Self.seconds(fromDurationText: durationText) ?? 0 }


    static func seconds(fromDurationText text: String?) -> Double? {
        let parts = (text ?? "").split(separator: ":").compactMap { Double($0) }
        switch parts.count {
        case 2: return parts[0] * 60 + parts[1]
        case 3: return parts[0] * 3600 + parts[1] * 60 + parts[2]
        default: return nil
        }
    }

    static func formatDuration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let mins = total / 60
        let secs = total % 60
        let hours = mins / 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, mins % 60, secs)
            : String(format: "%d:%02d", mins, secs)
    }

}
