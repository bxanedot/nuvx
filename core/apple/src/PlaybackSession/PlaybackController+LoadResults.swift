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
    func prepareNowPlayingForPlayback(entry: QueueEntry, duration: Double, position: Double) {
        nowPlaying.prepareForPlayback(
            id: entry.id, title: entry.title, artist: entry.artist,
            duration: duration, artworkData: entry.artworkData,
            thumbnailUrl: entry.thumbnailUrl, position: position,
            canNext: playingIndex + 1 < queue.count || repeatMode == .all,
            canPrevious: true, canSeek: duration > 0
        )
    }

    func loadDidSucceed(
        entry: QueueEntry,
        index: Int,
        info: TrackInfoRec,
        startAt: Double = 0,
        headers: [String: String]
    ) {
        if var pending = pendingPlaybackLatency, pending.entryID == entry.id {
            let firstBufferAt = ProcessInfo.processInfo.systemUptime
            PlaybackLatencySignposts.record(
                "first-buffer", entryID: entry.id,
                elapsed: firstBufferAt - pending.startedAt
            )
            pending.firstBufferAt = firstBufferAt
            pendingPlaybackLatency = pending
        }
        NSLog("[Nuvx] loaded track at %.2fs (duration %.2fs)", startAt, info.durationSeconds)
        loadingGeneration = nil
        playingIndex = index
        current = entry
        historyVideoID = youtubeVideoID(for: entry)
        let restoredStream = recoveringEntryID == entry.id
        if restoredStream {
            sourceDecisionNotes[entry.id] = "Playback restored with a fresh stream."
            recoveringEntryID = nil
        }
        engineLoadedId = entry.id
        loadedSourcePath = info.source
        loadedSourceHeaders = headers
        Self.releaseObsoleteStreams(keeping: Set([entry.id, nextEntry?.id].compactMap { $0 }))
        position = startAt
        positionSampledAt = Date()
        duration = info.durationSeconds
        repairMissingDuration(entry: entry, info: info)
        lastError = nil
        automaticLoadEntryId = nil
        automaticSkips = 0
        state = .playing
        persistSession()
        refreshArtwork(entry, source: info.source)
        fetchLyrics(for: entry)
        fetchCanvas(for: entry)
        applySpatialPreference()
        try? engine.setLoudnessMode(mode: routeAwareLoudnessMode)
        nerd = dolbyRenderer?.nerd ?? engine.nerdStats()
        try? engine.setBitPerfectEnabled(enabled: effectiveBitPerfectOutput)
        racingLossless = QualityUpgrade.isRacing(entry.id)
        if !restoredStream {
            scrobbleArmed = false
            scrobbleSent = false
            if let id = historyVideoID {
                QueueBuilderBridge.shared.rememberPlayed(videoId: id)
                PlaybackTrackerBridge.shared.onPlaying(videoId: id)
            }
            publishPresence()
            ScrobbleBridge.shared.nowPlaying(
                artist: scrobbleArtist(for: entry), title: entry.title, album: entry.albumName,
                durationSec: Swift.Int32(info.durationSeconds), positionMs: Swift.Int64(0)
            )
        }
        nowPlaying.update(
            id: entry.id, title: entry.title, artist: entry.artist,
            duration: info.durationSeconds, artworkData: entry.artworkData,
            thumbnailUrl: entry.thumbnailUrl, isPlaying: isPlaying, position: position
        )
        nowPlaying.playbackDidStart()
        widgetPublisher.publish(entry: entry, isPlaying: true,
                                canNext: playingIndex + 1 < queue.count,
                                canPrevious: index > 0)
        if dolbyRenderer == nil { lookForBetterCopy(entry, codec: info.codec, kbps: info.kbps) }
        let output = engine.outputDevice()
        let sourceKind: String
        switch resolvedOrigin[entry.id] {
        case .local: sourceKind = "local"
        case .cache: sourceKind = "cache"
        case .youtube: sourceKind = "youtube"
        case .substitute: sourceKind = "substitute"
        case .other, .none: sourceKind = "other"
        }
        let rawLoudnessOrigin = nerd?.loudnessOrigin.lowercased() ?? ""
        let loudnessOrigin: String
        if rawLoudnessOrigin.contains("youtube") {
            loudnessOrigin = "player_response"
        } else if rawLoudnessOrigin.contains("measurement") || rawLoudnessOrigin.contains("replaygain") || rawLoudnessOrigin.contains("ebu r128") {
            loudnessOrigin = "analyzed"
        } else {
            loudnessOrigin = "none"
        }
        PlaybackDebugLog.shared.record(
            "quality-start source=\(sourceKind) route_kind=\(AudioRouteState.shared.kind) claimed_quality=\(AppSettings.shared.effectiveAudioQuality(metered: NetworkQuality.shared.metered).name) claimed_max_kbps=\(NetworkQuality.shared.maxKbps) measured_codec=\(info.codec) measured_rate=\(info.sampleRate) measured_depth=\(info.bitDepth) measured_kbps=\(info.kbps) output_rate=\(output.sampleRate) output_format=\(output.sampleFormat) loudness_origin=\(loudnessOrigin) loudness_detail=\(nerd?.loudnessOrigin ?? "No measurement") loudness_db=\(currentLoudnessDb.map { String($0) } ?? "unknown") loudness_mode=\(loudnessMode) bit_perfect=\(output.bitPerfectActive)",
            about: entry.id
        )



        syncEngineQueueNext()
        scheduleSequencing()
    }



    private func repairMissingDuration(entry: QueueEntry, info: TrackInfoRec) {
        guard duration <= 0, let videoId = entry.videoId else { return }
        let generation = playGeneration
        let ceiling = ResolvePrefs.current().maxKbps
        durationRepairTask?.cancel()
        durationRepairTask = Task { [weak self] in
            guard let stream = try? await InnertubeStreamResolver.shared.resolve(videoId: videoId, maxKbps: ceiling),
                  let seconds = stream.durationSeconds, seconds > 0, !Task.isCancelled,
                  let self, self.playGeneration == generation, self.engineLoadedId == entry.id,
                  self.loadedSourcePath == info.source, let current = self.current else { return }
            self.duration = Double(seconds)
            self.nowPlaying.update(id: current.id, title: current.title, artist: current.artist,
                duration: self.duration, artworkData: current.artworkData, thumbnailUrl: current.thumbnailUrl,
                isPlaying: self.isPlaying, position: self.position)
            self.nowPlaying.updateCommands(canNext: self.nextEntry != nil, canPrevious: true, canSeek: true)
            self.publishSmartWindow()
            await StreamFileCache.shared.storeDuration(seconds, at: info.source)
        }
    }

    func loadDidFail(entry: QueueEntry, error: Error) {
        if let pending = pendingPlaybackLatency, pending.entryID == entry.id {
            let resolved = pending.resolvedAt.map { String(format: "%.0fms", ($0 - pending.startedAt) * 1_000) } ?? "unresolved"
            PlaybackDebugLog.shared.record(
                "latency: tap→resolved \(resolved), first buffer unavailable, first audible unavailable (\(pending.cacheState), \(pending.client))",
                about: entry.id
            )
            pendingPlaybackLatency = nil
        }
        loadingGeneration = nil
        holdAfterLoad = false



        if automaticLoadEntryId == entry.id, state == .buffering, automaticSkips < 3,
           markUnplayable(entry, after: error),
           let next = nextPlayableIndex(after: playingIndex) {
            automaticSkips += 1
            PlaybackDebugLog.shared.record(
                "could not play \(entry.title) (\(error.localizedDescription)); moving on to \(queue[next].title)",
                about: entry.id
            )
            automaticLoadEntryId = queue[next].id
            loadCurrent(next, refreshAutoplay: true)
            return
        }
        automaticLoadEntryId = nil
        lastError = "Couldn't play “\(entry.title)” — \(error.localizedDescription)"



        guard state == .buffering else { return }
        state = .stopped
        dolbyRenderer?.stop()
        dolbyRenderer = nil
        engineLoadedId = nil
        try? engine.pause()
        let engine = self.engine
        loadSubmissionGate.advance(to: playGeneration) { try? engine.stop() }
        nowPlaying.stop()
        deactivateAudioSessionIfIdle()
    }



    private nonisolated static func fileSettled(_ path: String) -> Bool {
        let files = FileManager.default
        if files.fileExists(atPath: path + ".complete") { return true }
        if files.fileExists(atPath: path + ".grow") { return false }
        return true
    }

    private nonisolated static func fileBytes(_ path: String) -> Int64 {
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber
        return size?.int64Value ?? 0
    }



    private nonisolated static func grewMeaningfully(from old: Int64, to new: Int64) -> Bool {
        new - old >= max(old / 4, 512 * 1024)
    }



    private nonisolated static func waitForOpeningToSettle(
        engine: PlayerEngine,
        outgoingDuration: Double,
        transitionSeconds: Double
    ) async throws {
        let settledAt = 15.0
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            let position = engine.positionSeconds()
            if position >= settledAt { return }
            if outgoingDuration > 0, outgoingDuration - position < transitionSeconds + 60 { return }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }





    func syncEngineQueueNext() {
        nextPreparationTask?.cancel()
        nextPreparationTask = nil
        guard !sleepAfterTrack else { return }
        if dolbyRenderer != nil {
            nowPlaying.updateCommands(canNext: nextEntry != nil, canPrevious: current != nil, canSeek: duration > 0)
            return
        }
        nowPlaying.updateCommands(canNext: playingIndex + 1 < queue.count || repeatMode == .all, canPrevious: current != nil,
                                  canSeek: engineLoadedId != nil && duration > 0)


        guard engineLoadedId != nil, engineLoadedId == current?.id else { return }
        Self.releaseObsoleteStreams(keeping: Set([current?.id, nextEntry?.id].compactMap { $0 }))
        queueNextRevision &+= 1
        let revision = queueNextRevision
        let gate = loadSubmissionGate
        gate.setQueueRevision(revision)
        let generation = playGeneration
        let expectedSource = loadedSourcePath ?? ""

        gate.submit(generation: generation, revision: revision) { [engine] in
            try? engine.queueNextIfCurrent(request: LoadRequest(
                source: "", title: "", artist: "", startSeconds: 0, plan: nil,
                headers: [:], claimedKbps: 0, loudnessDb: nil, durationSeconds: nil
            ), expectedSource: expectedSource)
        }


        warmUpcoming(around: playingIndex, generation: generation)
        if repeatMode == .one && !automixEnabled {


            if let repeated = repeatableCurrentSource(), let entry = current {
                let loop = TransitionPlanRec(
                    style: .gapless, bassSwap: false, bassSwapFraction: 0.7,
                    filterSweep: 0, vocalOverlap: 0, fadeSeconds: 0,
                    transitionEndSeconds: 0, cueSeconds: 0, playbackRate: 1,
                    bedFraction: 0, bedGainDb: 0, dipDepth: 0, dipWidth: 0,
                    postGlideSeconds: 0, outgoingDurationSeconds: duration,
                    reason: "repeat-one"
                )
                sourceHeadersByPath = [repeated.source: repeated.headers]
                gate.submit(generation: generation, revision: revision) { [engine] in
                    try? engine.queueNextIfCurrent(request: LoadRequest(
                        source: repeated.source, title: entry.title, artist: entry.artist,
                        startSeconds: 0, plan: loop, headers: repeated.headers,
                        claimedKbps: UInt32(clamping: repeated.kbps), loudnessDb: repeated.loudnessDb,
                        durationSeconds: repeated.durationSec.map(Double.init)
                    ), expectedSource: expectedSource)
                }
            }
            return
        }
        guard let next = nextEntry else {
            return
        }
        let nextId = next.id
        let outgoingId = current?.id
        let selfMix = repeatMode == .one || nextId == outgoingId


        let transitionPair = TransitionPair(outgoing: outgoingId ?? "", incoming: nextId)
        let engine = self.engine
        let automix = automixEnabled
        let allowMusicUnderstanding = automixPerformanceMode != "EFFICIENT"







        let currentSource = loadedSourcePath ?? ""
        let outgoingDuration = duration > 0 ? duration : (current?.durationSeconds ?? 0)
        let currentSourceHeaders = loadedSourceHeaders
        let repeatedSource = selfMix ? repeatableCurrentSource() : nil
        let currentText = current?.itemText ?? ""
        let nextText = next.itemText

        let albumSequential = !selfMix && !shuffleEnabled && (current?.sameAlbum(as: next) ?? false)
        let prefs = ResolvePrefs.current()





        let fadeSeconds = automix ? max(pendingAutomixPlan?.fadeSeconds ?? 8, 8)
            : Double(PlatformSettings.shared.getInt(key: "crossfade_seconds", default: 0))
        let preparationBudget = PlaybackPreparationPolicy.lookupBudget(
            remainingSeconds: outgoingDuration - position, transitionSeconds: fadeSeconds
        )
        nextPreparationTask = Task.detached(priority: .userInitiated) {
            defer { MemoryRelief.soon() }
            do {





                try await Self.waitForOpeningToSettle(
                    engine: engine, outgoingDuration: outgoingDuration,
                    transitionSeconds: fadeSeconds
                )
                let selection: ResolveOutcome
                if let repeatedSource {
                    selection = ResolveOutcome(source: repeatedSource, leftover: nil)
                } else {
                    selection = try await Self.resolveSource(next, prefs: prefs, preparationBudget: preparationBudget)
                }
                try Task.checkCancellation()
                let outcome = try await Self.bufferPreparedSource(selection, mediaId: nextId)
                let stillCurrent = await MainActor.run { [weak self] in
                    guard let self else { return false }
                    return self.playGeneration == generation
                        && self.nextEntry?.id == nextId
                        && self.current?.id == outgoingId
                        && self.queueNextRevision == revision
                }
                guard stillCurrent else { return }
                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == generation, self.queueNextRevision == revision else { return }
                    self.noteResolved(next, outcome: outcome, prefs: prefs)
                    self.sourceHeadersByPath = [outcome.source.source: outcome.source.headers]
                    DownloadStore.shared.retainPlayback(paths: Set([outcome.source.source, self.loadedSourcePath ?? ""]))




                    if !selfMix {
                        self.lookForBetterCopy(
                            next,
                            codec: outcome.source.format.codec ?? "",
                            kbps: Swift.UInt32(outcome.source.kbps)
                        )
                    }
                }
                let stillQueueTarget = await MainActor.run { [weak self] in
                    guard let self else { return false }
                    return self.playGeneration == generation
                        && self.nextEntry?.id == nextId
                        && self.current?.id == outgoingId
                        && self.queueNextRevision == revision
                }
                guard stillQueueTarget else { return }
                let resolved = outcome.source
                guard !PlaybackCodecCapabilities.shared.canRenderDolby(codec: resolved.codec, headers: resolved.headers) else { return }
                let incomingDuration = next.durationSeconds > 0
                    ? next.durationSeconds : Double(resolved.durationSec ?? 0)
                let declaredDuration = incomingDuration > 0 ? incomingDuration : nil
                let safetyFade = albumSequential ? 4.0 : min(
                    8.0,
                    outgoingDuration > 0 ? outgoingDuration / 3 : 8.0,
                    incomingDuration > 0 ? incomingDuration / 3 : 8.0
                )



                let initialSafetyPlan: TransitionPlanRec? = automix ? TransitionPlanRec(
                    style: .equalPower, bassSwap: false, bassSwapFraction: 0.7,
                    filterSweep: 0, vocalOverlap: 0, fadeSeconds: safetyFade,
                    outgoingHoldSeconds: 0,
                    transitionEndSeconds: 0, cueSeconds: 0, playbackRate: 1,
                    bedFraction: 0, bedGainDb: 0, dipDepth: 0, dipWidth: 0,
                    postGlideSeconds: 0, outgoingDurationSeconds: outgoingDuration,
                    reason: "safety:"
                ) : nil
                let safetyPlan = initialSafetyPlan
                    .map { constrainAutomixPlanForSources(plan: $0, outgoingSource: currentSource, incomingSource: resolved.source, outgoingDuration: outgoingDuration, incomingDuration: incomingDuration) }
                guard try gate.performIfCurrent(generation: generation, revision: revision, {
                    try engine.queueNextIfCurrent(request: LoadRequest(
                    source: resolved.source, title: next.title, artist: next.artist,
                    startSeconds: 0, plan: safetyPlan, headers: resolved.headers,
                    claimedKbps: Swift.UInt32(resolved.kbps),
                    loudnessDb: resolved.loudnessDb, durationSeconds: declaredDuration
                ), expectedSource: expectedSource)
                }) != nil else { return }
                if let safetyPlan {
                    await MainActor.run { [weak self] in
                        guard let self, self.playGeneration == generation,
                              self.queueNextRevision == revision, self.current?.id == outgoingId else { return }
                        self.adoptAutomixPlan(safetyPlan)
                    }
                }
                if automix {
                    NSLog(
                        "[Nuvx] automix queued %@%@ with an immediate %.1fs overlap",
                        next.title,
                        selfMix ? " (self-mix)" : "",
                        safetyPlan?.fadeSeconds ?? 0
                    )
                }
                guard automix, !currentSource.isEmpty else { return }
                let fade = Double(PlatformSettings.shared.getInt(key: "crossfade_seconds", default: 0))



                var attempt = 0
                var muOutgoingDone = false
                var muIncomingDone = false
                while true {
                    try Task.checkCancellation()
                    let stillPlanning = await MainActor.run { [weak self] in
                        guard let self else { return false }
                        return self.playGeneration == generation
                            && self.nextEntry?.id == nextId
                            && self.current?.id == outgoingId
                            && self.queueNextRevision == revision
                    }
                    guard stillPlanning else { return }








                    while !(Self.fileSettled(currentSource) && Self.fileSettled(resolved.source)) {
                        let remaining = outgoingDuration - engine.positionSeconds()
                        if outgoingDuration <= 0 || remaining < safetyFade + 60 { break }
                        try await Task.sleep(nanoseconds: 2_000_000_000)
                    }





                    if attempt == 0 {
                        await MainActor.run { [weak self] in
                            guard let self,
                                  let edges = self.regionEdgesBySource[currentSource],
                                  edges.end == nil else { return }
                            let partial = FileManager.default.fileExists(atPath: currentSource + ".grow")
                                && !FileManager.default.fileExists(atPath: currentSource + ".complete")
                            self.debugLog.record(
                                "automix: no analysed tail when the transition arms\(partial ? " (file still growing)" : " (file complete)")",
                                about: outgoingId
                            )
                        }
                    }












                    if MusicUnderstandingAnalyzer.isAvailable, !(muOutgoingDone && muIncomingDone) {
                        let decoder: (Double, [String: String]) -> MusicUnderstandingAnalyzer.Decode = { length, headers in
                            { path, destination in
                                engine.writeAnalysisWav(
                                    source: path, dest: destination.path, headers: headers,
                                    maxSeconds: length > 0 ? length + 5 : 0, targetRate: nil
                                )
                            }
                        }
                        var outOk = false
                        var inOk = false


                        if !muOutgoingDone {
                            let decode = decoder(outgoingDuration, currentSourceHeaders)
                            outOk = await AnalysisTurns.take {
                                await MusicUnderstandingAnalyzer.analyzeAndSeed(
                                    filePath: currentSource, allowAccelerator: allowMusicUnderstanding,
                                    decode: decode
                                )
                            }
                            muOutgoingDone = outOk || Self.fileSettled(currentSource)
                        }
                        if !muIncomingDone {
                            let decode = decoder(incomingDuration, resolved.headers)
                            let incomingSource = resolved.source
                            inOk = await AnalysisTurns.take {
                                await MusicUnderstandingAnalyzer.analyzeAndSeed(
                                    filePath: incomingSource, allowAccelerator: allowMusicUnderstanding,
                                    decode: decode
                                )
                            }
                            muIncomingDone = inOk || Self.fileSettled(resolved.source)
                        }
                        if outOk || inOk {
                            NSLog(
                                "[Nuvx] Music Understanding overlay out=%@ in=%@",
                                outOk ? "yes" : "no",
                                inOk ? "yes" : "no"
                            )
                        }
                    }
                    let allowEffects = self.pacing.allowsEffect(for: transitionPair)
                    let incomingSource = resolved.source
                    let incomingHeaders = resolved.headers
                    let rawPlan = await AnalysisTurns.take {
                        engine.planAutomix(
                            outgoingPath: currentSource,
                            incomingPath: incomingSource,
                            outgoingText: currentText,
                            incomingText: nextText,
                            albumSequential: albumSequential,
                            crossfadeSeconds: fade,
                            outgoingDurationSeconds: outgoingDuration,
                            incomingDurationSeconds: incomingDuration,
                            outgoingHeaders: currentSourceHeaders,
                            incomingHeaders: incomingHeaders,
                            allowEffects: allowEffects
                        )
                    }


                    MemoryRelief.soon()
                    let plan = constrainAutomixPlanForSources(
                        plan: rawPlan, outgoingSource: currentSource, incomingSource: resolved.source,
                        outgoingDuration: outgoingDuration, incomingDuration: incomingDuration
                    )
                    let queued = try gate.performIfCurrent(generation: generation, revision: revision, {
                        self.pacing.note(plan.style, for: transitionPair)
                        return try engine.queueNextIfCurrent(request: LoadRequest(
                            source: resolved.source,
                            title: next.title,
                            artist: next.artist,
                            startSeconds: plan.cueSeconds,
                            plan: plan,
                            headers: resolved.headers,
                            claimedKbps: Swift.UInt32(resolved.kbps),
                            loudnessDb: resolved.loudnessDb,





                            durationSeconds: declaredDuration
                        ), expectedSource: expectedSource)
                    }) != nil
                    guard queued else { return }



                    let delivered = engine.pendingTrack()?.source == resolved.source
                    if delivered {
                        let shown = Self.displayAutomixReason(plan.reason)
                        NSLog(
                            "[Nuvx] automix plan %d for %@%@: %@, cue %.2fs, fade %.2fs, end %.1fs — %@",
                            attempt, next.title, selfMix ? " (self-mix)" : "",
                            String(describing: plan.style),
                            plan.cueSeconds, plan.fadeSeconds, plan.transitionEndSeconds,
                            shown
                        )



                        PlaybackDebugLog.shared.record(
                            "automix \(Self.tierName(plan)) [plan \(attempt)] → \(next.title): "
                                + String(format: "cue %.1fs, window %.1fs, end %.1fs. ", plan.cueSeconds, plan.fadeSeconds, plan.transitionEndSeconds)
                                + (shown.isEmpty ? "no reason given" : shown),
                            about: nextId
                        )
                        await MainActor.run { [weak self] in
                            guard let self, self.queueNextRevision == revision else { return }
                            self.adoptAutomixPlan(plan)
                        }
                    }





                    let settled = Self.fileSettled(currentSource) && Self.fileSettled(resolved.source)
                    if settled { return }
                    let lead = max(plan.fadeSeconds, safetyFade) + 8




                    let planned = (Self.fileBytes(currentSource), Self.fileBytes(resolved.source))
                    while true {
                        let remaining = outgoingDuration - engine.positionSeconds()
                        if outgoingDuration > 0, remaining < lead { return }
                        try await Task.sleep(nanoseconds: 2_000_000_000)
                        if Self.fileSettled(currentSource) && Self.fileSettled(resolved.source) { break }
                        if Self.grewMeaningfully(from: planned.0, to: Self.fileBytes(currentSource))
                            || Self.grewMeaningfully(from: planned.1, to: Self.fileBytes(resolved.source)) { break }
                    }
                    attempt += 1
                }
            } catch is CancellationError {
                return
            } catch {


                await MainActor.run { [weak self] in
                    guard let self, self.playGeneration == generation,
                          self.queueNextRevision == revision else { return }
                    PlaybackDebugLog.shared.record("next-track preparation failed: \(error.localizedDescription)", about: nextId)


                    if self.markUnplayable(next, after: error) {
                        PlaybackDebugLog.shared.record("stepping over it; preparing the track after it instead", about: nextId)
                        self.syncEngineQueueNext()
                    }
                }
            }
        }
    }
}
