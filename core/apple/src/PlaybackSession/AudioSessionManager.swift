import AVFoundation
import NuvxShared


























enum AudioSessionManager {
    private static let sessionQueue = DispatchQueue(label: "com.bxane.nuvx.audio-session")
    private static let readiness = AudioSessionReadiness()



    static var isActive: Bool {
        #if os(iOS)
        readiness.isActive
        #else
        true
        #endif
    }

    private static func record(_ event: String) {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        let message = "audio session \(event) category=\(session.category.rawValue) "
            + "mode=\(session.mode.rawValue) options=\(session.categoryOptions.rawValue) "
            + "policy=\(session.routeSharingPolicy.rawValue) "
            + "active=\(isActive) otherAudio=\(session.isOtherAudioPlaying)"
        PlaybackDebugLog.shared.record(message)
        NSLog("[Nuvx] %@", message)
        #endif
    }



    struct Format: Equatable {
        let rate: Double
        let channels: UInt32
    }

















    @discardableResult
    static func activate(preferredSampleRate: Double? = nil, restartRouting: Bool = false) async throws -> Format? {
        #if os(macOS)
        _ = await HeadphoneRouting.shared.acquire(allowSwitching: !PlatformSettings.shared.getBoolean(key: "prefer_usb_dac", default: false), restart: restartRouting)
        #endif
        return try await withCheckedThrowingContinuation { continuation in
            sessionQueue.async {
                do {
                    continuation.resume(returning: try activateSynchronously(
                        preferredSampleRate: preferredSampleRate
                    ))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private static func activateSynchronously(preferredSampleRate: Double?) throws -> Format? {
#if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            try readiness.activate {


                let mixing = PlatformSettings.shared.getBoolean(
                    key: "mix_with_other_audio", default: true
                )
                let options: AVAudioSession.CategoryOptions = mixing ? [.mixWithOthers] : []



                let policy: AVAudioSession.RouteSharingPolicy = mixing ? .default : .longFormAudio
                if session.category != .playback || session.mode != .default || session.categoryOptions != options || session.routeSharingPolicy != policy {
                    try session.setCategory(.playback, mode: .default, policy: policy, options: options)
                }
                let outputKind = session.currentRoute.outputs.first?.portType.rawValue ?? ""
                let effectivePreferredRate = LosslessRoute.cappedLosslessSampleRate(
                    routeKind: outputKind,
                    requested: preferredSampleRate
                )
                if PlatformSettings.shared.getBoolean(
                    key: "match_source_sample_rate", default: true
                ), let effectivePreferredRate,
                   session.preferredSampleRate != effectivePreferredRate {


                    do {
                        try session.setPreferredSampleRate(effectivePreferredRate)
                    } catch {
                        record("preferred output rate \(effectivePreferredRate) Hz rejected (nonfatal): \(error)")
                    }
                }
                try session.setActive(true)
            }
            record("activation succeeded")
        } catch {
            record("activation failed: \(error)")
            throw error
        }










        let hardwareChannels = session.outputNumberOfChannels
        let routeChannels = session.currentRoute.outputs.first?.channels?.count ?? 0
        let channels = hardwareChannels > 0
            ? hardwareChannels
            : (routeChannels > 0 ? routeChannels : (session.currentRoute.outputs.isEmpty ? 0 : 2))









        let output = session.currentRoute.outputs.first
        NSLog("[Nuvx] audio session %@",
              "category=\(session.category.rawValue) mode=\(session.mode.rawValue) "
              + "options=\(session.categoryOptions.rawValue) volume=\(session.outputVolume) "
              + "policy=\(session.routeSharingPolicy.rawValue) "
              + "route=\(output.map { "\($0.portType.rawValue)/\($0.portName)" } ?? "none") "
              + "rate=\(session.sampleRate) ch=\(channels) "
              + "otherAudio=\(session.isOtherAudioPlaying) "
              + "shouldSilenceSecondary=\(session.secondaryAudioShouldBeSilencedHint)")

        guard channels > 0, session.sampleRate > 0 else { return nil }
        return Format(rate: session.sampleRate, channels: UInt32(channels))
#else
        return nil
#endif
    }



    static func deactivate(notifyOthers: Bool = true) async {
        #if os(macOS)
        await HeadphoneRouting.shared.release()
        #endif
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            sessionQueue.async {
                deactivateSynchronously(notifyOthers: notifyOthers)
                continuation.resume()
            }
        }
    }

    private static func deactivateSynchronously(notifyOthers: Bool) {
#if os(iOS)
        readiness.invalidate()
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: notifyOthers ? [.notifyOthersOnDeactivation] : [])
            record(notifyOthers ? "deactivated (.notifyOthersOnDeactivation)" : "deactivated (without notification)")
        } catch {
            record("deactivation failed: \(error)")
        }
#endif
    }















    @discardableResult
    static func observeRouteChanges(
        _ handler: @escaping () -> Void,
        onOldDeviceUnavailable: (() -> Void)? = nil
    ) -> [NSObjectProtocol] {
#if os(iOS)
        let center = NotificationCenter.default
        let token = center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
            else { return }
            switch reason {
            case .oldDeviceUnavailable:
                NSLog("[Nuvx] audio route device unavailable (e.g. headphones unplugged)")
                onOldDeviceUnavailable?()
                handler()
            case .newDeviceAvailable,
                 .routeConfigurationChange, .categoryChange, .override:
                NSLog("[Nuvx] audio route changed (reason %d)", raw)
                handler()
            default:
                break
            }
        }
        return [token]
#else
        return [MacAudioRoutes(changed: handler, disconnected: onOldDeviceUnavailable)]
#endif
    }

    static func observeSpatialCapabilities(_ handler: @escaping (Bool?) -> Void) -> [NSObjectProtocol] {
        #if os(iOS)
        return [NotificationCenter.default.addObserver(forName: AVAudioSession.spatialPlaybackCapabilitiesChangedNotification,
            object: nil, queue: .main) { notification in
                handler((notification.userInfo?[AVAudioSessionSpatialAudioEnabledKey] as? NSNumber)?.boolValue)
            }]
        #else
        return []
        #endif
    }






    @discardableResult
    static func observeInterruptions(
        began: @escaping () -> Void,
        ended: @escaping (_ shouldResume: Bool) -> Void
    ) -> [NSObjectProtocol] {
#if os(iOS)
        let center = NotificationCenter.default
        let token = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw)
            else { return }
            switch type {
            case .began:
                readiness.invalidate()
                record("interrupted")
                began()
            case .ended:
                let optionRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionRaw)
                    .contains(.shouldResume)
                ended(shouldResume)
            @unknown default:
                break
            }
        }
        return [token]
#else
        _ = began
        _ = ended
        return []
#endif
    }





















    @discardableResult
    static func observeSecondaryAudioSilence(
        _ handler: @escaping (Bool) -> Void
    ) -> [NSObjectProtocol] {
#if os(iOS)
        let session = AVAudioSession.sharedInstance()
        return [NotificationCenter.default.addObserver(
            forName: AVAudioSession.silenceSecondaryAudioHintNotification,
            object: nil,
            queue: .main
        ) { _ in



            handler(session.secondaryAudioShouldBeSilencedHint)
        }]
#else
        return []
#endif
    }

    @discardableResult
    static func observeSessionEvents(_ handler: @escaping (String) -> Void) -> [NSObjectProtocol] {
#if os(iOS)
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        var tokens: [NSObjectProtocol] = []

        tokens.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 0
            let began = AVAudioSession.InterruptionType(rawValue: raw) == .began
            let optionRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionRaw)
                .contains(.shouldResume)
            handler("audio interruption \(began ? "began" : "ended") shouldResume=\(shouldResume)")
        })

        tokens.append(center.addObserver(
            forName: AVAudioSession.silenceSecondaryAudioHintNotification,
            object: nil,
            queue: .main
        ) { _ in
            handler("secondary-audio hint: otherAudio=\(session.isOtherAudioPlaying) "
                    + "shouldSilence=\(session.secondaryAudioShouldBeSilencedHint)")
        })

        tokens.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil,
            queue: .main
        ) { _ in
            readiness.invalidate()
            record("media services were reset")
            handler("audio media services were reset")
        })

        return tokens
#else
        _ = handler
        return []
#endif
    }
}
