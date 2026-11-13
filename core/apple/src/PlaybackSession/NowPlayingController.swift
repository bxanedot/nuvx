import Foundation
import Observation
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import MediaPlayer
import NuvxShared
#if os(iOS)
#if canImport(NowPlaying)
import NowPlaying
#endif
import AVFoundation
#if DEBUG
import Synchronization
#endif
#endif



@MainActor
final class NowPlayingController {
    private var metadataKey: String?
    private var artwork: MPMediaItemArtwork?

    private var artworkURL: String?
    private var lastRequestedURL: String?
    private var handlers: [Any] = []
#if os(iOS) && canImport(NowPlaying)
    private var modern: AnyObject?
#endif

    init() {
#if os(iOS)
        NowPlayingActivityController.endLegacyActivities()
#if canImport(NowPlaying)
        if #available(iOS 27, *) {
            resetModernModel()






            return
        }
#endif
        DispatchQueue.main.async {
            UIApplication.shared.beginReceivingRemoteControlEvents()
        }
#endif

        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = true
        handlers.append(center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.onPlay?() }
            return .success
        })
        center.pauseCommand.isEnabled = true
        handlers.append(center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.onPause?() }
            return .success
        })
        center.togglePlayPauseCommand.isEnabled = true
        handlers.append(center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.onToggle?() }
            return .success
        })
        center.nextTrackCommand.isEnabled = true
        handlers.append(center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.onNext?() }
            return .success
        })
        center.previousTrackCommand.isEnabled = true
        handlers.append(center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.onPrevious?() }
            return .success
        })
        center.changePlaybackPositionCommand.isEnabled = true
        handlers.append(center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime else {
                return .commandFailed
            }
            Task { @MainActor [weak self] in self?.onSeek?(position) }
            return .success
        })
    }

    var onPlay: (() -> Void)?
#if os(iOS)
    var onPlayAsync: (() async throws -> Void)?
#endif
    var onPause: (() -> Void)?
    var onToggle: (() -> Void)?
    var onNext: (() -> Void)?
    var onPrevious: (() -> Void)?
    var onSeek: ((Double) -> Void)?

#if os(iOS) && canImport(NowPlaying)
    @available(iOS 27, *)
    private func resetModernModel() {
        let model = ModernNowPlaying()
        model.delegate = self
        modern = model
    }
#endif
#if os(iOS)
#if DEBUG
    func verifyModernObservation() -> [String: Bool] {
#if canImport(NowPlaying)
        if #available(iOS 27, *) { return ModernNowPlaying.verifyObservation() }
#endif
        return [:]
    }
#endif
#endif



    func prepareForPlayback(id: String, title: String, artist: String, duration: Double,
                            artworkData: Data?, thumbnailUrl: String?, position: Double,
                            canNext: Bool, canPrevious: Bool, canSeek: Bool) {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.prepare(id: id, title: title, artist: artist, duration: duration,
                          artworkData: artworkData, thumbnailUrl: thumbnailUrl, position: position,
                          canNext: canNext, canPrevious: canPrevious, canSeek: canSeek)
        }
#endif
    }

    func update(id: String, title: String, artist: String, duration: Double,
                artworkData: Data?, thumbnailUrl: String?, isPlaying: Bool,
                position: Double? = nil) {


#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.update(id: id, title: title, artist: artist, duration: duration,
                         artworkData: artworkData, thumbnailUrl: thumbnailUrl,
                         isPlaying: isPlaying, position: position)
            return
        }
#endif
        let key = id
        if metadataKey != key {
            metadataKey = key
            artwork = nil
            artworkURL = nil
            lastRequestedURL = nil
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(PlatformSettings.shared.getFloat(key: "playback_speed", default: 1)) : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let position {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        } else if let old = MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = old
        }
        if lastRequestedURL != thumbnailUrl { lastRequestedURL = nil }
        if let item = artworkFromBytes(artworkData) {
            info[MPMediaItemPropertyArtwork] = item
            artworkURL = thumbnailUrl
            lastRequestedURL = thumbnailUrl
        } else if let artwork, artworkURL == thumbnailUrl {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
#if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
#endif
        if let thumbnailUrl, !thumbnailUrl.isEmpty, artworkData == nil,
           artworkURL != thumbnailUrl, lastRequestedURL != thumbnailUrl {
            fetchArtwork(
                id: id, url: thumbnailUrl, title: title, artist: artist,
                duration: duration, isPlaying: isPlaying
            )
        }
    }

    func updateCommands(canNext: Bool, canPrevious: Bool, canSeek: Bool) {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.canNext = canNext
            model.canPrevious = canPrevious
            model.canSeek = canSeek
            return
        }
#endif
        let commands = MPRemoteCommandCenter.shared()
        commands.nextTrackCommand.isEnabled = canNext
        commands.previousTrackCommand.isEnabled = canPrevious
        commands.changePlaybackPositionCommand.isEnabled = canSeek
    }

    func update(position: Double) {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.updatePosition(position)
            return
        }
#endif
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func updateRate(_ rate: Double, position: Double? = nil) {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.updateRate(rate, position: position)
            return
        }
#endif
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = rate
        if let position {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        }
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
#if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = rate > 0 ? .playing : .paused
#endif
    }


    func playbackDidStart() {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.playbackDidStart()
        }
#endif
        requestPrimaryIfPossible(reason: "playback")
    }

    func requestPrimaryIfPossible(reason: String = "foreground") {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.requestPrimaryIfPossible(reason: reason)
        }
#endif
    }

    func audioSessionUnavailable() {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.suspendClaims()
        }
#endif
    }

    func stop() {
#if os(iOS) && canImport(NowPlaying)
        if #available(iOS 27, *), let model = modern as? ModernNowPlaying {
            model.stop()


            resetModernModel()
            return
        }
#endif
        lastRequestedURL = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
#if os(macOS)
        MPNowPlayingInfoCenter.default().playbackState = .stopped
#endif
    }

    private func artworkFromBytes(_ data: Data?) -> MPMediaItemArtwork? {
        guard let data else { return nil }
#if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        let size = image.size
        let item = MPMediaItemArtwork(boundsSize: size) { _ in image }
        artwork = item
        return item
#else
        guard let image = NSImage(data: data) else { return nil }
        let size = image.size
        let item = MPMediaItemArtwork(boundsSize: size) { requested in
            let canvas = NSImage(size: requested)
            canvas.lockFocus()
            image.draw(in: NSRect(origin: .zero, size: requested))
            canvas.unlockFocus()
            return canvas
        }
        artwork = item
        return item
#endif
    }



    private func fetchArtwork(
        id: String, url: String, title: String, artist: String, duration: Double, isPlaying: Bool
    ) {
        lastRequestedURL = url
        let sized = SharedArtwork.sized(url, 544) ?? url
        let headers = LocalArtwork.isReference(sized) ? [:] : WebDavBridge.shared.playbackHeaders(fileUrl: sized)
        Task.detached(priority: .utility) { [weak self] in
            guard let data = await ArtworkRequests.shared.data(url: sized, headers: headers),
                  !data.isEmpty else { return }
            await MainActor.run { [weak self] in
                guard let self, self.metadataKey == id, self.lastRequestedURL == url else { return }
                guard let item = self.artworkFromBytes(data) else { return }
                self.artworkURL = url
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = item
                info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }
}

#if os(iOS) && canImport(NowPlaying)



@available(iOS 27, *)
@Observable
@MainActor
private final class ModernNowPlaying: MediaSessionRepresentable {
    let id = "nuvx-player-\(UUID().uuidString)"
    weak var delegate: NowPlayingController?
    var title = ""
    var artist = ""
    var duration: Double = 0
    @ObservationIgnored private var playback = NowPlayingPlaybackState()


    private(set) var playbackSnapshot: MediaPlaybackSnapshot?
    var position: Double { playback.position }
    var rate: Double { playback.rate }
    var preparing: Bool { playback.preparing }
    var artworkData: Data?
    var artworkURL: String?
    var canNext = false
    var canPrevious = false
    var canSeek = false
    var contentID: String?
    @ObservationIgnored private var lastSnapshotState: String?
    @ObservationIgnored private lazy var lifecycle = NowPlayingLifecycle(
        makeSession: { [weak self] in
            guard let self else { return nil }
            return ModernNowPlayingSession(MediaSession(self))
        },
        audioReady: { AudioSessionManager.isActive },


        foreground: { UIApplication.shared.applicationState != .background },
        log: { message in
            PlaybackDebugLog.shared.record(message)
            NSLog("[Nuvx] %@", message)
        }
    )

    var content: (any MediaContentRepresentable)? {
        guard let contentID else { return nil }
        let imageData = artworkData
        let imageURL = artworkURL
        let artwork: Artwork? = (imageData != nil || imageURL != nil)
            ? Artwork(id: "\(contentID)|\(imageURL ?? "embedded")") { _ in
                if let imageData { return try ArtworkRepresentation(data: imageData) }
                guard let imageURL
                else { throw ArtworkRepresentation.ArtworkRepresentationError.noRepresentationAvailable }
                let sized = SharedArtwork.sized(imageURL, 544) ?? imageURL
                let headers = LocalArtwork.isReference(sized) ? [:] : WebDavBridge.shared.playbackHeaders(fileUrl: sized)
                guard let data = await ArtworkRequests.shared.data(url: sized, headers: headers)
                else { throw ArtworkRepresentation.ArtworkRepresentationError.noRepresentationAvailable }
                return try ArtworkRepresentation(data: data)
            }
            : nil
        return MusicContent(
            id: contentID, songTitle: title, artistName: artist,
            albumName: "", type: .audio,
            duration: duration > 0 ? .finite(duration) : nil,
            artwork: artwork
        )
    }

    private func publishPlayback(_ value: NowPlayingPlaybackState) {
        playback = value
        guard contentID != nil else {
            playbackSnapshot = nil
            return
        }
        let state = value.preparing ? "buffering" : (value.rate > 0 ? "playing" : "paused")
        let snapshot = MediaPlaybackSnapshot(
            state: value.preparing ? .buffering : (value.rate > 0 ? .playing(rate: Float(value.rate)) : .paused),
            elapsedTime: value.position, timestamp: value.timestamp
        )
        if playbackSnapshot != snapshot { playbackSnapshot = snapshot }
        if lastSnapshotState != state {
            lastSnapshotState = state
            record("snapshot published state=\(state) position=\(value.position)")
        }
    }

    var commands: [MediaCommand] {
        return [
            .play { [weak self] in
                guard let self else { throw CancellationError() }
                try await self.performPlay()
            },
            .pause { [weak self] in try self?.perform("pause", action: self?.delegate?.onPause) },
            .next { [weak self] in try self?.perform("next", action: self?.delegate?.onNext) }.enabled(canNext),
            .previous { [weak self] in try self?.perform("previous", action: self?.delegate?.onPrevious) }.enabled(canPrevious),
            .seekToPosition { [weak self] seconds in
                guard let self else { throw CancellationError() }
                try self.perform("seek position=\(seconds)", action: self.delegate?.onSeek.map { seek in { seek(seconds) } })
            }.enabled(canSeek),
        ]
    }

    private func performPlay() async throws {
        guard let contentID, let action = delegate?.onPlayAsync else {
            throw CancellationError()
        }
        record("command received play")
        do {
            try await NowPlayingCommandCompletion.perform(action: action) {
                self.contentID == contentID && self.rate > 0 && !self.preparing
            }
            record("command completed play")
        } catch {
            record("command failed play: \(error)")
            throw error
        }
    }

    private func perform(_ command: String, action: (() -> Void)?) throws {
        guard contentID != nil, let action else {
            record("command rejected \(command): inactive session or missing handler")
            throw CancellationError()
        }
        record("command received \(command)")
        action()
        record("command dispatched \(command)")
    }

    private func record(_ event: String) {
        let message = "Now Playing \(event) session=\(id) content=\(contentID ?? "none") "
            + "rate=\(rate) preparing=\(preparing) appState=\(UIApplication.shared.applicationState.rawValue)"
        PlaybackDebugLog.shared.record(message)
        NSLog("[Nuvx] %@", message)
    }

    func prepare(id: String, title: String, artist: String, duration: Double,
                 artworkData: Data?, thumbnailUrl: String?, position: Double,
                 canNext: Bool, canPrevious: Bool, canSeek: Bool) {
        update(id: id, title: title, artist: artist, duration: duration,
               artworkData: artworkData, thumbnailUrl: thumbnailUrl,
               isPlaying: false, position: position)
        self.canNext = canNext
        self.canPrevious = canPrevious
        self.canSeek = canSeek


        publishPlayback(playback.updating(preparing: !lifecycle.hasSession))
        lifecycle.prepare()
    }

    func playbackDidStart() {
        publishPlayback(playback.updating(preparing: false))
        lifecycle.update(contentID: contentID, playing: rate > 0)
        record("audio playback started")
    }

    func update(id: String, title: String, artist: String, duration: Double,
                artworkData: Data?, thumbnailUrl: String?, isPlaying: Bool,
                position: Double?) {
        let elapsed = position ?? (contentID == id ? nil : 0)
        contentID = id
        self.title = title
        self.artist = artist
        self.duration = duration
        self.artworkData = artworkData
        self.artworkURL = thumbnailUrl
        let speed = Double(PlatformSettings.shared.getFloat(key: "playback_speed", default: 1))
        updateRate(isPlaying ? speed : 0, position: elapsed)
    }

    func updateRate(_ rate: Double, position: Double? = nil) {
        let validRate = rate.isFinite ? max(0, rate) : 0


        publishPlayback(playback.updating(rate: validRate, position: position))
        lifecycle.update(contentID: contentID, playing: self.rate > 0)
    }

    func updatePosition(_ position: Double) {
        publishPlayback(playback.updating(position: position))
    }

    func requestPrimaryIfPossible(reason: String) {
        lifecycle.request(reason: reason)
    }

    func suspendClaims() {
        lifecycle.suspend()
    }

    func stop() {
        lifecycle.stop()
        playback = NowPlayingPlaybackState()
        playbackSnapshot = nil
        title = ""
        contentID = nil
    }

#if DEBUG


    static func verifyObservation() -> [String: Bool] {
        let model = ModernNowPlaying()
        model.update(id: "observation-fixture", title: "Fixture", artist: "Fixture", duration: 40,
                     artworkData: nil, thumbnailUrl: nil, isPlaying: true, position: 2)
        let snapshotChanged = Mutex(false)
        let commandsChanged = Mutex(false)
        withObservationTracking { _ = model.playbackSnapshot } onChange: {
            snapshotChanged.withLock { $0 = true }
        }
        withObservationTracking { _ = model.commands } onChange: {
            commandsChanged.withLock { $0 = true }
        }
        model.updateRate(0, position: 2)
        var checks = [
            "observation.pauseSnapshot": snapshotChanged.withLock { $0 },
            "observation.pauseKeepsCommands": !commandsChanged.withLock { $0 },
            "observation.pausedValue": model.playbackSnapshot == MediaPlaybackSnapshot(
                state: .paused, elapsedTime: 2, timestamp: model.playback.timestamp)
        ]
        let positionChangedCommands = Mutex(false)
        withObservationTracking { _ = model.commands } onChange: {
            positionChangedCommands.withLock { $0 = true }
        }
        model.updatePosition(3)
        checks["observation.positionKeepsCommands"] = !positionChangedCommands.withLock { $0 }
        let resumedSnapshot = Mutex(false)
        withObservationTracking { _ = model.playbackSnapshot } onChange: {
            resumedSnapshot.withLock { $0 = true }
        }
        model.updateRate(1, position: 3)
        checks["observation.resumeSnapshot"] = resumedSnapshot.withLock { $0 }
        checks["observation.playingValue"] = model.playbackSnapshot == MediaPlaybackSnapshot(
            state: .playing(), elapsedTime: 3, timestamp: model.playback.timestamp)
        checks["observation.noPublication"] = !model.lifecycle.hasSession
        return checks
    }
#endif
}


@available(iOS 27, *)
@MainActor
private final class ModernNowPlayingSession: NowPlayingSessionDriver {
    private let session: MediaSession<ModernNowPlaying>
    private var observing = false
    private var lastEligibility = false
    private var lastApplicationPrimary = false
    private var eligibilityChanged: (@MainActor () -> Void)?

    init(_ session: MediaSession<ModernNowPlaying>) { self.session = session }
    var canBecomeApplicationPrimary: Bool { session.canBecomeApplicationPrimary }
    var isApplicationPrimary: Bool { session.isApplicationPrimary }
    var isSystemPrimary: Bool { session.isSystemPrimary }
    func publish() async throws { try await session.requestToBecomeApplicationPrimary() }
    func promote() async throws { try await session.requestToBecomeSystemPrimary() }

    func classify(_ error: Error) -> NowPlayingClaimFailure {
        switch error as? MediaSessionError {
        case .sessionInvalidated: return .invalidated
        case .invalidState: return .ineligible
        default: return .transient
        }
    }

    func observeEligibility(_ changed: @escaping @MainActor () -> Void) {
        observing = true
        lastEligibility = canBecomeApplicationPrimary
        lastApplicationPrimary = isApplicationPrimary
        eligibilityChanged = changed
        observe()
    }

    func stopObserving() {
        observing = false
        eligibilityChanged = nil
    }

    private func observe() {
        guard observing else { return }
        withObservationTracking {
            _ = session.canBecomeApplicationPrimary
            _ = session.isApplicationPrimary
            _ = session.isSystemPrimary
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.observing else { return }
                let eligible = self.canBecomeApplicationPrimary
                let applicationPrimary = self.isApplicationPrimary
                let message = "Now Playing status session=\(self.session.id) eligible=\(eligible) "
                    + "applicationPrimary=\(applicationPrimary) systemPrimary=\(self.isSystemPrimary)"
                PlaybackDebugLog.shared.record(message)
                NSLog("[Nuvx] %@", message)



                let needsPublication = eligible && (!self.lastEligibility
                    || (self.lastApplicationPrimary && !applicationPrimary))
                self.lastEligibility = eligible
                self.lastApplicationPrimary = applicationPrimary
                self.observe()
                if needsPublication { self.eligibilityChanged?() }
            }
        }
    }
}
#endif







@MainActor
final class WidgetStatePublisher {
    func publish(entry: QueueEntry?, isPlaying: Bool, canNext: Bool, canPrevious: Bool) {
        guard let entry else { return }
        let group = "group.com.bxane.nuvx"


        guard FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: group
        ) != nil else { return }
        let title = entry.title
        let artist = entry.artist
        let artwork = entry.artworkData
        let artworkURL = Self.artworkFileURL()
        DispatchQueue.global(qos: .utility).async {
            guard let defaults = UserDefaults(suiteName: group) else { return }
            defaults.set(title, forKey: "widget.title")
            defaults.set(artist, forKey: "widget.artist")
            defaults.set(isPlaying, forKey: "widget.playing")
            defaults.set(canNext, forKey: "widget.canNext")
            defaults.set(canPrevious, forKey: "widget.canPrevious")
            if let artwork {
                if let url = artworkURL {
                    try? artwork.write(to: url, options: .atomic)
                    defaults.set(url.path, forKey: "widget.artworkPath")
                }
            } else {
                defaults.removeObject(forKey: "widget.artworkPath")
            }
        }
    }

    nonisolated private static func artworkFileURL() -> URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.com.bxane.nuvx"
        ) else { return nil }
        return container.appendingPathComponent("widget-artwork.jpg")
    }
}
