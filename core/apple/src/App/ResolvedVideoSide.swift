import Foundation



struct ResolvedVideoSide: Sendable {

    let url: String?
    let width: Int?
    let height: Int?
    let itag: Int?
    let mimeType: String?
    let codecs: String?
    let bitrate: Int?
    let headers: [String: String]
    let availableVideoHeights: [Int]


    let hasNParameter: Bool
    let clientName: String
    let profileId: String
    let musicVideoType: String?

    var hasVideoURL: Bool { !(url ?? "").isEmpty }
    var aspectRatio: Double? {
        guard let width, let height, height > 0 else { return nil }
        return Double(width) / Double(height)
    }


    var looksLikeMusicVideo: Bool { (aspectRatio ?? 0) >= 1.2 }




    var isStillImage: Bool {
        guard let kbpsPerMegapixel else { return false }
        return kbpsPerMegapixel < 700
    }



    var isTrulyStatic: Bool {
        guard let kbpsPerMegapixel else { return false }
        return kbpsPerMegapixel < 50
    }
    var kbpsPerMegapixel: Double? {
        guard let bitrate, let width, let height, width > 0, height > 0 else { return nil }
        return Double(bitrate) / 1000 / (Double(width * height) / 1_000_000)
    }
    var isAuthoritativeMusicVideo: Bool {
        musicVideoType == "MUSIC_VIDEO_TYPE_OMV"
    }
    func qualifiesAsCounterpartVideo() -> Bool {
        hasVideoURL && isAVCInMP4 && !isTrulyStatic
    }
    func qualifiesAsSearchVideo(isOfficialMusicVideo: Bool) -> Bool {
        let hasMovingPicture = (isOfficialMusicVideo || isAuthoritativeMusicVideo) ? !isTrulyStatic : !isStillImage
        return hasVideoURL && isAVCInMP4 && looksLikeMusicVideo && hasMovingPicture
    }

    var isAVCInMP4: Bool {
        let mime = mimeType?.lowercased() ?? ""
        return mime.contains("video/mp4") && !mime.contains("mp4a")
            && (codecs?.lowercased().contains("avc1") ?? false)
    }
}
