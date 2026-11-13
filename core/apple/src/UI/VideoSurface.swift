import AVFoundation
import AVKit
import Observation
import SwiftUI
import NuvxShared









@MainActor
@Observable
final class VideoPlayback: NSObject, AVPictureInPictureControllerDelegate {
    static let shared = VideoPlayback()

    private(set) var side: ResolvedVideoSide?


    private(set) var preparing = false
    let controller = VideoSideController(clock: VideoClock(position: { 0 }, isPlaying: { false }))

    @ObservationIgnored private var gate = VideoSurfaceState()
    @ObservationIgnored private var mountedId: String?
    @ObservationIgnored private var resolvedId: String?
    @ObservationIgnored private var retriedId: String?
    @ObservationIgnored private var resolveTask: Task<Void, Never>?
    @ObservationIgnored private var lastGateLog: String?
    @ObservationIgnored private var aheadTask: Task<Void, Never>?
    @ObservationIgnored private var aheadId: String?
    private struct PreparedVideo {
        let songId: String
        let side: ResolvedVideoSide
        let map: VideoVersionAlignment.Map?
        let saved: Date
    }
    @ObservationIgnored private var preparedAhead: PreparedVideo?

    private override init() { super.init() }

    var player: AVPlayer { controller.player }
    var state: VideoSideController.State { controller.state }

    var showsVideo: Bool { side != nil && controller.state == .ready }


    var isLoading: Bool { preparing || (side != nil && controller.state == .loading) }

    private(set) var notice: String?
    @ObservationIgnored private var noticeTask: Task<Void, Never>?
    @ObservationIgnored private var pictureInPictureController: AVPictureInPictureController?
    @ObservationIgnored private weak var pictureInPictureLayer: AVPlayerLayer?
    private(set) var pictureInPictureActive = false
    @ObservationIgnored private var networkAllowed = true

    private func say(_ text: String?) {
        noticeTask?.cancel()
        notice = text
        guard text != nil else { return }
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
    }





    struct PlayerHooks {
        var track: () -> (title: String, artist: String, seconds: Double, durationText: String?)?
        var playVideoVersion: (_ videoId: String, _ durationText: String?) -> Void
        var playSongVersion: () -> Void
        var next: () -> (videoId: String, title: String, artist: String, seconds: Double, durationText: String?)? = { nil }
    }

    @ObservationIgnored private var hooks: PlayerHooks?



    func configure(clock: VideoClock, hooks: PlayerHooks) {
        controller.setClock(clock)
        self.hooks = hooks
    }

    func setEnabled(_ on: Bool) {
        guard gate.enabled != on else { return }
        PlaybackDebugLog.shared.record("video: switch \(on ? "on" : "off")", about: gate.trackId)
        gate.setEnabled(on)
        if on { UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastUsedKey) }

        if !on {
            hooks?.playSongVersion()
            pictureInPictureController?.stopPictureInPicture()
        }
        reconcile()
    }


    func setSurfaceVisible(_ visible: Bool, videoId: String?) {
        if visible {
            gate.surfaceAppeared(videoId: videoId)
        } else {
            gate.surfaceDisappeared()
        }
        reconcile()
    }

    func setTrack(_ videoId: String?) {
        gate.setTrack(videoId)
        reconcile()
    }


    func setActive(_ active: Bool) {
        gate.setActive(active)
        reconcile()
    }

    func refreshNetworkPolicy() {
        let allowed = !NetworkQuality.shared.metered ||
            PlatformSettings.shared.getBoolean(key: "video_over_cellular", default: false)
        guard networkAllowed != allowed else { return }
        networkAllowed = allowed
        if !allowed { pictureInPictureController?.stopPictureInPicture() }
        reconcile()
    }

    var pictureInPictureSupported: Bool {
        AVPictureInPictureController.isPictureInPictureSupported()
    }

    var canStartPictureInPicture: Bool {
        pictureInPictureSupported && pictureInPictureController?.isPictureInPicturePossible == true
    }

    func registerPictureInPictureLayer(_ layer: AVPlayerLayer) {
        guard pictureInPictureSupported else { return }


        guard pictureInPictureLayer == nil else { return }
        guard let pip = AVPictureInPictureController(playerLayer: layer) else { return }
        pip.delegate = self
        pictureInPictureLayer = layer
        pictureInPictureController = pip
    }

    func unregisterPictureInPictureLayer(_ layer: AVPlayerLayer) {
        guard pictureInPictureLayer === layer else { return }
        pictureInPictureController?.stopPictureInPicture()
        pictureInPictureController?.delegate = nil
        pictureInPictureController = nil
        pictureInPictureLayer = nil
    }

    func startPictureInPicture() {
        guard canStartPictureInPicture else { return }
        pictureInPictureController?.startPictureInPicture()
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        Task { @MainActor [weak self] in
            self?.didStartPictureInPicture()
        }
    }

    @MainActor
    private func didStartPictureInPicture() {
        pictureInPictureActive = true
        gate.setPictureInPictureActive(true)
        reconcile()
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        Task { @MainActor [weak self] in
            self?.didStopPictureInPicture()
        }
    }

    @MainActor
    private func didStopPictureInPicture() {
        pictureInPictureActive = false
        gate.setPictureInPictureActive(false)
        reconcile()
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: Error
    ) {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in
            self?.didFailToStartPictureInPicture(message: message)
        }
    }

    @MainActor
    private func didFailToStartPictureInPicture(message: String) {
        PlaybackDebugLog.shared.record("video: PiP failed to start: \(message)", about: gate.trackId)
    }




    private func reconcile() {
        if gate.enabled {
            let state = "surfaces=\(gate.surfaces) active=\(gate.active) track=\(gate.trackId ?? "none") mounted=\(mountedId ?? "none")"
            if state != lastGateLog {
                lastGateLog = state
                PlaybackDebugLog.shared.record("video gate: \(state)", about: gate.trackId)
            }
        }
        guard networkAllowed, gate.canRequest, let id = gate.trackId else {
            stop()
            warmWhileOff()
            return
        }
        if mountedId == id, resolvedId == id { return }
        if mountedId == id, resolveTask != nil { return }
        start(id)
    }

    private func start(_ id: String, forceRefresh: Bool = false) {
        if mountedId != id { retriedId = nil }
        resolveTask?.cancel()
        resolveTask = nil
        mountedId = id
        resolvedId = nil
        side = nil
        preparing = true
        say(nil)
        controller.detach()

        if !forceRefresh, let prepared = preparedAhead, prepared.songId == id,
           Date().timeIntervalSince(prepared.saved) < Self.preparedLifetime {
            preparedAhead = nil
            preparing = false
            resolvedId = id
            side = prepared.side
            controller.attach(prepared.side, timeMap: prepared.map)
            PlaybackDebugLog.shared.record("video: using prepared video choice and alignment", about: id)
            prepareAhead()
            return
        }
        preparedAhead = nil

        let requestedTrack = hooks?.track()
        PlaybackDebugLog.shared.record(
            "video: resolving song=\(requestedTrack?.title ?? id) artist=\(requestedTrack?.artist ?? "") (forceRefresh=\(forceRefresh))",
            about: id
        )
        resolveTask = Task { [weak self] in
            guard let self else { return }
            do {
                let side = try await InnertubeStreamResolver.shared.resolveVideo(videoId: id, forceRefresh: forceRefresh)
                guard !Task.isCancelled, self.mountedId == id, self.gate.canRequest else { return }
                self.resolvedId = id
                self.resolveTask = nil


                let sameFileIsPlayable = side.isAuthoritativeMusicVideo
                    ? side.qualifiesAsCounterpartVideo()
                    : side.qualifiesAsSearchVideo(isOfficialMusicVideo: false)
                if sameFileIsPlayable {
                    PlaybackDebugLog.shared.record("video: attaching the picture", about: id)
                    self.preparing = false
                    self.side = side
                    self.controller.attach(side)
                    self.prepareAhead()
                    return
                }



                guard let track = self.hooks?.track() else { self.preparing = false; return }
                let candidates = await Self.findVideoVersions(videoId: id, track: track)
                guard !Task.isCancelled, self.mountedId == id, self.gate.canRequest else { return }
                var fallback: VideoVersion?
                for candidate in candidates.prefix(3) {
                    let videoSide = try await InnertubeStreamResolver.shared.resolveVideo(videoId: candidate.videoId)
                    guard !Task.isCancelled, self.mountedId == id, self.gate.canRequest else { return }
                    let isCounterpart = candidate.source == "counterpart"
                    let qualifies = isCounterpart
                        ? videoSide.qualifiesAsCounterpartVideo()
                        : videoSide.qualifiesAsSearchVideo(
                            isOfficialMusicVideo: candidate.type == "MUSIC_VIDEO_TYPE_OMV"
                        )
                    guard qualifies else {
                        if isCounterpart {
                            PlaybackDebugLog.shared.record(
                                "video: counterpart \(candidate.videoId) rejected (AVC/MP4=\(videoSide.isAVCInMP4), bitrate=\(videoSide.bitrate.map { String($0) } ?? "?") kbps, \(videoSide.kbpsPerMegapixel.map { String(format: "%.0f", $0) } ?? "?") kbps/MP)",
                                about: id
                            )
                        }
                        PlaybackDebugLog.shared.record(
                            "video: \(candidate.videoId) (\(candidate.type ?? "untyped")) is not moving picture; next", about: id
                        )
                        continue
                    }
                    if fallback == nil { fallback = candidate }
                    PlaybackDebugLog.shared.record(
                        "video: aligning \(requestedTrack?.title ?? id) by \(requestedTrack?.artist ?? "") with \(candidate.videoId) (\(candidate.type ?? "untyped"), \(candidate.durationText ?? "?"), source=\(candidate.source ?? "search"))",
                        about: id
                    )



                    self.controller.attach(videoSide)
                    let map = await VideoVersionAlignment.shared.map(songId: id, videoId: candidate.videoId)
                    guard !Task.isCancelled, self.mountedId == id, self.gate.canRequest else { return }
                    if let map, map.confident {
                        PlaybackDebugLog.shared.record(
                            "video: picture of \(candidate.videoId) for \(requestedTrack?.title ?? id) by \(requestedTrack?.artist ?? "") aligned: \(map.summary) min-window=\(String(format: "%.3f", map.minimumWindowSimilarity))",
                            about: id
                        )
                        self.preparing = false
                        self.side = videoSide
                        self.controller.attach(videoSide, timeMap: map)
                        self.prepareAhead()
                        return
                    }
                    self.controller.detach()
                }
                self.preparing = false
                guard let fallback else {
                    PlaybackDebugLog.shared.record("video: no music video for this song", about: id)
                    self.say("No music video for this song")
                    self.prepareAhead()
                    self.side = nil
                    self.controller.detach()
                    return
                }




                PlaybackDebugLog.shared.record(
                    "video: \(fallback.videoId) does not align with the song; playing the music video itself", about: id
                )
                self.hooks?.playVideoVersion(fallback.videoId, fallback.durationText)
            } catch {
                PlaybackDebugLog.shared.record("video: resolve failed: \(error)", about: id)
                guard !Task.isCancelled, self.mountedId == id else { return }
                self.preparing = false
                self.resolvedId = id
                self.resolveTask = nil
                self.side = nil
                self.controller.detach()
                self.say("The music video couldn’t be loaded")
            }
        }
    }



    func videoFailed() {
        PlaybackDebugLog.shared.record("video: the player failed", about: gate.trackId)
        guard gate.canRequest, let id = gate.trackId,
              mountedId == id, resolvedId == id, retriedId != id else { return }
        retriedId = id
        start(id, forceRefresh: true)
    }




    private func prepareAhead() {
        guard gate.canRequest, let next = hooks?.next() else { return }

        prepare(next, delay: 20)
    }



    private static let preparedLifetime: TimeInterval = 20 * 60
    private static let lastUsedKey = "VideoSwitchLastUsedAt"







    private func warmWhileOff() {
        guard !gate.canRequest, networkAllowed, gate.active,
              let id = gate.trackId, !id.isEmpty,
              preparedAhead?.songId != id else { return }
        let lastUsed = UserDefaults.standard.double(forKey: Self.lastUsedKey)
        guard lastUsed > 0, Date().timeIntervalSince1970 - lastUsed < 14 * 24 * 3600 else { return }


        prepare((videoId: id, title: "", artist: "", seconds: 0, durationText: nil), delay: 15)
    }

    private func prepare(
        _ next: (videoId: String, title: String, artist: String, seconds: Double, durationText: String?),
        delay: Double
    ) {
        guard next.videoId != aheadId else { return }
        aheadId = next.videoId
        aheadTask?.cancel()
        aheadTask = Task(priority: .utility) { [weak self] in
            var next = next
            if delay > 0 {
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                if next.title.isEmpty {
                    guard let self, self.gate.trackId == next.videoId,
                          let track = self.hooks?.track() else { return }
                    next = (videoId: next.videoId, title: track.title, artist: track.artist,
                            seconds: track.seconds, durationText: track.durationText)
                }
            }
            let own = try? await InnertubeStreamResolver.shared.resolveVideo(videoId: next.videoId)
            if let own {
                let qualifies = own.isAuthoritativeMusicVideo
                    ? own.qualifiesAsCounterpartVideo()
                    : own.qualifiesAsSearchVideo(isOfficialMusicVideo: false)
                if qualifies {
                    await self?.rememberPrepared(songId: next.videoId, side: own, map: nil)
                    return
                }
            }
            let track = (title: next.title, artist: next.artist, seconds: next.seconds, durationText: next.durationText)
            for candidate in await Self.findVideoVersions(videoId: next.videoId, track: track).prefix(3) {
                guard !Task.isCancelled else { return }
                guard let side = try? await InnertubeStreamResolver.shared.resolveVideo(videoId: candidate.videoId) else { continue }
                let qualifies = candidate.source == "counterpart"
                    ? side.qualifiesAsCounterpartVideo()
                    : side.qualifiesAsSearchVideo(
                        isOfficialMusicVideo: candidate.type == "MUSIC_VIDEO_TYPE_OMV"
                    )
                guard qualifies else { continue }
                let map = await VideoVersionAlignment.shared.map(songId: next.videoId, videoId: candidate.videoId)
                PlaybackDebugLog.shared.record(
                    "video: lined up \(next.title) by \(next.artist) ahead with \(candidate.videoId): \(map?.confident == true ? "aligned" : "not aligned") map=\(map?.summary ?? "none") min-window=\(map.map { String(format: "%.3f", $0.minimumWindowSimilarity) } ?? "?")",
                    about: next.videoId
                )
                if map?.confident == true {
                    await self?.rememberPrepared(songId: next.videoId, side: side, map: map)
                    return
                }
            }
        }
    }

    private func rememberPrepared(songId: String, side: ResolvedVideoSide, map: VideoVersionAlignment.Map?) async {
        guard !Task.isCancelled, networkAllowed, aheadId == songId else { return }
        preparedAhead = PreparedVideo(songId: songId, side: side, map: map, saved: Date())


        if let string = side.url, let url = URL(string: string) {
            _ = try? await VideoIndexCache.shared.index(source: url, headers: side.headers)
        }
    }

    private func stop() {


        if aheadId != gate.trackId || gate.trackId == nil {
            aheadTask?.cancel()
            aheadTask = nil
            aheadId = nil
        }
        if preparedAhead?.songId != gate.trackId { preparedAhead = nil }
        resolveTask?.cancel()
        resolveTask = nil
        mountedId = nil
        resolvedId = nil
        side = nil
        preparing = false
        say(nil)
        controller.detach()
    }

    private struct VideoVersion: Decodable {
        let videoId: String
        let title: String
        let durationText: String?
        let type: String?
        let source: String?
    }

    private static func findVideoVersions(
        videoId: String, track: (title: String, artist: String, seconds: Double, durationText: String?)
    ) async -> [VideoVersion] {
        await withCheckedContinuation { continuation in
            VideoVersionBridge.shared.find(
                videoId: videoId, title: track.title, artist: track.artist,
                durationText: track.durationText ?? (track.seconds > 0 ? QueueEntry.formatDuration(track.seconds) : nil),
                callback: VideoVersionAdapter { json, message in
                    if let message {
                        PlaybackDebugLog.shared.record("video: music-video search failed: \(message)", about: videoId)
                    }
                    let found = json.flatMap { try? JSONDecoder().decode([VideoVersion].self, from: Data($0.utf8)) } ?? []
                    continuation.resume(returning: found)
                }
            )
        }
    }
}

private final class VideoVersionAdapter: VideoVersionBridgeVideoVersionCallback {
    private let handler: (String?, String?) -> Void
    init(_ handler: @escaping (String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, message: String?) { handler(json, message) }
}





struct VideoSurface<Fallback: View>: View {
    var cornerRadius: CGFloat = 12


    var fillSlot = true


    var announcesLoading = false
    var presentation: Presentation = .letterbox
    @ViewBuilder var fallback: () -> Fallback

    enum Presentation {


        case letterbox


        case fill


        case card
    }

    @Environment(PlaybackController.self) private var controller
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("show_music_videos") private var showMusicVideos = false
    @AppStorage("video_over_cellular") private var videoOverCellular = false
    @State private var registered = false
    @State private var theaterPresented = false

    private func caption(_ text: String, loading: Bool) -> some View {
        VStack {
            Spacer()
            HStack(spacing: 6) {
                if loading {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "video.slash")
                        .font(.caption)
                }
                Text(text)
                    .font(.caption.weight(.medium))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(10)
        }
        .allowsHitTesting(false)
        .transition(.opacity)
    }

    @ViewBuilder
    private var picture: some View {
        let player = VideoPlayback.shared.player

        let aspect = VideoPlayback.shared.side?.aspectRatio ?? 16.0 / 9.0
        switch presentation {
        case .fill:
            VideoLayerView(player: player, gravity: .resizeAspectFill)
                .allowsHitTesting(false)
                .transition(.opacity)
        case .card:
            VideoLayerView(player: player, gravity: .resizeAspectFill)
                .aspectRatio(aspect, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .shadow(color: .black.opacity(0.35), radius: 20, y: 10)
                .allowsHitTesting(false)
                .transition(.opacity)
        case .letterbox:
            if fillSlot { Color.black }
            VideoLayerView(player: player)
                .aspectRatio(aspect, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .allowsHitTesting(false)
                .transition(.opacity)
        }
    }

    var body: some View {
        ZStack {
            fallback()


                .opacity(presentation == .card && VideoPlayback.shared.showsVideo ? 0 : 1)
            if VideoPlayback.shared.showsVideo {
                picture
                    .overlay(alignment: presentation == .fill ? .topTrailing : .bottomTrailing) {
                        HStack(spacing: 8) {
                            Button {
                                theaterPresented = true
                            } label: {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .accessibilityLabel("Open video theater")
                            }
                            .help("Open Video Theater")
                            if VideoPlayback.shared.pictureInPictureSupported {
                                Button {
                                    VideoPlayback.shared.startPictureInPicture()
                                } label: {
                                    Image(systemName: "pip.enter")
                                        .accessibilityLabel("Start Picture in Picture")
                                }
                                .help("Picture in Picture")
                                .disabled(!VideoPlayback.shared.canStartPictureInPicture)
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .padding(10)
                    }
            } else if announcesLoading, showMusicVideos, VideoPlayback.shared.isLoading {
                caption("Loading music video", loading: true)
                    .accessibilityIdentifier("player.musicVideoLoading")
            } else if announcesLoading, showMusicVideos, let notice = VideoPlayback.shared.notice {
                caption(notice, loading: false)
                    .accessibilityIdentifier("player.musicVideoNotice")
            }
        }
        .animation(.easeInOut(duration: 0.25), value: VideoPlayback.shared.showsVideo)
        .animation(.easeInOut(duration: 0.25), value: VideoPlayback.shared.isLoading)
        .animation(.easeInOut(duration: 0.25), value: VideoPlayback.shared.notice)
        #if os(iOS)
        .fullScreenCover(isPresented: $theaterPresented) {
            VideoTheaterView(controller: controller)
        }
        #else
        .sheet(isPresented: $theaterPresented) {
            VideoTheaterView(controller: controller)
                .frame(minWidth: 900, minHeight: 560)
        }
        #endif
        .onAppear {
            guard !registered else { return }
            registered = true
            VideoPlayback.shared.configure(
                clock: VideoClock(
                    position: { controller.livePosition(at: Date()) },
                    isPlaying: { controller.isPlaying },
                    rate: { controller.effectivePlaybackRate }
                ),
                hooks: .init(
                    track: { [controller] in
                        guard let entry = controller.current else { return nil }
                        let seconds = controller.duration > 0 ? controller.duration : entry.durationSeconds
                        return (entry.title, entry.artist, seconds, entry.durationText)
                    },
                    playVideoVersion: { [controller] id, durationText in
                        controller.playVideoVersion(videoId: id, durationText: durationText)
                    },
                    playSongVersion: { [controller] in controller.playSongVersion() },
                    next: { [controller] in
                        guard let entry = controller.upNextEntry, let videoId = entry.videoId else { return nil }
                        return (videoId, entry.title, entry.artist, entry.durationSeconds, entry.durationText)
                    }
                )
            )
            VideoPlayback.shared.setActive(scenePhase == .active)
            VideoPlayback.shared.refreshNetworkPolicy()
            VideoPlayback.shared.setEnabled(showMusicVideos)
            VideoPlayback.shared.setSurfaceVisible(true, videoId: controller.current?.videoId)
        }
        .onDisappear {
            guard registered else { return }
            registered = false
            VideoPlayback.shared.setSurfaceVisible(false, videoId: nil)
        }
        .onChange(of: controller.current?.videoId) { _, id in
            guard registered else { return }
            VideoPlayback.shared.setTrack(id)
        }
        .onChange(of: scenePhase) { _, phase in
            guard registered else { return }
            VideoPlayback.shared.setActive(phase == .active)
        }
        .onChange(of: showMusicVideos) { _, on in
            guard registered else { return }
            VideoPlayback.shared.setEnabled(on)
        }
        .onChange(of: NetworkQuality.shared.metered) { _, _ in
            VideoPlayback.shared.refreshNetworkPolicy()
        }
        .onChange(of: videoOverCellular) { _, _ in
            VideoPlayback.shared.refreshNetworkPolicy()
        }
        .onChange(of: VideoPlayback.shared.state) { _, state in
            guard registered, state == .failed else { return }
            VideoPlayback.shared.videoFailed()
        }
    }
}

#if os(iOS)
struct VideoLayerView: UIViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect
    func makeUIView(context: Context) -> VideoLayerHost {
        let view = VideoLayerHost()
        view.player = player
        view.gravity = gravity
        VideoPlayback.shared.registerPictureInPictureLayer(view.playerLayer)
        return view
    }
    func updateUIView(_ view: VideoLayerHost, context: Context) {
        view.player = player
        view.gravity = gravity
        VideoPlayback.shared.registerPictureInPictureLayer(view.playerLayer)
    }
    static func dismantleUIView(_ view: VideoLayerHost, coordinator: ()) {
        VideoPlayback.shared.unregisterPictureInPictureLayer(view.playerLayer)
    }
}

final class VideoLayerHost: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    var player: AVPlayer? {
        get { playerLayer.player }
        set { playerLayer.player = newValue }
    }
    var gravity: AVLayerVideoGravity {
        get { playerLayer.videoGravity }
        set { playerLayer.videoGravity = newValue }
    }
}
#else
struct VideoLayerView: NSViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect
    func makeNSView(context: Context) -> VideoLayerHost {
        let view = VideoLayerHost()
        view.player = player
        view.gravity = gravity
        VideoPlayback.shared.registerPictureInPictureLayer(view.playerLayer)
        return view
    }
    func updateNSView(_ view: VideoLayerHost, context: Context) {
        view.player = player
        view.gravity = gravity
        VideoPlayback.shared.registerPictureInPictureLayer(view.playerLayer)
    }
    static func dismantleNSView(_ view: VideoLayerHost, coordinator: ()) {
        VideoPlayback.shared.unregisterPictureInPictureLayer(view.playerLayer)
    }
}

final class VideoLayerHost: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        playerLayer.videoGravity = .resizeAspect
        layer?.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layout() {
        super.layout()
        playerLayer.frame = bounds
    }

    var player: AVPlayer? {
        get { playerLayer.player }
        set { playerLayer.player = newValue }
    }
    var gravity: AVLayerVideoGravity {
        get { playerLayer.videoGravity }
        set { playerLayer.videoGravity = newValue }
    }
}
#endif
