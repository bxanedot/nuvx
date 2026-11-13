import Foundation





struct VideoSurfaceState: Equatable {
    private(set) var enabled = false
    private(set) var surfaces = 0
    private(set) var active = true
    private(set) var pictureInPictureActive = false
    private(set) var trackId: String?


    var canRequest: Bool {
        enabled && (surfaces > 0 || pictureInPictureActive) &&
            (active || pictureInPictureActive) && !(trackId ?? "").isEmpty
    }

    mutating func setEnabled(_ on: Bool) {
        enabled = on
    }


    mutating func setActive(_ on: Bool) {
        active = on
    }

    mutating func setPictureInPictureActive(_ active: Bool) {
        pictureInPictureActive = active
    }

    mutating func setTrack(_ id: String?) {
        trackId = id
    }

    mutating func surfaceAppeared(videoId: String?) {
        surfaces += 1


        trackId = videoId
    }

    mutating func surfaceDisappeared() {
        surfaces = max(0, surfaces - 1)


    }
}
