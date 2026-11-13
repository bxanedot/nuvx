import SwiftUI
import NuvxShared























enum EmbeddedArtwork {







    static func trackId(of entry: QueueEntry) -> String? {
        guard entry.thumbnailUrl == nil, entry.artworkData == nil else { return nil }
        return WebDavBridge.shared.isRemoteId(videoId: entry.id) ? entry.id : nil
    }






    @discardableResult
    static func embeddedCoverData(id: String) async -> Data? {
        guard WebDavBridge.shared.isRemoteId(videoId: id),
              let fileUrl = WebDavBridge.shared.fileUrlOf(videoId: id)
        else { return nil }
        return await withCheckedContinuation { continuation in
            WebDavBridge.shared.embeddedCover(fileUrl: fileUrl) { image, error in



                guard error == nil, let image else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: image.bytes as Data)
            }
        }
    }
}
