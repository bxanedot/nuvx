import Foundation












var failures = 0
var checks = 0

func pass(_ label: String, _ detail: String) {
    checks += 1
    print("  ok   \(label)\(detail.isEmpty ? "" : " — \(detail)")")
}

func fail(_ label: String, _ detail: String) {
    checks += 1
    failures += 1
    print("  FAIL \(label)\(detail.isEmpty ? "" : " — \(detail)")")
}

func checkAction(
    _ label: String,
    _ got: VideoSyncPolicy.Action,
    _ want: VideoSyncPolicy.Action
) {
    if got == want {
        pass(label, "")
    } else {
        fail(label, "got \(got), want \(want)")
    }
}

func checkRate(
    _ label: String,
    _ got: VideoSyncPolicy.Action,
    _ want: Float
) {
    if case .play(let rate) = got, abs(rate - want) < 0.0005 {
        pass(label, String(format: "%.3fx", rate))
    } else {
        fail(label, "got \(got), want play(\(want))")
    }
}

let policy = VideoSyncPolicy.self


checkAction("in sync does nothing",
            policy.decide(engineSeconds: 100, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .none)


checkAction("half a second behind seeks to the engine",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 0, secondsSinceSeek: 1),
            .seek(seconds: 100.5))
checkAction("half a second ahead seeks back to the engine",
            policy.decide(engineSeconds: 99.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .seek(seconds: 99.5))


checkRate("0.1 s behind speeds the picture up",
          policy.decide(engineSeconds: 100.1, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
          1.08)
checkRate("0.1 s ahead slows the picture down",
          policy.decide(engineSeconds: 99.9, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
          0.92)


checkRate("the rate nudge is clamped at 1.1x",
          policy.decide(engineSeconds: 100.14, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
          1.1)



checkAction("20 ms of drift is ignored while the picture runs",
            policy.decide(engineSeconds: 100.02, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .none)
checkRate("a stopped picture starts even inside the ignore band",
          policy.decide(engineSeconds: 100.02, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1, videoRate: 0, secondsSinceSeek: 1),
          1.0)


checkAction("already at the nudged rate does nothing",
            policy.decide(engineSeconds: 100.1, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1.08, secondsSinceSeek: 1),
            .none)


checkAction("a paused transport pauses a moving picture",
            policy.decide(engineSeconds: 100, videoSeconds: 99, enginePlaying: false,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .pause)
checkAction("a paused scrub seeks the still picture",
            policy.decide(engineSeconds: 100, videoSeconds: 99, enginePlaying: false,
                          engineRate: 1, videoRate: 0, secondsSinceSeek: 1),
            .seek(seconds: 100))


checkAction("the seek cooldown blocks a second seek",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 0.1),
            .play(rate: 1.1))
checkAction("the cooldown expires at 0.5 s",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 0.5),
            .seek(seconds: 100.5))



checkAction("a playing engine is sought ahead by the measured seek time",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 0, secondsSinceSeek: 1, seekLead: 0.4),
            .seek(seconds: 100.9))
checkAction("the lead scales with a faster engine",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1.5, videoRate: 0, secondsSinceSeek: 1, seekLead: 0.4),
            .seek(seconds: 101.1))
checkAction("a paused engine is sought exactly, with no lead",
            policy.decide(engineSeconds: 100, videoSeconds: 99, enginePlaying: false,
                          engineRate: 1, videoRate: 0, secondsSinceSeek: 1, seekLead: 0.4),
            .seek(seconds: 100))
checkRate("a picture that landed on the lead plays rather than seeking again",
          policy.decide(engineSeconds: 100.89, videoSeconds: 100.9, enginePlaying: true,
                        engineRate: 1, videoRate: 0, secondsSinceSeek: 0.4, seekLead: 0.4),
          1.0)


checkRate("a 1.5x engine runs the picture at 1.5x",
          policy.decide(engineSeconds: 100, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1.5, videoRate: 0, secondsSinceSeek: 1),
          1.5)
checkRate("the picture cannot be pushed past the player's 2x ceiling",
          policy.decide(engineSeconds: 100.1, videoSeconds: 100, enginePlaying: true,
                        engineRate: 2, videoRate: 0, secondsSinceSeek: 1),
          2.0)



checkAction("a non-finite position is ignored",
            policy.decide(engineSeconds: .nan, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .none)

checkAction("overflowing clock drift is ignored",
            policy.decide(engineSeconds: .greatestFiniteMagnitude,
                          videoSeconds: -.greatestFiniteMagnitude, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1),
            .none)
checkAction("a non-finite seek estimate cannot poison the target",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1, videoRate: 1, secondsSinceSeek: 1, seekLead: .infinity),
            .seek(seconds: 100.5))
checkAction("seek estimates are bounded to two seconds",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: 1.5, videoRate: 1, secondsSinceSeek: 1, seekLead: 100),
            .seek(seconds: 103.5))
checkRate("a non-finite video rate is restored",
          policy.decide(engineSeconds: 100, videoSeconds: 100, enginePlaying: true,
                        engineRate: 1, videoRate: .nan, secondsSinceSeek: 1),
          1)
checkRate("a non-finite engine rate falls back to normal speed",
          policy.decide(engineSeconds: 100, videoSeconds: 100, enginePlaying: true,
                        engineRate: .infinity, videoRate: 0, secondsSinceSeek: 1),
          1)
checkAction("seek prediction respects the same rate ceiling as playback",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: true,
                          engineRate: .greatestFiniteMagnitude, videoRate: 1,
                          secondsSinceSeek: 1, seekLead: 0.4),
            .seek(seconds: 101.3))
checkAction("paused scrubbing waits for the cooldown without starting playback",
            policy.decide(engineSeconds: 100.5, videoSeconds: 100, enginePlaying: false,
                          engineRate: 1, videoRate: 0, secondsSinceSeek: 0.1),
            .none)

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
