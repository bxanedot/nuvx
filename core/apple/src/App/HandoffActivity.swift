import Foundation









struct PlaybackHandoffPayload: Codable, Equatable {


    static let currentVersion = 1




    static let activityType = "com.bxane.nuvx.playback"


    static let userInfoKey = "payload"

    var version: Int
    var videoId: String
    var title: String
    var artist: String

    var positionMs: Int
    var isPlaying: Bool

    var positionSeconds: Double {
        Double(max(0, positionMs)) / 1000
    }

    init(videoId: String, title: String, artist: String, positionMs: Int, isPlaying: Bool) {
        self.version = Self.currentVersion
        self.videoId = videoId
        self.title = title
        self.artist = artist
        self.positionMs = max(0, positionMs)
        self.isPlaying = isPlaying
    }







    var userInfo: [AnyHashable: Any] {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else { return [:] }
        return [Self.userInfoKey: json]
    }






    init?(userInfo: [AnyHashable: Any]?) {
        guard let json = userInfo?[Self.userInfoKey] as? String,
              let data = json.data(using: .utf8),
              let payload = try? JSONDecoder().decode(Self.self, from: data),
              payload.version == Self.currentVersion,
              !payload.videoId.isEmpty
        else { return nil }
        self = payload
    }
}
