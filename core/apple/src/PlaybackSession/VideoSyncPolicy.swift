import Foundation







enum VideoSyncPolicy {

    static let seekDriftSeconds: Double = 0.15

    static let ignoreDriftSeconds: Double = 0.02

    static let maxRateNudge: Float = 0.1

    static let seekCooldownSeconds: Double = 0.5


    static let nudgeGain: Double = 0.8

    enum Action: Equatable {
        case none

        case pause

        case play(rate: Float)

        case seek(seconds: Double)
    }









    static func decide(
        engineSeconds: Double,
        videoSeconds: Double,
        enginePlaying: Bool,
        engineRate: Double,
        videoRate: Float,
        secondsSinceSeek: Double,
        seekLead: Double = 0
    ) -> Action {
        guard engineSeconds.isFinite, videoSeconds.isFinite else { return .none }
        let drift = engineSeconds - videoSeconds
        let canSeek = secondsSinceSeek >= seekCooldownSeconds

        if !enginePlaying {


            if videoRate != 0 { return .pause }
            if abs(drift) > seekDriftSeconds && canSeek {
                return .seek(seconds: max(0, engineSeconds))
            }
            return .none
        }

        if abs(drift) > seekDriftSeconds && canSeek {






            let rate = engineRate.isFinite && engineRate > 0 ? engineRate : 1
            return .seek(seconds: max(0, engineSeconds + max(0, seekLead) * rate))
        }

        let base = engineRate.isFinite && engineRate > 0 ? Float(engineRate) : 1
        let rawNudge = abs(drift) <= ignoreDriftSeconds ? 0 : Float(drift * nudgeGain)
        let nudge = min(max(rawNudge, -maxRateNudge), maxRateNudge)
        let target = min(max(base + nudge, 0.1), 2.0)
        if videoRate == 0 || abs(videoRate - target) > 0.01 {
            return .play(rate: target)
        }
        return .none
    }
}
