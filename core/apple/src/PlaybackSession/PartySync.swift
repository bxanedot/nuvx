import Foundation
import NuvxShared













































@MainActor
final class PartySync {




    private static let tickMs: UInt64 = 700






    private static let intentQuietMs: Int64 = 4_500

    private let controller: PlaybackController
    private let judge = PartyDriftJudge()



    private var loadedVideoId: String?


    private var localIntentAtMs: Int64?

    private var task: Task<Void, Never>?

    private(set) var isActive = false








    var partyPositionMs: Int64? {
        let playback = PartyCoordinator.shared.session.current.playback
        guard PartyCoordinator.shared.session.current.clockSynced else { return nil }
        return PartyCoordinator.shared.session.correctedPosition(
            playback: playback,
            localNowMs: PartySocket.localNowMs()
        )
    }

    init(controller: PlaybackController) {
        self.controller = controller
    }








    func start() {
        guard !isActive else { return }
        isActive = true
        judge.reset()
        loadedVideoId = nil
        localIntentAtMs = nil
        reconcile()
        task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.tickMs * 1_000_000)
                guard let self else { return }
                self.reconcile()
            }
        }
    }

    func stop() {
        guard isActive else { return }
        isActive = false
        task?.cancel()
        task = nil
        judge.reset()
        loadedVideoId = nil
        localIntentAtMs = nil
    }



    func syncWithMembership() {
        if PartyCoordinator.shared.membership != nil { start() } else { stop() }
    }








    func onLocalIntent() {
        localIntentAtMs = PartySocket.localNowMs()
    }



    private func reconcile() {
        let state = PartyCoordinator.shared.session.current
        guard state.inParty else {


            stop()
            return
        }
        let playback = state.playback


        if let track = playback.track, track.videoId != loadedVideoId {

            controller.withLocalIntentSuppressed { load(track) }
            return
        }


        if playback.track == nil, loadedVideoId != nil {
            controller.pauseForBackground()
            loadedVideoId = nil
            return
        }

        let now = PartySocket.localNowMs()



        let partyPosition = partyPositionMs ?? playback.positionMs
        let decision = judge.onTick(
            partyPositionMs: partyPosition,
            localPositionMs: Int64(controller.position * 1000),
            localIsPlaying: controller.isPlaying,
            partyIsPlaying: playback.isPlaying,
            nowMs: now
        )







        if let seek = decision as? PartyDriftJudgeDecisionSeek {
            controller.withLocalIntentSuppressed {
                controller.seek(to: Double(seek.seekToMs) / 1000)
            }
        } else if decision is PartyDriftJudgeDecisionPause, controller.isPlaying {
            controller.withLocalIntentSuppressed {
                controller.togglePlayPause()
            }
        }



        if state.canControl, !isQuiet(nowMs: now) {
            publish(positionMs: Int64(controller.position * 1000), isPlaying: controller.isPlaying)
        }
    }


    private func isQuiet(nowMs: Int64) -> Bool {
        guard let at = localIntentAtMs else { return false }
        if nowMs - at > Self.intentQuietMs {
            localIntentAtMs = nil
            return false
        }
        return true
    }



    private func load(_ track: PartyTrack) {



        let entry = QueueEntry(
            id: track.videoId,
            title: track.title,
            artist: track.artist,
            source: "yt:" + track.videoId,
            thumbnailUrl: track.thumbnailUrl,
            durationText: nil,
            albumName: nil,
            artworkData: nil,
            isLocal: false,
            fromAutoplay: track.fromAutoplay
        )
        loadedVideoId = track.videoId
        controller.play([entry], at: 0)
    }

    private var lastPublishedMs: Int64 = -1

    private func publish(positionMs: Int64, isPlaying: Bool) {



        if lastPublishedMs >= 0, abs(positionMs - lastPublishedMs) < 1_000, isPlaying {
            return
        }
        lastPublishedMs = positionMs
        PartyCoordinator.shared.report(positionMs: positionMs, isPlaying: isPlaying)
    }
}
