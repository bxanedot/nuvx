#if os(iOS)
import ActivityKit



enum NowPlayingActivityController {
    static func endLegacyActivities() {
        Task {
            for activity in Activity<NowPlayingActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }
}
#endif
