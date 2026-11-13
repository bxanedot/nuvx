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

extension QueueEntry {
    static func from(_ song: Song) -> QueueEntry {
        QueueEntry(
            id: song.videoId,
            title: song.title,
            artist: song.artist,
            source: song.localPath ?? "",
            thumbnailUrl: song.thumbnailUrl,
            durationText: song.durationText,
            albumName: song.albumName,
            artworkData: nil,
            isLocal: song.localPath != nil,


            isExplicit: song.isExplicit?.boolValue,
            isVideo: song.isVideo
        )
    }

    static func from(_ track: LocalTrack) -> QueueEntry {
        QueueEntry(
            id: track.path,
            title: track.title.isEmpty ? URL(fileURLWithPath: track.path).deletingPathExtension().lastPathComponent : track.title,
            artist: track.artist,
            source: track.path,
            thumbnailUrl: track.artworkURL,
            durationText: track.durationSeconds > 0 ? formatDuration(track.durationSeconds) : nil,
            albumName: track.album.isEmpty ? nil : track.album,
            artworkData: track.artwork,
            isLocal: true
        )
    }

    func asSongJSON(videoIDOverride: String? = nil) -> SongJSON {
        SongJSON(
            videoId: videoIDOverride ?? videoId ?? id,
            title: title,
            artist: artist,
            thumbnailUrl: thumbnailUrl,
            durationText: durationText,
            albumName: albumName,
            artistId: artistId,
            albumId: albumId,
            isVideo: false,
            fromAutoplay: fromAutoplay
        )
    }
}

struct SongJSON: Codable {
    let videoId: String
    let title: String
    let artist: String
    let thumbnailUrl: String?
    let durationText: String?
    let albumName: String?
    let artistId: String?
    let albumId: String?
    let isVideo: Bool
    let fromAutoplay: Bool
}



struct TransitionWindow: Equatable, Sendable {
    var start: Double
    var end: Double
}

enum PlaybackLatencySignposts {
    private static let log = OSLog(
        subsystem: Bundle.main.bundleIdentifier ?? "com.nuvx.app",
        category: "PlaybackLatency"
    )

    static func record(_ name: StaticString, entryID: String, elapsed: TimeInterval) {
        os_signpost(.event, log: log, name: name,
                    "%{public}@ elapsed_ms=%{public}.1f", entryID as NSString, elapsed * 1_000)
    }
}










@MainActor
@Observable
final class PlaybackController {
    typealias PlaybackLatencyRecorder = @Sendable (TimeInterval, String, String) async -> Void

    struct PlaybackLatencySample {
        let entryID: String
        let startedAt: TimeInterval
        let initialPosition: Double
        var resolvedAt: TimeInterval?
        var firstBufferAt: TimeInterval?
        var cacheState = "cold"
        var client = "unknown"
    }

    let engine = PlayerEngine()

    var state: PlaybackState = .stopped
    var current: QueueEntry?

    var playbackContext: String?

    var playbackContextID: String?


    var playbackContextEntries: [QueueEntry] = []
    var playbackContextContinuation: String?
    var playbackContextRevision: UInt64 = 0
    @ObservationIgnored var contextWindowEntries: [QueueEntry] = []
    @ObservationIgnored var contextWindowCursor = 0
    @ObservationIgnored var contextWindowBaseIndex = 0
    @ObservationIgnored var contextWindowInstalling = false
    @ObservationIgnored var contextWindowGeneration: UInt64 = 0
    @ObservationIgnored var contextContinuationTask: Task<Void, Never>?
    var queue: [QueueEntry] = [] {
        didSet { refreshUpcomingURLPrefetch() }
    }
    var playingIndex: Int = 0 {
        didSet {
            refreshUpcomingURLPrefetch()
            maintainContextWindow()
        }
    }
    var position: Double = 0
    var duration: Double = 0
    var lastError: String?
    var recoveringEntryID: String?
    var recoveryNeedsFreshResolution = false
    var sourceDecisionNotes: [String: String] = [:]
    @ObservationIgnored var streamRecovery = PlaybackRecoveryPolicy()
    @ObservationIgnored var pendingPlaybackLatency: PlaybackLatencySample?
    @ObservationIgnored var nextPreparationTask: Task<Void, Never>?
    @ObservationIgnored var urlPrefetchTask: Task<Void, Never>?
    @ObservationIgnored var urlPrefetchFingerprint: String?









    final class TransitionPacingStore: @unchecked Sendable {
        private let lock = NSLock()
        private var pacing = TransitionPacing<TransitionStyleRec>()

        func allowsEffect(for pair: TransitionPair) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            return pacing.allowsEffect(for: pair, isEffect: Self.isEffect)
        }



        func note(_ style: TransitionStyleRec, for pair: TransitionPair) {
            lock.lock()
            defer { lock.unlock() }
            pacing.note(style, for: pair)
        }

        private static func isEffect(_ style: TransitionStyleRec) -> Bool {
            switch style {
            case .echoOut, .loopRoll, .reverbWash, .dropCut, .texture, .brake: return true
            case .equalPower, .djFilter, .djBlend, .gapless: return false
            }
        }
    }

    @ObservationIgnored nonisolated let pacing = TransitionPacingStore()



    var volume: Double = 0.9 {
        didSet { applyOutputGain() }
    }









    static func outputGain(forVolume volume: Double) -> Double {
        let position = min(max(volume, 0), 1)
        return position * position * position
    }


    var targetOutputGain: Float {
        Float(Self.outputGain(forVolume: volume) * (isDucked ? Self.duckGain : 1))
    }




    private func applyOutputGain() {
        duckTask?.cancel()
        let gain = targetOutputGain
        engine.setVolume(gain: gain)
        dolbyRenderer?.volume = gain
    }

    var isPlaying: Bool { state == .playing }



    var effectivePlaybackRate: Double { playbackRate }


    var holdAfterLoad = false



    var unplayableUntil: [String: Date] = [:]


    var automaticLoadEntryId: String?


    var automaticSkips = 0
    var deferredNetworkRecovery: (entryID: String, position: Double, generation: UInt64)?
    var streamRecoveryPathChanges = 0

    var settledAutoplayIds: Set<String> = []

    var loadingGeneration: UInt64?
    var isBuffering: Bool { state == .buffering }
#if os(iOS)
    private(set) var mixWithOtherAudio = PlatformSettings.shared.getBoolean(
        key: "mix_with_other_audio", default: true
    )
    private var mixingPreferenceGeneration: UInt64 = 0


    func setMixWithOtherAudio(_ enabled: Bool) {
        guard mixWithOtherAudio != enabled else { return }
        mixWithOtherAudio = enabled
        PlatformSettings.shared.putBoolean(key: "mix_with_other_audio", value: enabled)
        mixingPreferenceGeneration &+= 1
        nowPlaying.audioSessionUnavailable()
        guard started, isPlaying || isBuffering || AudioSessionManager.isActive else { return }
        let preference = mixingPreferenceGeneration
        let selection = playGeneration
        let intent = resumeGeneration
        Task { [weak self] in
            guard let self, self.mixingPreferenceGeneration == preference,
                  self.playGeneration == selection, self.resumeGeneration == intent,
                  self.isPlaying || self.isBuffering || AudioSessionManager.isActive else { return }
            do {


                _ = try await AudioSessionManager.activate()
                guard self.mixingPreferenceGeneration == preference,
                      self.playGeneration == selection, self.resumeGeneration == intent,
                      self.isPlaying else { return }
                self.nowPlaying.updateRate(self.playbackRate, position: self.playbackPosition)
                self.nowPlaying.requestPrimaryIfPossible(reason: "mixing preference changed")
            } catch {
                guard self.mixingPreferenceGeneration == preference,
                      self.playGeneration == selection, self.resumeGeneration == intent else { return }
                self.audioActivationFailed(error)
            }
        }
    }
#endif
    var canPlayPrevious: Bool {
        playingIndex > 0 || contextWindowBaseIndex > 0 || (repeatMode == .all && !queue.isEmpty)
    }
    var canPlayNext: Bool {
        playingIndex + 1 < queue.count
            || (repeatMode == .all && !queue.isEmpty)
            || (repeatMode == .one && automixEnabled && current != nil)
    }


    enum RepeatMode: Int, CaseIterable {
        case off, all, one
    }

    var repeatMode: RepeatMode = .off {
        didSet { syncSkipMixEnabled() }
    }
    var shuffleEnabled = false



    var repeatAllStash: [QueueEntry] = []

    var repeatAllStashSeed: String?
    @ObservationIgnored var autoplayRefresh = AutoplayRefreshState()
    @ObservationIgnored var queueEditRevision: UInt64 = 0
    @ObservationIgnored var sequencingTask: Task<Void, Never>?
    @ObservationIgnored var sequencingGeneration: UInt64 = 0
    #if DEBUG
    @ObservationIgnored var recommendationFetchOverride: ((String, @escaping (String?, String?) -> Void) -> Void)?
    #endif
    var lyrics: [LyricLineDto] = []

    var lyricsSourceLabel: String?
    var lyricsLoading = false


    let lyricsTranslator = LyricsTranslator()






    var displayedLyrics: [LyricLineDto] {
        lyricsTranslator.translatedLines ?? lyrics
    }








    var lyricsUnavailable: Bool {
        attemptedLyrics && !lyricsLoading && lyrics.isEmpty
    }

    var attemptedLyrics = false

    var lyricsAligning = false



    var lyricsAlignedLocally: LyricsAudioAligner.Coverage?

    @ObservationIgnored var lyricsNeedsDurationRefresh = false

    @ObservationIgnored var lyricsRequestGeneration: UInt64 = 0
    @ObservationIgnored var lyricsAlignmentTask: Task<Void, Never>?
    var canvasURL: URL?
    var canvasFallbackURL: URL?


    var canvasSource: String?
    var canvasLookupGeneration: UInt64 = 0
    var canvasIdentity: String?
    var nerd: NerdStatsRec?


    var racingLossless = false

    var analysisTier: String?

    var transitionReason: String?


    var analysisSources: String?

    var analysisConfidence: Double?

    var smartTransitionWindow: TransitionWindow?

    var smartMixInProgress = false







    var automixCueSeconds: Double?
    var sleepDeadline: ContinuousClock.Instant?
    var sleepTask: Task<Void, Never>?
    var sleepSecondsRemaining: Int?
    var sleepAfterTrack = false
    var autoplayEnabled = PlatformSettings.shared.getBoolean(key: "autoplay", default: true)
    var automixEnabled = PlatformSettings.shared.getBoolean(key: "smart_fade_enabled", default: true)
    var automixOnSkipEnabled = PlatformSettings.shared.getBoolean(key: "automix_on_skip", default: false)
    var hideVolumeBar = PlatformSettings.shared.getBoolean(key: "hide_volume_bar", default: false)


    var hideSongStatus = PlatformSettings.shared.getBoolean(key: "hide_song_status", default: false)




    var outputPcmMode = migrateOutputPcmMode()


    var bitPerfectOutput = PlatformSettings.shared.getBoolean(key: "bit_perfect_output", default: false)

    var matchSourceSampleRate = PlatformSettings.shared.getBoolean(key: "match_source_sample_rate", default: true)




    var preferUsbDac = PlatformSettings.shared.getBoolean(key: "prefer_usb_dac", default: false)



    var soundMode = PlatformSettings.shared.getString(key: "sound_mode", default: "TRANSPARENT")
    var clarityPreset = PlatformSettings.shared.getString(key: "clarity_preset", default: "REFERENCE")
    var clarityWet = Double(PlatformSettings.shared.getFloat(key: "clarity_wet", default: 1))
    var loudnessMode = PlatformSettings.shared.getString(key: "loudness_mode", default: PlatformSettings.shared.getBoolean(key: "loudness_normalization", default: true) ? "TRACK" : "OFF")
    var loudnessNormalization = PlaybackController.storedLoudnessMode() != .off



    var automixPerformanceMode = PlatformSettings.shared.getString(key: "automix_performance", default: "BALANCED")

    var automixPhraseBars: Int = {
        let stored = Int(PlatformSettings.shared.getInt(key: "automix_phrase_bars", default: 0))
        return stored == 8 || stored == 16 ? stored : 0
    }()




    var smartVersionAlignment = PlatformSettings.shared.getBoolean(key: "smart_version_alignment", default: true)



    var preferMusicOnly = PlatformSettings.shared.getBoolean(key: "prefer_music_only", default: false)


    var scrobblePrimaryArtist = PlatformSettings.shared.getString(key: "scrobble_primary_artist", default: "track")
    var scrobbleArmed = false
    var scrobbleSent = false



    var currentLoudnessDb: Double?

    static let backRestartsAfter: TimeInterval = 10


    var sleepTimerStatus: String? {
        if let sleepDeadline {
            let remaining = sleepRemaining(sleepDeadline)
            guard remaining > 0 else { return nil }
            let seconds = sleepSecondsRemaining ?? Int(remaining.rounded())
            return String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        return sleepAfterTrack ? "After this song" : nil
    }


    var debugLogText: String { debugLog.dump() }
    var diagnosticSnapshot: String { "engine=\(String(describing: engine.nerdStats()))\nroute=\(String(describing: engine.outputDevice()))\ntrim_edges=\(PlatformSettings.shared.getBoolean(key: "trim_edge_silence", default: false)) skip_non_music=\(PlatformSettings.shared.getBoolean(key: "skip_non_music", default: false))\noutput=\(String(describing: engine.outputHealth()))\nstate=\(state) automix=\(automixEnabled) volume=\(volume)" }

    let debugLog = PlaybackDebugLog.shared
    var pendingAutomixPlan: TransitionPlanRec?
    var upgradeFor: String?
    @ObservationIgnored var qualityUpgradeTask: Task<Void, Never>?
    var mixFadeUntil: Date?


    var dolbyRenderer: AppleDolbyRenderer?
    var playbackPosition: Double { dolbyRenderer?.position ?? engine.positionSeconds() }
    @ObservationIgnored var durationRepairTask: Task<Void, Never>?
    var engineLoadedId: String?

    var historyVideoID: String?










    var regionTasks: [String: Task<Void, Never>] = [:]
    var resolvedVideoIds: [String: String] = [:]
    var preparedRegionSources: Set<String> = []

    var regionEdgesBySource: [String: (start: Double, end: Double?)] = [:]
    var loudnessMeasurementTask: Task<Void, Never>?
    var loadedSourcePath: String? {
        didSet { if oldValue != loadedSourcePath {
            scheduleLoudnessMeasurement()
            if let source = loadedSourcePath {
                DownloadStore.shared.retainPlayback(paths: Set([source] + Array(sourceHeadersByPath.keys)))
                prepareRegions(source: source, videoId: resolvedVideoIds[source] ?? DownloadStore.shared.provenance(for: source))
            } else { DownloadStore.shared.retainPlayback(paths: Set(sourceHeadersByPath.keys)) }
        } }
    }


    var loadedSourceHeaders: [String: String] = [:]
    var sourceHeadersByPath: [String: [String: String]] = [:]


    var queueNextRevision: UInt64 = 0









    var restoredStart: (entryId: String, position: Double)?
    private var lastPersistAt = Date.distantPast

    private var positionTimer: Timer?
    var started = false



    var engineStartupTask: Task<Void, Error>?


    var playGeneration: UInt64 = 0


    @ObservationIgnored let loadSubmissionGate = PlaybackLoadSubmissionGate()


    var resumeGeneration: UInt64 = 0
#if os(iOS)
    @ObservationIgnored var playbackStartTask: Task<Void, Never>?
#endif
    let nowPlaying = NowPlayingController()
    let widgetPublisher = WidgetStatePublisher()
    let headTracker = HeadTracker()


    private var routeObservers: [NSObjectProtocol] = []

    var wasInterrupted = false

    init() {
        NativeStreamVerifier.install()
        _ = LoadingMonitor.shared
        _ = LaunchReadiness.shared
        let configuredSpeed = Double(PlatformSettings.shared.getFloat(key: "playback_speed", default: 1))
        playbackRate = configuredSpeed.isFinite ? min(max(configuredSpeed, 0.5), 2.0) : 1.0
        engine.registerCallback(callback: EngineCallbacks(controller: self))


        applyOutputGain()



        routeObservers = AudioSessionManager.observeRouteChanges(
            { [weak self] in
                Task { @MainActor in
                    AudioRouteState.shared.refresh()
                    self?.applySpatialPreference()
                    self?.outputRouteChanged()
                }
            },
            onOldDeviceUnavailable: { [weak self] in
                Task { @MainActor in
                    guard let self, self.isPlaying || self.isBuffering || self.wasInterrupted else { return }
                    self.pausePlayback()
                }
            }
        )
        routeObservers.append(NotificationCenter.default.addObserver(
            forName: .partyMembershipDidChange, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.syncSkipMixEnabled() }
        })
        #if os(iOS)
        routeObservers.append(NotificationCenter.default.addObserver(forName: UIAccessibility.monoAudioStatusDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in AudioRouteState.shared.refresh(); self?.applySpatialPreference() }
        })
        #endif
        AudioRouteState.shared.refresh()
        syncSkipMixEnabled()
        routeObservers += AudioSessionManager.observeSpatialCapabilities { [weak self] enabled in
            Task { @MainActor in
                AudioRouteState.shared.refresh(systemSpatialEnabled: enabled)
                self?.applySpatialPreference()
            }
        }




        routeObservers += AudioSessionManager.observeInterruptions(
            began: { [weak self] in
                Task { @MainActor in
                    guard let self else { return }
                    NSLog("[Nuvx] Audio interruption began")
                    self.audioInterruptionBegan()
                }
            },
            ended: { [weak self] shouldResume in
                Task { @MainActor in
                    guard let self else { return }
                    NSLog("[Nuvx] Audio interruption ended, shouldResume=\(shouldResume)")
                    self.audioInterruptionEnded(shouldResume: shouldResume)
                }
            }
        )

        routeObservers += AudioSessionManager.observeSessionEvents { [weak self] event in
            PlaybackDebugLog.shared.record(event)
            NSLog("[Nuvx] %@", event)
            if event == "audio media services were reset" {
                Task { @MainActor in
                    self?.nowPlaying.audioSessionUnavailable()
                    AudioRouteState.shared.refresh()
                    self?.applySpatialPreference()
                    self?.outputRouteChanged()
                }
            }
        }




        routeObservers += AudioSessionManager.observeSecondaryAudioSilence { [weak self] shouldSilence in
            Task { @MainActor in
                self?.applyDuck(shouldSilence)
                if !shouldSilence {
                    self?.nowPlaying.requestPrimaryIfPossible(reason: "other audio ended")
                }
            }
        }
        nowPlaying.onToggle = { [weak self] in self?.togglePlayPause() }
        nowPlaying.onPlay = { [weak self] in
            guard let self, !self.isBuffering else { return }
            if self.isPlaying {



                self.nowPlaying.updateRate(self.playbackRate, position: self.playbackPosition)
                self.reactivateAudioSessionAfterForeground(reason: "native play")
                return
            }
            self.togglePlayPause()
        }
#if os(iOS)
        nowPlaying.onPlayAsync = { [weak self] in
            guard let self else { throw CancellationError() }
            self.nowPlaying.onPlay?()
            let intent = self.resumeGeneration
            let selection = self.playGeneration
            await self.playbackStartTask?.value
            guard self.resumeGeneration == intent, self.playGeneration == selection,
                  self.isPlaying else { throw CancellationError() }
        }
#endif
        nowPlaying.onPause = { [weak self] in
            guard let self else { return }


            self.wasInterrupted = false
            self.resumeGeneration &+= 1
            guard self.isPlaying || self.isBuffering else { return }
            self.pausePlayback()
        }
        nowPlaying.onNext = { [weak self] in self?.next() }
        nowPlaying.onPrevious = { [weak self] in self?.previous() }
        nowPlaying.onSeek = { [weak self] seconds in self?.seek(to: seconds) }




        QualityUpgrade.forgetLastSession()
        restoreSession()
        let token = PlatformSettings.shared.getSecret(key: "discord_token") ?? ""
        if !token.isEmpty { DiscordGateway.shared.connect(token: token) }
    }

    func startEngineIfNeeded() {
        guard !started else { return }
        started = true
        Task { [weak self] in
            guard let self else { return }
            await reloadAutomixModels(playbackStarted: self.started)
            if self.automixEnabled { self.syncEngineQueueNext() }
        }




        let eng = engine
        let crossfade = Double(PlatformSettings.shared.getInt(key: "crossfade_seconds", default: 0))
        let spatial = PlatformSettings.shared.getBoolean(key: "spatial_audio", default: false) && AudioRouteState.shared.permitsCustomSpatial
        let skip = PlatformSettings.shared.getBoolean(key: "skip_silence", default: false)
        let speed = PlatformSettings.shared.getFloat(key: "playback_speed", default: 1)
        let startBitPerfect = effectiveBitPerfectOutput
        playbackRate = Double(speed).isFinite ? min(max(Double(speed), 0.5), 2.0) : 1.0
        let eq = Self.currentEqTuning()
        engineStartupTask = Task.detached(priority: .utility) { [weak self] in
            do {



                let format = try await AudioSessionManager.activate()



                try eng.setOutputPcmMode(mode: Self.migrateOutputPcmMode())
                try eng.setBitPerfectEnabled(enabled: startBitPerfect)
                try eng.setPreferUsbDac(
                    enabled: PlatformSettings.shared.getBoolean(key: "prefer_usb_dac", default: false)
                )
                eng.setAutomixPerformance(
                    mode: PlatformSettings.shared.getString(key: "automix_performance", default: "BALANCED")
                )
                let storedPhraseBars = PlatformSettings.shared.getInt(key: "automix_phrase_bars", default: 0)
                let phraseBars = storedPhraseBars == 8 || storedPhraseBars == 16 ? storedPhraseBars : 0
                eng.setAutomixPhraseBars(bars: UInt32(phraseBars))
                try eng.start(
                    rate: format?.rate,
                    channels: format?.channels
                )
                try eng.setSoundMode(mode: PlatformSettings.shared.getString(key: "sound_mode", default: "TRANSPARENT") == "ENHANCED" ? .enhanced : .transparent)
                try eng.setClarityTuning(tuning: Self.storedClarityTuning())
                try eng.setLoudnessMode(mode: Self.storedLoudnessMode())
                try eng.setCrossfadeWindow(seconds: crossfade)
                try eng.setSpatialEnabled(enabled: spatial)
                try eng.setSkipSilence(enabled: skip)
                try eng.setPlaybackSpeed(speed: speed)
                try eng.setEqTuning(
                    enabled: eq.enabled,
                    gainsDb: eq.gains,
                    qs: eq.qs,
                    balance: eq.balance
                )

                if spatial && PlatformSettings.shared.getString(key: "sound_mode", default: "TRANSPARENT") == "ENHANCED" {
                    await MainActor.run { [weak self] in self?.headTracker.start(engine: eng) }
                }
            } catch {
                await MainActor.run { [weak self] in
                    guard let self else { return }
                    self.started = false
                    self.positionTimer?.invalidate()
                    self.positionTimer = nil
                    self.lastError = "Audio engine failed to start: \(error)"
                }
                throw error
            }
        }
        positionTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                for event in drainTechnicalEvents() { self.debugLog.record(event) }
                self.pollWidgetCommands()
                self.pollStreamRecovery()
                guard self.state == .playing else { return }


                if self.dolbyRenderer == nil {
                    let latest = self.engine.nerdStats()
                    if self.nerd != latest { self.nerd = latest }
                }
                self.position = self.playbackPosition
                self.positionSampledAt = Date()
                self.nowPlaying.update(position: self.position)
                if let sample = self.pendingPlaybackLatency,
                   sample.entryID == self.current?.id,
                   let resolvedAt = sample.resolvedAt,
                   let firstBufferAt = sample.firstBufferAt,
                   self.position > sample.initialPosition + 0.01 {
                    let audibleAt = ProcessInfo.processInfo.systemUptime
                    let summary = String(
                        format: "latency: tap→resolved %.0fms, →first buffer %.0fms, →first audible %.0fms (%@, %@)",
                        (resolvedAt - sample.startedAt) * 1_000,
                        (firstBufferAt - sample.startedAt) * 1_000,
                        (audibleAt - sample.startedAt) * 1_000,
                        sample.cacheState as NSString,
                        sample.client as NSString
                    )
                    PlaybackDebugLog.shared.record(summary, about: sample.entryID)
                    self.pendingPlaybackLatency = nil
                }






                self.tickSleep()
                self.tickScrobble()
                self.tickHistory()
                self.tickListening()
                if Date().timeIntervalSince(self.lastPersistAt) >= 4 {
                    self.lastPersistAt = Date()
                    self.persistSession()
                }
            }
        }
    }




    func reactivateAudioSessionAfterForeground(reason: String = "foreground") {
        guard started, state == .playing else { return }
        let selection = playGeneration
        let intent = resumeGeneration
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                _ = try await AudioSessionManager.activate()
                await MainActor.run { [weak self] in
                    guard let self, self.state == .playing,
                          self.playGeneration == selection, self.resumeGeneration == intent else { return }
                    self.nowPlaying.requestPrimaryIfPossible(reason: reason)
                }
            } catch {
                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == selection,
                          self.resumeGeneration == intent else { return }
                    self.audioActivationFailed(error)
                }
            }
        }
    }

    func audioActivationFailed(_ error: Error) {
        nowPlaying.audioSessionUnavailable()
        if isPlaying || isBuffering { pausePlayback(releaseAudioSession: true) }
        lastError = "Audio session could not activate: \(error)"
    }















    private var routeRecoveryGeneration: UInt64 = 0
    private func outputRouteChanged() {
        let route = AudioRouteState.shared
        let returnedFromBluetooth = route.previousKind.lowercased().contains("bluetooth") && route.carriesLossless
        routeRecoveryGeneration &+= 1
        let recovery = routeRecoveryGeneration
        guard started, state == .playing else { return }
        let selection = playGeneration
        let intent = resumeGeneration
        let engine = self.engine
        let sessionWasActive = AudioSessionManager.isActive
        Task.detached(priority: .userInitiated) { [weak self] in
            let sessionFormat: AudioSessionManager.Format?
            do {
                sessionFormat = try await AudioSessionManager.activate()
            } catch {
                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == selection,
                          self.resumeGeneration == intent, self.routeRecoveryGeneration == recovery else { return }
                    self.audioActivationFailed(error)
                }
                return
            }
            await MainActor.run { [weak self] in
                guard let self, self.isPlaying, self.playGeneration == selection,
                      self.resumeGeneration == intent, self.routeRecoveryGeneration == recovery else { return }
                do {
                    try engine.setLoudnessMode(mode: self.routeAwareLoudnessMode)
                    try engine.setBitPerfectEnabled(enabled: self.effectiveBitPerfectOutput)
                    if self.dolbyRenderer == nil {
                        if Self.routeEventKeepsOutput(engine.outputDevice(), session: sessionFormat,
                                                      sessionWasActive: sessionWasActive) {
                            self.debugLog.record("route event kept the rebuilt output")
                        } else {
                            try engine.requestOutputRebuild(force: true)
                        }
                    }
                    if returnedFromBluetooth, let entry = self.current {
                        QualityUpgrade.resetAutomaticAttemptForRoute(entry.id)
                        self.lookForBetterCopy(entry, codec: self.nerd?.codec ?? "", kbps: self.nerd?.kbps ?? 0)
                    }
                    self.nowPlaying.requestPrimaryIfPossible(reason: "route activation")
                } catch {
                    NSLog("[Nuvx] output rebuild after route change failed: \(error)")
                }
            }
        }
    }














    nonisolated static func routeEventKeepsOutput(
        _ output: OutputDeviceRec, session: AudioSessionManager.Format?, sessionWasActive: Bool
    ) -> Bool {
        #if os(iOS)
        guard sessionWasActive, output.started, let session else { return false }
        return Double(output.sampleRate) == session.rate && output.channels == session.channels
        #else
        return false
        #endif
    }






    private static let duckGain: Double = 0.16





    @ObservationIgnored private var duckTask: Task<Void, Never>?

    @ObservationIgnored private var isDucked = false

















    private func applyDuck(_ shouldDuck: Bool) {
        guard shouldDuck != isDucked else { return }
        isDucked = shouldDuck
        duckTask?.cancel()
        let full = Self.outputGain(forVolume: volume)
        let from = Float(full * (shouldDuck ? 1.0 : Self.duckGain))
        let to = targetOutputGain
        let engine = self.engine
        duckTask = Task(priority: .userInitiated) { [weak self] in
            let steps = 20
            for step in 1...steps {
                if Task.isCancelled { return }
                let progress = Float(step) / Float(steps)
                engine.setVolume(gain: from + (to - from) * progress)
                self?.dolbyRenderer?.volume = from + (to - from) * progress
                try? await Task.sleep(for: .milliseconds(10))
            }
        }
    }





    @ObservationIgnored var songVersions: [String: QueueEntry] = [:]













    @ObservationIgnored var onLocalIntent: (() -> Void)?






    @ObservationIgnored var intentSuppression = 0


    @ObservationIgnored var positionSampledAt = Date.distantPast

    @ObservationIgnored var playbackRate = 1.0

    var resolvedOrigin: [String: ResolvedSource.Origin] = [:]






    var lyricsProviderStates: [String: LyricsSourceState] = [:]
    var lyricsProviderResults: [String: [LyricLineDto]] = [:]



    var lyricsProviderChoice: String?
}





protocol LyricsChoiceStore {
    func choice(for trackId: String) -> String?
    func remember(_ source: String, for trackId: String)
}
