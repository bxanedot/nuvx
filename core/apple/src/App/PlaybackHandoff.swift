import Foundation













@MainActor
final class PlaybackHandoff: NSObject, NSUserActivityDelegate {
    static let shared = PlaybackHandoff()




    private weak var controller: PlaybackController?
    private var activity: NSUserActivity?

    private override init() {
        super.init()
    }



    func refresh(controller: PlaybackController) {
        self.controller = controller
        guard let payload = payload(from: controller) else {
            withdraw()
            return
        }




        let activity = self.activity ?? NSUserActivity(activityType: PlaybackHandoffPayload.activityType)
        if activity.delegate !== self {
            activity.delegate = self
        }
        activity.title = payload.title
        activity.isEligibleForHandoff = true
        activity.userInfo = payload.userInfo



        activity.needsSave = payload.isPlaying
        activity.becomeCurrent()
        self.activity = activity
    }



    func withdraw() {
        activity?.resignCurrent()
        activity = nil
    }








    nonisolated func userActivityWillSave(_ userActivity: NSUserActivity) {
        if Thread.isMainThread {
            MainActor.assumeIsolated { writePayload(to: userActivity) }
        } else {



            DispatchQueue.main.sync {
                MainActor.assumeIsolated { writePayload(to: userActivity) }
            }
        }
    }


    private func writePayload(to userActivity: NSUserActivity) {
        guard userActivity === activity,
              let controller,
              let payload = payload(from: controller) else { return }
        userActivity.userInfo = payload.userInfo
        userActivity.title = payload.title
    }









    func continuePlayback(payload: PlaybackHandoffPayload, controller: PlaybackController) {
        let entry = QueueEntry.youtube(
            videoId: payload.videoId,
            title: payload.title,
            artist: payload.artist
        )
        controller.play([entry], at: 0, context: "Handoff")
        if !payload.isPlaying {


            controller.togglePlayPause()
        }
        guard payload.positionMs > 0 else { return }
        Task { @MainActor in
            let deadline = Date().addingTimeInterval(15)
            while Date() < deadline {
                guard controller.current?.id == entry.id else { return }
                if controller.duration > 1 {
                    controller.seek(to: payload.positionSeconds)
                    return
                }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }



    private func payload(from controller: PlaybackController) -> PlaybackHandoffPayload? {
        guard let current = controller.current,
              let videoId = current.videoId,
              !videoId.isEmpty else { return nil }
        return PlaybackHandoffPayload(
            videoId: videoId,
            title: current.title,
            artist: current.artist,
            positionMs: Int((controller.position * 1000).rounded()),
            isPlaying: controller.isPlaying
        )
    }
}
