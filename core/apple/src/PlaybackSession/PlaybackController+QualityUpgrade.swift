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


extension PlaybackController {

    var playbackSourceName: String? {
        guard let current else { return nil }
        return PlaybackSourceLabel.name(
            origin: resolvedOrigin[current.id]?.rawValue,
            source: current.source,
            isLocal: current.isLocal
        )
    }

    func noteResolved(_ entry: QueueEntry, outcome: ResolveOutcome, prefs: ResolvePrefs) {
        resolvedOrigin[entry.id] = outcome.source.origin
        sourceDecisionNotes[entry.id] = outcome.decisionNote
        if let videoId = outcome.source.youtubeVideoId { resolvedVideoIds[outcome.source.source] = videoId }
        prepareRegions(source: outcome.source.source, videoId: outcome.source.youtubeVideoId)

        debugLog.record(
            "resolved \(outcome.source.origin) \(outcome.source.format.summary)",
            about: entry.id
        )
        let below = prefs.wantLossless && !outcome.source.lossless
        let youtubeWon = outcome.source.origin == .youtube
        let leftover = outcome.leftover
        if youtubeWon || (outcome.source.origin == .substitute && below) {
            let pending = QualityUpgrade.settledForLess(
                mediaId: entry.id,
                target: QualityUpgrade.Target(
                    title: entry.title,
                    artist: entry.artist,
                    durationSec: {
                        let s = Int(entry.durationSeconds.rounded())
                        return s > 0 ? s : nil
                    }()
                ),
                inFlight: leftover,
                playing: outcome.source.format,
                canSubstitute: prefs.canSubstitute
            )
            if current?.id == entry.id { racingLossless = pending }
            if pending {
                debugLog.record(
                    leftover != nil
                        ? "started on the fallback; lookup is still running"
                        : "below request; will look again during playback",
                    about: entry.id
                )
            } else {
                leftover?.cancel()
            }
        } else {
            leftover?.cancel()
            if current?.id == entry.id { racingLossless = QualityUpgrade.isRacing(entry.id) }
        }
    }

    func lookForBetterCopy(_ entry: QueueEntry, codec: String, kbps: UInt32) {
        guard NetworkQuality.shared.connected else { return }
        guard !entry.isLocal, entry.source.hasPrefix("yt:") else { return }
        let mediaId = entry.id






        guard !OriginalVersion.shared.isPinned(videoId: mediaId) else {
            racingLossless = false
            return
        }
        let prefs = ResolvePrefs.current()


        if let shelved = QualityUpgrade.shelvedFor(mediaId) {
            debugLog.record("re-offering the upgrade already proved", about: mediaId)
            QualityUpgrade.onRaceStart(mediaId)
            racingLossless = true
            startUpgradeJob(entry: entry, prefs: prefs, shelved: shelved)
            return
        }
        if !QualityUpgrade.isPending(mediaId) {
            if resolvedOrigin[mediaId] == .cache {
                let playing = cachedFloor(kbps: kbps)
                let adopted = QualityUpgrade.adoptUnresolved(
                    mediaId: mediaId,
                    target: QualityUpgrade.Target(
                        title: entry.title, artist: entry.artist,
                        durationSec: duration > 0 ? Int(duration.rounded()) : nil
                    ),
                    playingCodec: codec,
                    playing: playing,
                    canSubstitute: prefs.canSubstitute
                )
                racingLossless = adopted
                if adopted {
                    debugLog.record(
                        "playing \(playing?.summary ?? "an unmeasured stream") from cache; looking for a better copy",
                        about: mediaId
                    )
                }
                guard adopted else { return }
            } else if !QualityUpgrade.couldStillUpgrade(
                mediaId: mediaId, canSubstitute: prefs.canSubstitute
            ) {
                racingLossless = QualityUpgrade.isRacing(mediaId)
                return
            }
        }
        if upgradeFor == mediaId { return }
        if QualityUpgrade.isPending(mediaId) {
            debugLog.record("looking again for a better copy", about: mediaId)
        }
        startUpgradeJob(entry: entry, prefs: prefs, shelved: nil)
    }

    private func cachedFloor(kbps: UInt32) -> QualityUpgrade.Format? {
        let codec = nerd?.codec
        guard kbps > 0 || codec != nil else { return nil }
        return QualityUpgrade.Format(codec: codec, kbps: kbps > 0 ? Int(kbps) : nil,
            lossless: QualityUpgrade.Format.isLosslessCodec(codec))
    }

    private func startUpgradeJob(
        entry: QueueEntry, prefs: ResolvePrefs, shelved: QualityUpgrade.Candidate?
    ) {
        let mediaId = entry.id
        upgradeFor = mediaId
        let generation = playGeneration
        racingLossless = true
        let eng = engine
        let log = debugLog
        qualityUpgradeTask?.cancel()
        qualityUpgradeTask = Task.detached(priority: .utility) { [weak self] in
            let better: QualityUpgrade.Candidate?
            if let shelved {
                better = shelved
            } else {
                better = await QualityUpgrade.lookAgain(
                    mediaId: mediaId,
                    playingDurationSec: await MainActor.run { [weak self] () -> Int? in
                        guard let self, self.duration > 0 else { return nil }
                        return Int(self.duration.rounded())
                    },
                    search: {
                        let playing = await MainActor.run { [weak self] () -> QualityUpgrade.Format? in
                            guard let self else { return nil }
                            let kbps = self.nerd?.kbps ?? 0
                            let codec = self.nerd?.codec
                            return QualityUpgrade.Format(codec: codec, kbps: kbps > 0 ? Int(kbps) : nil,
                                lossless: QualityUpgrade.Format.isLosslessCodec(codec))
                        }
                        let dur = await MainActor.run { [weak self] () -> Int? in
                            guard let self, self.duration > 0 else { return nil }
                            return Int(self.duration.rounded())
                        }
                        guard let hit = await Self.resolveSubstitute(
                            entry, prefs: prefs, waitForAll: true, playingDurationSec: dur, playing: playing
                        ) else { return nil }
                        let format = hit.format
                        let allowsLosslessSources = await MainActor.run { PlaybackCodecCapabilities.shared.allowLosslessSources }
                        if format.lossless && !allowsLosslessSources { return nil }
                        guard QualityUpgrade.worthSwapping(format, playing: playing) else {
                            return nil
                        }
                        if let dur, !QualityUpgrade.sameRecordingAs(hit.durationSec, dur),
                           hit.durationSec != nil {
                            return nil
                        }
                        return hit.asCandidate()
                    }
                )
            }
            defer {
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if self.upgradeFor == mediaId {
                        self.upgradeFor = nil
                        self.qualityUpgradeTask = nil
                    }
                    QualityUpgrade.onRaceEnd(mediaId)
                    if self.current?.id == mediaId {
                        self.racingLossless = QualityUpgrade.isRacing(mediaId)
                    }
                }
            }
            let moment = await MainActor.run { [weak self] () -> String in
                guard let self, self.playGeneration == generation else { return "gone" }
                if self.current?.id == mediaId { return "current" }
                if self.nextEntry?.id == mediaId { return "next" }
                return "gone"
            }
            guard let better else {
                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == generation, self.current?.id == mediaId else { return }
                    self.sourceDecisionNotes[mediaId] = "No verified upgrade was found. Playback continues from the original source."
                }
                return
            }
            let allowsLosslessSources = await MainActor.run { PlaybackCodecCapabilities.shared.allowLosslessSources }
            guard !better.format.lossless || allowsLosslessSources else { return }
            switch moment {
            case "current":
                await self?.performSwap(
                    mediaId: mediaId, stream: better, entry: entry,
                    generation: generation, engine: eng, log: log
                )
            case "next":
                await self?.installUpgradedIncoming(
                    mediaId: mediaId, stream: better, entry: entry,
                    generation: generation, engine: eng, log: log
                )
            default:
                QualityUpgrade.shelve(mediaId, stream: better)
                log.record("upgrade proved but the queue moved on; shelved", about: mediaId)
            }
        }
    }

    nonisolated private func installUpgradedIncoming(
        mediaId: String,
        stream: QualityUpgrade.Candidate,
        entry: QueueEntry,
        generation: UInt64,
        engine: PlayerEngine,
        log: PlaybackDebugLog
    ) async {
        let path: String?
        if stream.url.hasPrefix("/") || stream.url.hasPrefix("file:") {
            let local = stream.url.hasPrefix("file:")
                ? (URL(string: stream.url)?.path ?? stream.url)
                : stream.url
            path = FileManager.default.fileExists(atPath: local) ? local : nil
        } else {
            path = try? await Self.streamViaKtor(
                videoId: "\(mediaId)#\(QualityUpgrade.upgraded)",
                url: stream.url,
                headers: stream.headers, codec: stream.format.codec ?? "unknown", kbps: stream.format.kbps ?? 0,
                durationSeconds: stream.durationSec
            )
        }
        guard let path else {
            QualityUpgrade.forget(mediaId)
            log.record("upgrade audition failed", about: mediaId)
            return
        }
        guard await RecordingIdentityVerifier.matches(videoId: mediaId, candidate: path, allowTimingEdits: entry.isVideo) else {
            guard !Task.isCancelled else { return }
            QualityUpgrade.refuseUpgrades(mediaId); QualityUpgrade.forget(mediaId)
            await StreamFileCache.shared.forget(videoId: "\(mediaId)#\(QualityUpgrade.upgraded)")
            log.record("queued replacement rejected: decoded audio does not verify against the selected YouTube recording", about: mediaId)
            return
        }
        let allowsLosslessSources = await MainActor.run { PlaybackCodecCapabilities.shared.allowLosslessSources }
        guard !stream.format.lossless || allowsLosslessSources else { return }
        let context = await MainActor.run { () -> (String, String, String, String, Bool, Double, [String: String])? in
            guard self.playGeneration == generation, self.nextEntry?.id == mediaId else { return nil }
            let outgoing = self.loadedSourcePath ?? ""
            guard !outgoing.isEmpty else { return nil }
            let outgoingId = self.current?.id ?? ""
            let album = !self.shuffleEnabled && (self.current?.sameAlbum(as: entry) ?? false)
            let outgoingDuration = self.duration > 0
                ? self.duration : (self.current?.durationSeconds ?? 0)
            return (
                outgoing, outgoingId, self.current?.itemText ?? "", entry.itemText, album,
                outgoingDuration, self.loadedSourceHeaders
            )
        }
        guard let (outgoing, outgoingId, outgoingText, incomingText, album, outgoingDuration, outgoingHeaders) = context else {
            let nowPlaying = await MainActor.run {
                self.playGeneration == generation && self.current?.id == mediaId
            }
            if nowPlaying {
                var local = stream
                local.url = path
                await performSwap(
                    mediaId: mediaId, stream: local, entry: entry,
                    generation: generation, engine: engine, log: log
                )
            } else {
                var local = stream
                local.url = path
                QualityUpgrade.shelve(mediaId, stream: local)
                log.record("upgrade proved but the queue moved on; shelved", about: mediaId)
            }
            return
        }
        let fade = Double(PlatformSettings.shared.getInt(key: "crossfade_seconds", default: 0))
        let incomingDuration = entry.durationSeconds > 0
            ? entry.durationSeconds : Double(stream.durationSec ?? 0)
        let allowEffects = self.pacing.allowsEffect(for: TransitionPair(outgoing: outgoingId, incoming: mediaId))
        let plan = await AnalysisTurns.take {
            engine.planAutomix(
                outgoingPath: outgoing,
                incomingPath: path,
                outgoingText: outgoingText,
                incomingText: incomingText,
                albumSequential: album,
                crossfadeSeconds: fade,
                outgoingDurationSeconds: outgoingDuration,
                incomingDurationSeconds: incomingDuration,
                outgoingHeaders: outgoingHeaders,

                incomingHeaders: [:],
                allowEffects: allowEffects
            )
        }
        let target = await MainActor.run { () -> (PlaybackLoadSubmissionGate, UInt64)? in
            guard self.playGeneration == generation, self.nextEntry?.id == mediaId,
                  self.loadedSourcePath == outgoing else { return nil }
            self.queueNextRevision &+= 1
            self.loadSubmissionGate.setQueueRevision(self.queueNextRevision)
            self.sourceHeadersByPath[path] = [:]
            return (self.loadSubmissionGate, self.queueNextRevision)
        }
        guard let (gate, revision) = target else { return }
        do {
            guard try gate.performIfCurrent(generation: generation, revision: revision, {
                self.pacing.note(plan.style, for: TransitionPair(outgoing: outgoingId, incoming: mediaId))
                return try engine.queueNextIfCurrent(request: LoadRequest(
                    source: path,
                    title: entry.title,
                    artist: entry.artist,
                    startSeconds: plan.cueSeconds,
                    plan: plan,
                    headers: stream.headers,
                    claimedKbps: Swift.UInt32(stream.format.kbps ?? 0),
                    loudnessDb: nil,
                    durationSeconds: entry.durationSeconds > 0 ? entry.durationSeconds : stream.durationSec.map(Double.init)
                ), expectedSource: outgoing)
            }) != nil else { return }
        } catch {
            var local = stream
            local.url = path
            QualityUpgrade.shelve(mediaId, stream: local)
            log.record("upgrade could not join the blend", about: mediaId)
            return
        }
        await StreamFileCache.shared.markRecordingVerified(mediaID: mediaId, cacheKey: "\(mediaId)#\(QualityUpgrade.upgraded)", sourcePath: path)
        await MainActor.run { [weak self] in
            guard let self, self.playGeneration == generation, self.nextEntry?.id == mediaId else { return }
            self.adoptAutomixPlan(plan)
            self.resolvedOrigin[mediaId] = .substitute
        }
        QualityUpgrade.unshelve(mediaId)
        log.record("upgrade joined the blend as \(stream.format.summary)", about: mediaId)
    }

    nonisolated private func performSwap(
        mediaId: String,
        stream: QualityUpgrade.Candidate,
        entry: QueueEntry,
        generation: UInt64,
        engine: PlayerEngine,
        log: PlaybackDebugLog
    ) async {
        let snapshot = await MainActor.run { () -> (Double, Double, Bool, Double)? in
            guard self.playGeneration == generation, self.current?.id == mediaId,
                  !stream.format.lossless || PlaybackCodecCapabilities.shared.allowLosslessSources else { return nil }
            let planned = self.pendingAutomixPlan?.fadeSeconds ?? 0


            let fade = self.automixEnabled ? max(planned, 8) : planned
            return (self.position, self.duration, self.smartMixInProgress, fade)
        }
        guard let (pos, dur, mixing, fade) = snapshot else {
            QualityUpgrade.shelve(mediaId, stream: stream)
            log.record("upgrade proved but the queue moved on; shelved", about: mediaId)
            return
        }
        let horizon = max(QualityUpgrade.minRemaining, fade + 6)
        if mixing || (dur > 0 && dur - pos < horizon) {
            QualityUpgrade.shelve(mediaId, stream: stream)
            log.record(
                mixing
                    ? "upgrade shelved: a crossfade was still running"
                    : "upgrade held: the blend starts inside \(Int(horizon))s",
                about: mediaId
            )
            return
        }
        QualityUpgrade.beginAudition(mediaId)
        let warmed: String?
        if stream.url.hasPrefix("/") || stream.url.hasPrefix("file:") {
            let path = stream.url.hasPrefix("file:")
                ? (URL(string: stream.url)?.path ?? stream.url)
                : stream.url
            warmed = FileManager.default.fileExists(atPath: path) ? path : nil
        } else {
            warmed = try? await Self.streamViaKtor(
                videoId: "\(mediaId)#\(QualityUpgrade.upgraded)",
                url: stream.url,
                headers: stream.headers, codec: stream.format.codec ?? "unknown", kbps: stream.format.kbps ?? 0,
                durationSeconds: stream.durationSec
            )
        }
        QualityUpgrade.endAudition(mediaId)
        guard let path = warmed else {
            QualityUpgrade.forget(mediaId)
            log.record("upgrade audition failed", about: mediaId)
            return
        }
        guard await RecordingIdentityVerifier.matches(videoId: mediaId, candidate: path, allowTimingEdits: entry.isVideo) else {
            guard !Task.isCancelled else { return }
            QualityUpgrade.refuseUpgrades(mediaId); QualityUpgrade.forget(mediaId)
            await StreamFileCache.shared.forget(videoId: "\(mediaId)#\(QualityUpgrade.upgraded)")
            log.record("replacement rejected: decoded audio does not verify against the selected YouTube recording", about: mediaId)
            await MainActor.run { [weak self] in
                guard let self, self.playGeneration == generation, self.current?.id == mediaId else { return }
                self.sourceDecisionNotes[mediaId] = "The alternative audio did not match the selected recording. Playback continues from the original."
                self.racingLossless = false
            }
            return
        }
        let again = await MainActor.run { () -> (Double, Bool, Double?, String?)? in
            guard self.playGeneration == generation, self.current?.id == mediaId else { return nil }
            if self.smartMixInProgress { return nil }
            return (
                self.position, self.isPlaying, self.currentLoudnessDb,
                self.loadedSourcePath
            )
        }
        guard let (nowPos, playing, loudnessDb, priorSource) = again else {
            QualityUpgrade.shelve(mediaId, stream: stream)
            log.record("upgrade proved but the queue moved on; shelved", about: mediaId)
            return
        }
        if dur > 0 && dur - nowPos < horizon {
            QualityUpgrade.shelve(mediaId, stream: stream)
            return
        }



        var effectivePos = nowPos
        if playing, let currentSource = priorSource {
            if let dipTime = engine.nextEnergyDip(source: currentSource, positionSeconds: nowPos) {
                let delay = dipTime - nowPos
                if delay >= 0.1 && delay <= 2.0 {
                    log.record("delaying swap by \(Int(delay * 1000))ms for energy dip at \(Int(dipTime * 1000))ms", about: mediaId)
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    let stillValid = await MainActor.run { () -> Double? in
                        guard self.playGeneration == generation, self.current?.id == mediaId, self.isPlaying else { return nil }
                        return self.position
                    }
                    guard let updatedPos = stillValid else {
                        QualityUpgrade.shelve(mediaId, stream: stream)
                        return
                    }
                    effectivePos = updatedPos
                }
            }
        }


        let swapPos = effectivePos
        do {











            let gate = await MainActor.run { self.loadSubmissionGate }
            try Task.checkCancellation()
            guard let info = try gate.performIfCurrent(generation: generation, {
                try engine.swapSourceIfCurrent(
                    request: LoadRequest(
                        source: path,
                        title: entry.title,
                        artist: entry.artist,
                        startSeconds: swapPos,
                        plan: nil,
                        headers: stream.headers,
                        claimedKbps: Swift.UInt32(stream.format.kbps ?? 0),



                        loudnessDb: loudnessDb,



                        durationSeconds: entry.durationSeconds > 0 ? entry.durationSeconds : stream.durationSec.map(Double.init)
                    ),
                    crossfadeSeconds: QualityUpgrade.swapCrossfadeSeconds,
                    expectedSource: priorSource ?? ""
                )
            }) else { return }
            log.record("upgrade candidate opened; waiting for mixer acceptance", about: mediaId)
            let accepted = await PlaybackSwapConfirmation.wait(
                isCurrent: {
                    await MainActor.run {
                        self.playGeneration == generation && self.current?.id == mediaId &&
                            (self.state == .playing || self.state == .paused)
                    }
                },
                outcome: { engine.sourceSwapOutcome(source: info.source) }
            )
            guard let accepted else { return }
            guard accepted == true else {
                QualityUpgrade.refuseUpgrades(mediaId)
                QualityUpgrade.forget(mediaId)
                await MainActor.run {
                    guard self.playGeneration == generation, self.current?.id == mediaId else { return }
                    self.nerd = engine.nerdStats()
                    self.racingLossless = false
                }
                log.record("quality upgrade rejected by mixer; original source remains active", about: mediaId)
                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == generation, self.current?.id == mediaId else { return }
                    self.sourceDecisionNotes[mediaId] = "The alternative audio did not match the current recording closely enough."
                }
                return
            }
            await StreamFileCache.shared.markRecordingVerified(mediaID: mediaId, cacheKey: "\(mediaId)#\(QualityUpgrade.upgraded)", sourcePath: path)
            await MainActor.run {
                guard self.playGeneration == generation, self.current?.id == mediaId else { return }
                QualityUpgrade.unshelve(mediaId)
                QualityUpgrade.force(mediaId, stream: stream)
                self.engineLoadedId = entry.id
                self.loadedSourcePath = info.source
                self.loadedSourceHeaders = stream.headers
                self.resolvedOrigin[mediaId] = .substitute
                self.duration = info.durationSeconds

                self.position = engine.positionSeconds()
                self.positionSampledAt = Date()
                self.nerd = engine.nerdStats()
                self.racingLossless = false







                log.record(
                    "upgraded to \(stream.format.summary) in place at \(Int(swapPos * 1000))ms",
                    about: mediaId
                )
            }
        } catch is CancellationError {
            return
        } catch EngineError.LoadFailed(let message)
            where message.contains("transition is already running") {



            QualityUpgrade.shelve(mediaId, stream: stream)
            log.record("upgrade deferred: \(message)", about: mediaId)
        } catch {
            QualityUpgrade.refuseUpgrades(mediaId)
            QualityUpgrade.forget(mediaId)
            var explanation = "The alternative stream could not be opened; the original source remains active."
            if case EngineError.LoadFailed(let detail) = error, detail.contains("PCM alignment") {
                explanation = "The alternative recording could not be safely aligned; the original source remains active."
            }
            let note = explanation
            await MainActor.run { [weak self] in
                guard let self, self.playGeneration == generation, self.current?.id == mediaId else { return }
                self.sourceDecisionNotes[mediaId] = note
            }
            log.record("upgrade refused; original source remains active", about: mediaId)
        }
    }
}
