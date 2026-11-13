import AVFoundation
import Foundation
import Observation
import QuartzCore




struct VideoClock {
    var position: @MainActor () -> Double
    var isPlaying: @MainActor () -> Bool

    var rate: @MainActor () -> Double = { 1 }
}













@MainActor
@Observable
final class VideoSideController {
    enum State: Equatable {
        case idle
        case loading
        case ready

        case failed
    }

    private(set) var state: State = .idle

    private(set) var side: ResolvedVideoSide?


    @ObservationIgnored private var timeMap: VideoVersionAlignment.Map?

    @ObservationIgnored let player = AVPlayer()
    @ObservationIgnored private var clock: VideoClock
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?
    @ObservationIgnored private var failedObserver: NSObjectProtocol?

    @ObservationIgnored private var loader: ChunkedVideoLoader?
    @ObservationIgnored private var isSeeking = false
    @ObservationIgnored private var lastSeekAt: CFTimeInterval = 0
    @ObservationIgnored private var seekGeneration: UInt64 = 0
    @ObservationIgnored private var pausedSyncTask: Task<Void, Never>?


    @ObservationIgnored private var seekLead: Double = 0.4
    @ObservationIgnored private var lastStatusLog: CFTimeInterval = 0


    init(clock: VideoClock) {
        self.clock = clock
        player.isMuted = true
        player.volume = 0
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.audiovisualBackgroundPlaybackPolicy = .pauses


        player.allowsExternalPlayback = false
        player.actionAtItemEnd = .pause


        player.automaticallyWaitsToMinimizeStalling = false
    }

    deinit {
        timer?.invalidate()
        pausedSyncTask?.cancel()
        if let failedObserver {
            NotificationCenter.default.removeObserver(failedObserver)
        }
    }



    func setClock(_ clock: VideoClock) {
        self.clock = clock
        clockChanged()
    }



    func clockChanged() {
        tick()
    }




    func attach(_ side: ResolvedVideoSide?, timeMap: VideoVersionAlignment.Map? = nil) {
        guard let side, let url = side.url.flatMap(URL.init(string:)) else {
            detach()
            return
        }
        self.timeMap = timeMap
        if self.side?.url == side.url, state == .failed {


            self.side = side
            return
        }
        if self.side?.url == side.url, player.currentItem != nil {


            self.side = side
            tick()
            return
        }
        tearDownItem()
        self.side = side
        state = .loading
        load(side, url: url, segmented: true)
    }





    private func load(_ side: ResolvedVideoSide, url: URL, segmented: Bool) {


        let loader = ChunkedVideoLoader(source: url, headers: side.headers)
        let options: [String: Any] = side.headers.isEmpty ? [:] : ["AVURLAssetHTTPHeaderFieldsKey": side.headers]
        let asset: AVURLAsset
        if segmented {
            asset = AVURLAsset(url: ChunkedVideoLoader.playlistURL, options: options)
        } else if let chunked = loader.assetURL {
            asset = AVURLAsset(url: chunked)
        } else {
            asset = AVURLAsset(url: url, options: options)
        }
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        self.loader = loader
        let item = AVPlayerItem(asset: asset)
        let attachedAt = CACurrentMediaTime()
        PlaybackDebugLog.shared.record(
            "video sync: loading \(side.width ?? 0)x\(side.height ?? 0) itag \(side.itag ?? 0) via \(side.clientName) "
                + "\(segmented ? "segmented" : "whole"), "
                + "headers \(side.headers.keys.sorted().joined(separator: ",")), throttled=\(side.hasNParameter)"
        )


        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    PlaybackDebugLog.shared.record(String(
                        format: "video sync: ready in %.1fs", CACurrentMediaTime() - attachedAt
                    ))
                    self.state = .ready
                    self.lastSeekAt = 0
                    self.tick()
                case .failed:
                    PlaybackDebugLog.shared.record(
                        "video sync: the item failed: \(item.error.map { String(describing: $0) } ?? "no error")"
                    )
                    if segmented {
                        self.tearDownItem()
                        self.load(side, url: url, segmented: false)
                    } else {
                        self.fail()
                    }
                default:
                    break
                }
            }
        }
        failedObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === item else { return }
                self.fail()
            }
        }
        player.replaceCurrentItem(with: item)
        tick()
    }


    func detach() {
        tearDownItem()
        side = nil
        timeMap = nil
        state = .idle
    }



    func tick() {
        pausedSyncTask?.cancel()
        pausedSyncTask = nil
        updateTimer()
        if state != .idle, CACurrentMediaTime() - lastStatusLog >= 2,
           state != .ready || isSeeking || player.currentItem?.status != .readyToPlay {
            lastStatusLog = CACurrentMediaTime()
            PlaybackDebugLog.shared.record(
                "video sync: held (state=\(state) item=\(player.currentItem?.status.rawValue ?? -1) seeking=\(isSeeking))"
            )
        }
        guard state == .ready, let item = player.currentItem, item.status == .readyToPlay, !isSeeking else {
            return
        }





        let songTime = clock.position()
        let engine = timeMap.map { $0.videoTime(at: songTime) } ?? songTime
        let video = player.currentTime().seconds
        guard engine.isFinite, video.isFinite else { return }
        let now = CACurrentMediaTime()
        if now - lastStatusLog >= 2 {
            lastStatusLog = now
            PlaybackDebugLog.shared.record(String(
                format: "video sync: engine %.2f video %.2f playing=%@ rate %.2f control=%d waiting=%@ lead %.2f",
                engine, video, clock.isPlaying() ? "yes" : "no", player.rate, player.timeControlStatus.rawValue,
                player.reasonForWaitingToPlay?.rawValue ?? "-", seekLead
            ))
        }
        let decision = VideoSyncPolicy.decide(
            engineSeconds: engine,
            videoSeconds: video,
            enginePlaying: clock.isPlaying(),
            engineRate: clock.rate(),
            videoRate: player.rate,
            secondsSinceSeek: now - lastSeekAt,
            seekLead: seekLead
        )
        switch decision {
        case .none:
            if !clock.isPlaying(), abs(engine - video) > VideoSyncPolicy.seekDriftSeconds {
                retryPausedSync(after: max(0.01, VideoSyncPolicy.seekCooldownSeconds - (now - lastSeekAt)))
            }
        case .pause:
            player.pause()
        case .play(let rate):
            if abs(player.rate - rate) > 0.005 || player.rate == 0 {
                player.playImmediately(atRate: rate)
            }
        case .seek(let seconds):
            isSeeking = true
            lastSeekAt = now
            seekGeneration &+= 1
            let generation = seekGeneration
            let time = CMTime(seconds: max(0, seconds), preferredTimescale: 600)
            let tolerance = CMTime(seconds: 0.05, preferredTimescale: 600)
            player.seek(to: time, toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] finished in
                Task { @MainActor [weak self] in
                    guard let self, self.player.currentItem === item,
                          self.seekGeneration == generation else { return }
                    self.isSeeking = false
                    if finished {
                        let took = CACurrentMediaTime() - now
                        self.seekLead = min(2, max(0.05, self.seekLead * 0.5 + took * 0.5))
                    }
                    self.tick()
                }
            }
        }
    }

    private func retryPausedSync(after delay: Double) {
        pausedSyncTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) }
            catch { return }
            guard let self, !Task.isCancelled, !self.clock.isPlaying() else { return }
            self.pausedSyncTask = nil
            self.tick()
        }
    }

    private func fail() {


        tearDownItem()
        state = .failed
    }

    private func startTimer() {
        guard timer == nil else { return }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }

        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func updateTimer() {
        guard state == .ready,
              let item = player.currentItem,
              item.status == .readyToPlay,
              clock.isPlaying()
        else {
            stopTimer()
            if !clock.isPlaying() { player.pause() }
            return
        }
        startTimer()
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func tearDownItem() {
        seekGeneration &+= 1
        pausedSyncTask?.cancel()
        pausedSyncTask = nil
        player.currentItem?.cancelPendingSeeks()
        statusObservation = nil
        if let failedObserver {
            NotificationCenter.default.removeObserver(failedObserver)
        }
        failedObserver = nil
        stopTimer()
        player.pause()
        player.replaceCurrentItem(with: nil)
        loader = nil
        isSeeking = false
        lastSeekAt = 0
        seekLead = 0.4
    }
}

@MainActor
extension VideoPlayback {
    func transportClockChanged() {
        controller.clockChanged()
    }
}
