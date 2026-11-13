import Foundation
import NuvxShared
import os
#if os(iOS)
import UIKit
#if DEBUG
import AVFoundation
#endif
#else
import AppKit
#endif


extension PlaybackController {

    func toggleLike() {
        guard let vid = current?.videoId else { return }
        toggleLike(videoId: vid)
    }











    func toggleLike(videoId: String) {
        Task { @MainActor [weak self] in
            if let failure = await self?.toggleLikeAndWait(videoId: videoId) {
                self?.lastError = failure
            }
        }
    }

    func toggleLikeAndWait(videoId: String) async -> String? {
        let generation = PageSession.generation()
        let failure = await LibraryActions.toggleLike(videoId: videoId)
        refreshAfterRating(failure: failure, sessionGeneration: generation)
        return failure
    }

    func rateTrack(videoId: String, status: String) async -> String? {
        let generation = PageSession.generation()
        let previous = LibraryActions.cachedLike(videoId)
        let failure = await LibraryActions.rate(videoId: videoId, status: status)
        if generation == PageSession.generation() {
            if failure != nil { LikeStore.shared.set(videoId, previous) }


            if failure == nil, status == "DISLIKE" {
                let before = queue.count
                queue = queue.enumerated().filter {
                    $0.offset <= playingIndex || !$0.element.fromAutoplay || youtubeVideoID(for: $0.element) != videoId
                }.map(\.element)
                if queue.count != before { persistSession(); syncEngineQueueNext() }
            }
            refreshAfterRating(failure: failure, sessionGeneration: generation)
        }
        return failure
    }

    func refreshAfterRating(failure: String?, sessionGeneration: Int64) {
        guard failure == nil, sessionGeneration == PageSession.generation() else { return }
        maybeAutoplay(force: true)
    }

    var isLiked: Bool {
        _ = LikeStore.shared.epoch
        guard let vid = current?.videoId else { return false }
        return LibraryActions.cachedLike(vid) == "LIKE"
    }























    func livePosition(at now: Date) -> Double {
        guard state == .playing else { return position }
        let elapsed = now.timeIntervalSince(positionSampledAt)
        guard elapsed > 0 else { return position }




        guard elapsed < 0.4 else { return playbackPosition }
        return position + elapsed * playbackRate
    }

    func youtubeVideoID(for entry: QueueEntry) -> String? {
        if entry.source.hasPrefix("yt:") { return entry.videoId }
        return entry.isLocal ? DownloadStore.shared.selectedYouTubeVideoId(for: entry.source) : nil
    }

    var recommendationContext: AutoplayRefreshState.Context? {
        guard autoplayEnabled, repeatMode != .all, let current,
              let id = youtubeVideoID(for: current), state != .stopped else { return nil }
        return .init(
            source: "yt:\(id)", index: playingIndex, playbackGeneration: playGeneration,
            queueEditRevision: queueEditRevision, sessionGeneration: PageSession.generation()
        )
    }
}
