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














    struct ResolvedSource: Sendable {
        let source: String
        let headers: [String: String]
        let kbps: Int
        var lossless: Bool = false
        var codec: String? = nil
        var durationSec: Int? = nil



        var loudnessDb: Double? = nil
        var clientName: String? = nil
        var resolutionAt: TimeInterval? = nil
        var origin: Origin = .other
        var youtubeVideoId: String? = nil

        enum Origin: String, Sendable { case local, cache, youtube, substitute, other }

        var format: QualityUpgrade.Format {
            QualityUpgrade.Format(
                codec: codec,
                kbps: kbps > 0 ? kbps : nil,
                lossless: lossless
            )
        }

        func asCandidate() -> QualityUpgrade.Candidate {
            QualityUpgrade.Candidate(
                url: source, headers: headers, format: format, durationSec: durationSec
            )
        }
    }

    struct ResolveOutcome: Sendable {
        let source: ResolvedSource
        let leftover: Task<QualityUpgrade.Candidate?, Never>?
        var decisionNote: String? = nil
    }

    struct ResolvePrefs: Sendable {
        let maxKbps: Int
        let wantLossless: Bool
        let jiosaavn: Bool
        let canSubstitute: Bool
        let preferMusicOnly: Bool

        @MainActor
        static func current() -> ResolvePrefs {
            let streamQuality = AppSettings.shared.effectiveAudioQuality(metered: NetworkQuality.shared.metered).name
            NSLog("[Nuvx] playback quality requested=\(streamQuality) network=\(NetworkQuality.shared.metered ? "metered" : "unmetered")")
            return ResolvePrefs(
                maxKbps: Int(NetworkQuality.shared.maxKbps),
                wantLossless: streamQuality == "LOSSLESS",
                jiosaavn: PlatformSettings.shared.getBoolean(key: "jiosaavn_enabled", default: true),
                canSubstitute: QualityUpgrade.canSubstituteForYouTube(),
                preferMusicOnly: PlatformSettings.shared.getBoolean(key: "prefer_music_only", default: false)
            )
        }
    }

    static func resolveSource(
        _ entry: QueueEntry, prefs: ResolvePrefs,
        preparationBudget: TimeInterval? = nil,
        freshResolution: Bool = false,
        latencyResolved: PlaybackLatencyRecorder? = nil
    ) async throws -> ResolveOutcome {
        await DownloadStore.shared.waitUntilReady()
        try Task.checkCancellation()
        if !entry.isLocal { await StreamFileCache.shared.remember(entry) }
        let pinnedOriginal = entry.source.hasPrefix("yt:") && OriginalVersion.shared.isPinned(videoId: entry.id)
        if let asset = await DownloadStore.shared.playbackAsset(for: entry, onlyOriginal: pinnedOriginal) {
            await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cached", "download")
            return ResolveOutcome(source: ResolvedSource(source: asset.path, headers: [:], kbps: asset.kbps,
                lossless: asset.lossless, codec: asset.codec, origin: .local, youtubeVideoId: asset.youtubeVideoId), leftover: nil)
        }
        if !freshResolution, !entry.isLocal,
           let cached = await StreamFileCache.shared.path(for: entry.id, allowQualityFallback: true,
                onlyOriginal: entry.source.hasPrefix("yt:") && OriginalVersion.shared.isPinned(videoId: entry.id)) {
            let metadata = await StreamFileCache.shared.metadata(at: cached)
            let musicOnlyVideo = prefs.preferMusicOnly && entry.isVideo && !OriginalVersion.shared.isPinned(videoId: entry.id)
            if (!musicOnlyVideo || (metadata?.playbackMediaId == entry.id && metadata?.recordingVerified == true)),
               let verified = NativeStreamVerifier.verifyCachedFile(cached) {
            await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cached", "stream cache")
            return ResolveOutcome(source: ResolvedSource(source: cached, headers: [:],
                kbps: verified.estimatedKbps.map(Int.init) ?? metadata?.kbps ?? 0,
                lossless: verified.format.losslessPcm, codec: verified.format.codec,
                durationSec: metadata?.durationSeconds, loudnessDb: metadata?.relativeLoudnessDb,
                clientName: "cache", origin: .cache, youtubeVideoId: metadata?.youtubeVideoId), leftover: nil)
            }
        }
        if entry.source.hasPrefix("saavn:") || entry.id.hasPrefix("saavn:") {
            let id = entry.source.hasPrefix("saavn:") ? String(entry.source.dropFirst(6)) : String(entry.id.dropFirst(6))
            if let hit = await JioSaavn.streamURL(for: id) {
                await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", "JioSaavn")
                return ResolveOutcome(
                    source: ResolvedSource(
                        source: hit.url, headers: [:], kbps: hit.kbps,
                        lossless: false, origin: .substitute
                    ),
                    leftover: nil
                )
            }
            throw InnertubeStreamResolver.StreamError(message: "JioSaavn had no stream")
        }
        if entry.source.hasPrefix("src:") || entry.id.hasPrefix("src:") {



            let key = entry.source.hasPrefix("src:") ? entry.source : entry.id
            let report = PlaybackLookupReport()
            if let stream = await SourceSearch.shared.stream(
                key: key, title: entry.title, artist: entry.artist,


                durationSec: entry.durationSeconds > 0 ? Int(entry.durationSeconds.rounded()) : nil,
                diagnostic: report.record
            ) {
                await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", "source substitute")
                return ResolveOutcome(
                    source: ResolvedSource(
                        source: stream.url, headers: stream.headers, kbps: stream.kbps ?? 0,
                        lossless: stream.lossless, codec: stream.codec,
                        durationSec: stream.durationSec, origin: .substitute
                    ),
                    leftover: nil, decisionNote: report.explanation
                )
            }
            throw InnertubeStreamResolver.StreamError(message: "That source had no stream")
        }
        #if DEBUG
        if entry.source.hasPrefix("dolby-test:") {
            await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", "fixture")
            return ResolveOutcome(source: ResolvedSource(source: String(entry.source.dropFirst(11)), headers: [:], kbps: 0, codec: "eac3-joc"), leftover: nil)
        }
        #endif
        guard entry.source.hasPrefix("yt:") else {
            await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", "WebDAV/local")





            return ResolveOutcome(
                source: ResolvedSource(
                    source: entry.source,
                    headers: WebDavBridge.shared.playbackHeaders(fileUrl: entry.source),
                    kbps: 0,
                    codec: ["ac3", "ec3", "eac3"].contains(URL(string: entry.source)?.pathExtension.lowercased() ?? "") ? URL(string: entry.source)?.pathExtension : nil,
                    origin: .local
                ),
                leftover: nil
            )
        }
        let videoId = String(entry.source.dropFirst(3))
        if !freshResolution, !(prefs.preferMusicOnly && entry.isVideo && !OriginalVersion.shared.isPinned(videoId: videoId)),
           let growing = streamGate.lock.withLock({ streamGate.growing[videoId] }),
           FileManager.default.fileExists(atPath: growing),
           !FileManager.default.fileExists(atPath: growing + ".failed"),
           !FileManager.default.fileExists(atPath: growing + ".playback-cancelled") {
            await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cached", "stream cache")
            return ResolveOutcome(
                source: ResolvedSource(
                    source: growing, headers: [:], kbps: 0, durationSec: streamGate.lock.withLock { streamGate.durations[videoId] }, origin: .cache
                ),
                leftover: nil
            )
        }





        guard await NetworkQuality.shared.connected else { throw URLError(.notConnectedToInternet) }
        if OriginalVersion.shared.isPinned(videoId: videoId) {
            let source = try await Self.resolveYouTube(videoId: videoId, prefs: prefs)
            await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime,
                                   "cold", source.clientName ?? "unknown")
            return ResolveOutcome(source: source, leftover: nil)
        }
        return try await resolveWithModulePriority(
            entry, videoId: videoId, prefs: prefs, preparationBudget: preparationBudget, latencyResolved: latencyResolved
        )
    }




    private static func resolveWithModulePriority(
        _ entry: QueueEntry, videoId: String, prefs: ResolvePrefs,
        preparationBudget: TimeInterval?,
        latencyResolved: PlaybackLatencyRecorder? = nil
    ) async throws -> ResolveOutcome {
        let report = PlaybackLookupReport()
        let lookup = Task<QualityUpgrade.Candidate?, Never> {
            if preparationBudget != nil && prefs.canSubstitute && !entry.isVideo {
                let stream = await SourceSubstitution.prefetch(
                    title: entry.title, artist: entry.artist,
                    durationSec: entry.durationSeconds > 0 ? Int(entry.durationSeconds.rounded()) : nil,
                    album: entry.albumName,
                    isExplicit: entry.isExplicit.map { KotlinBoolean(value: $0) },
                    diagnostic: report.record
                )
                return stream.map { QualityUpgrade.Candidate(
                    url: $0.url, headers: $0.headers,
                    format: QualityUpgrade.Format(codec: $0.codec, kbps: $0.kbps, lossless: $0.lossless),
                    durationSec: $0.durationSec
                ) }
            }
            return await Self.resolveSubstitute(entry, prefs: prefs, diagnostic: report.record)?.asCandidate()
        }
        let fallback = Task<ResolvedSource, Error> {
            try await Self.resolveYouTube(videoId: videoId, prefs: prefs)
        }







        if prefs.preferMusicOnly, entry.isVideo {
            if let stream = await lookup.value {
                _ = try await fallback.value
                let selected = ResolveOutcome(
                    source: ResolvedSource(
                        source: stream.url, headers: stream.headers,
                        kbps: stream.format.kbps ?? 0,
                        lossless: stream.format.lossless, codec: stream.format.codec,
                        durationSec: stream.durationSec,
                        origin: .substitute
                    ),
                    leftover: nil
                )
                let buffered = try await bufferPreparedSource(selected, mediaId: entry.id)
                if buffered.source.source.hasPrefix("/"),
                   await RecordingIdentityVerifier.matches(videoId: videoId, candidate: buffered.source.source,
                                                           allowTimingEdits: true, urgent: true) {
                    await StreamFileCache.shared.markRecordingVerified(mediaID: entry.id,
                        cacheKey: "\(entry.id)#prepared-\(DiskCache.hashName(stream.url))", sourcePath: buffered.source.source)
                    await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", "verified source substitute")
                    return buffered
                }
                PlaybackDebugLog.shared.record("music-only candidate rejected: recording identity could not be verified", about: videoId)
            }
            let source = try await fallback.value
            await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime,
                                   "cold", source.clientName ?? "unknown")
            return ResolveOutcome(source: source, leftover: nil, decisionNote: source.lossless
                ? "A preferred source supplied verified lossless audio." : report.explanation)
        }

        let race = PlaybackSourceRace<QualityUpgrade.Candidate, ResolvedSource>(preferPrimary: false)
        _ = Task { await race.finishLookup(await lookup.value) }
        _ = Task { await race.finishFallback(try? await fallback.value) }

        let waitSeconds = 0.0
        switch await race.wait(timeoutNanoseconds: UInt64(waitSeconds * 1_000_000_000)) {
        case .preferred(let stream):


            let source = try await fallback.value
            await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime, "cold", source.clientName ?? "YouTube")
            return ResolveOutcome(source: source, leftover: Task { stream },
                decisionNote: "A preferred copy is waiting for recording verification.")
        case .fallback(let source):


            await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime,
                                   "cold", source.clientName ?? "unknown")
            return ResolveOutcome(source: source, leftover: lookup, decisionNote: report.explanation ?? (prefs.canSubstitute
                ? "Preferred sources did not provide a verified stream before the playback deadline."
                : "YouTube is the selected source."))
        case .timedOut:


            do {
                let source = try await fallback.value
                await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime,
                                       "cold", source.clientName ?? "unknown")
                return ResolveOutcome(source: source, leftover: lookup, decisionNote: report.explanation ?? (prefs.canSubstitute
                ? "Preferred sources did not provide a verified stream before the playback deadline."
                : "YouTube is the selected source."))
            } catch {


                let source = try await recoverRefusedTrack(entry, videoId: videoId, prefs: prefs, lookup: lookup, error: error)
                await latencyResolved?(ProcessInfo.processInfo.systemUptime, "cold", source.clientName ?? "recovered")
                return ResolveOutcome(source: source, leftover: nil, decisionNote: source.lossless
                ? "A preferred source supplied verified lossless audio." : report.explanation)
            }
        case .cancelled:
            lookup.cancel()
            fallback.cancel()
            throw CancellationError()
        case .none:


            let source: ResolvedSource
            do {
                source = try await fallback.value
            } catch {
                source = try await recoverRefusedTrack(entry, videoId: videoId, prefs: prefs, lookup: lookup, error: error)
            }
            await latencyResolved?(source.resolutionAt ?? ProcessInfo.processInfo.systemUptime,
                                   "cold", source.clientName ?? "unknown")
            return ResolveOutcome(source: source, leftover: nil, decisionNote: source.lossless
                ? "A preferred source supplied verified lossless audio." : report.explanation)
        }
    }






    private static func recoverRefusedTrack(
        _ entry: QueueEntry, videoId: String, prefs: ResolvePrefs,
        lookup: Task<QualityUpgrade.Candidate?, Never>, error: Error
    ) async throws -> ResolvedSource {
        if let stream = await lookup.value { QualityUpgrade.shelve(videoId, stream: stream) }

        if !InnertubeStreamResolver.StreamError.isBotCheck(error),
           let twin = await counterpart(of: entry, videoId: videoId) {
            do {
                var source = try await resolveYouTube(videoId: twin, prefs: prefs)
                source.clientName = "counterpart " + (source.clientName ?? "unknown")
                PlaybackDebugLog.shared.record("YouTube refused; playing its counterpart \(twin)", about: videoId)
                return source
            } catch {
                PlaybackDebugLog.shared.record("counterpart \(twin) failed too: \(error.localizedDescription)", about: videoId)
            }
        }
        throw error
    }



    private static func counterpart(of entry: QueueEntry, videoId: String) async -> String? {
        let songSeconds = entry.durationSeconds
        guard songSeconds > 0 else { return nil }
        let candidates: [CounterpartCandidate] = await withCheckedContinuation { continuation in
            VideoVersionBridge.shared.find(
                videoId: videoId, title: entry.title, artist: entry.artist,
                durationText: entry.durationText ?? QueueEntry.formatDuration(songSeconds),
                callback: CounterpartAdapter { json, _ in
                    continuation.resume(returning: json.flatMap {
                        try? JSONDecoder().decode([CounterpartCandidate].self, from: Data($0.utf8))
                    } ?? [])
                }
            )
        }
        return candidates.first { candidate in
            guard candidate.source == "counterpart", candidate.videoId != videoId,
                  let seconds = QueueEntry.seconds(fromDurationText: candidate.durationText) else { return false }
            return abs(seconds - songSeconds) <= 3
        }?.videoId
    }




























    private static func resolveYouTube(
        videoId: String, prefs: ResolvePrefs
    ) async throws -> ResolvedSource {
        var refusedClients: [String] = []
        var rebuilt = false
        func record(_ error: Error) {




            if let streamError = error as? InnertubeStreamResolver.StreamError {
                PlaybackDebugLog.shared.record(streamError.raw, about: videoId)
            }
        }

        func rebuildAndResolve(after error: Error) async throws -> ResolvedYouTubeStream {


            guard !rebuilt, !InnertubeStreamResolver.StreamError.isBotCheck(error) else { throw error }
            rebuilt = true
            PlaybackDebugLog.shared.record(
                "no client would serve this track (\(refusedClients.isEmpty ? "extraction failed" : refusedClients.joined(separator: ", "))); rebuilding the session and asking again",
                about: videoId
            )
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                PlayerBridge.shared.rebuildSession(videoId: videoId, callback: DoneAdapter { done.resume() })
            }
            refusedClients.removeAll()
            do {
                return try await InnertubeStreamResolver.shared.resolve(videoId: videoId, maxKbps: prefs.maxKbps)
            } catch {
                record(error)
                throw error
            }
        }

        func nextStream() async throws -> ResolvedYouTubeStream {
            let exhausted = InnertubeStreamResolver.StreamError(
                message: "Every client refused this track (\(refusedClients.joined(separator: ", ")))"
            )
            guard refusedClients.count < Self.clientsPerSession else {
                return try await rebuildAndResolve(after: exhausted)
            }
            do {
                return try await InnertubeStreamResolver.shared.resolve(videoId: videoId, maxKbps: prefs.maxKbps)
            } catch {

                record(error)
                return try await rebuildAndResolve(after: error)
            }
        }
        var stream: ResolvedYouTubeStream
        do {
            stream = try await InnertubeStreamResolver.shared.resolve(videoId: videoId, maxKbps: prefs.maxKbps)
        } catch {
            record(error)
            stream = try await rebuildAndResolve(after: error)
        }
        var resolvedAt = ProcessInfo.processInfo.systemUptime
        var rangeRetry = 0
        while true {
            if let seconds = stream.durationSeconds, seconds > 0 {
                streamGate.lock.withLock { streamGate.durations[videoId] = seconds }
            }
            do {
                let localPath = try await streamViaKtor(
                    videoId: videoId, url: stream.url, headers: stream.headers,
                    codec: stream.mimeType.lowercased().contains("opus") ? "Opus" : "AAC",
                    kbps: stream.kbps, relativeLoudnessDb: stream.loudnessDb,
                    youtubeVideoId: videoId, durationSeconds: stream.durationSeconds,
                    maxKbps: prefs.maxKbps, expectedMimeType: stream.mimeType,
                    firstRangeRetry: rangeRetry
                )
                return ResolvedSource(
                    source: localPath, headers: [:], kbps: stream.kbps,
                    durationSec: stream.durationSeconds, loudnessDb: stream.loudnessDb,
                    clientName: stream.clientName, resolutionAt: resolvedAt,
                    origin: .youtube, youtubeVideoId: videoId
                )
            } catch {
                let reason = (error as? InnertubeStreamResolver.StreamError)?.message
                let rejected = StreamDownloadBridge.shared.isInvalidFirstRange(message: reason)
                    || StreamDownloadBridge.shared.isRefusal(message: reason)
                guard rejected else {
                    DebugLog.shared.d(message: "\(videoId): stream failed: \(reason ?? "\(error)")")
                    return ResolvedSource(
                        source: stream.url, headers: stream.headers, kbps: stream.kbps,
                        durationSec: stream.durationSeconds, loudnessDb: stream.loudnessDb,
                        clientName: stream.clientName, resolutionAt: resolvedAt,
                        origin: .youtube, youtubeVideoId: videoId
                    )
                }
                refusedClients.append(stream.clientName ?? "unknown")
                rangeRetry += 1
                DebugLog.shared.d(message: "\(videoId): \(stream.clientName ?? "a client") refused (\(reason ?? "no reason")); asking another")
                stream = try await nextStream()
                resolvedAt = ProcessInfo.processInfo.systemUptime
            }
        }
    }





    private static let clientsPerSession = 4



















    static func resolveSubstitute(
        _ entry: QueueEntry, prefs: ResolvePrefs, waitForAll: Bool = false,
        playingDurationSec: Int? = nil, playing: QualityUpgrade.Format? = nil,
        diagnostic: @escaping @Sendable (String) -> Void = { _ in }
    ) async -> ResolvedSource? {



        guard prefs.canSubstitute else { return nil }

        let stream = await SourceSubstitution.substitute(
            title: entry.title,
            artist: entry.artist,
            durationSec: playingDurationSec ?? (entry.durationSeconds > 0 ? Int(entry.durationSeconds.rounded()) : nil),
            album: entry.albumName,
            isExplicit: entry.isExplicit.map { KotlinBoolean(value: $0) },
            isVideo: entry.isVideo,
            playing: (playing ?? (waitForAll ? QualityUpgrade.Format(codec: nil, kbps: nil, lossless: false) : nil)).map { SourceSubstitution.Format(codec: $0.codec, kbps: $0.kbps, lossless: $0.lossless) },
            diagnostic: diagnostic
        )
        guard let stream else { return nil }
        return ResolvedSource(
            source: stream.url,
            headers: stream.headers,
            kbps: stream.kbps ?? 0,
            lossless: stream.lossless, codec: stream.codec,
            durationSec: stream.durationSec,
            origin: .substitute
        )
    }

    private static func resolveCustom(title: String, artist: String) async -> ResolvedSource? {
        await withCheckedContinuation { cont in
            SourceBridge.shared.resolveCustom(title: title, artist: artist, quality: "LOSSLESS", callback: CustomStreamAdapter { json, _ in
                guard let json, let data = json.data(using: .utf8),
                      let hit = try? JSONDecoder().decode(CustomHit.self, from: data) else {
                    cont.resume(returning: nil)
                    return
                }
                cont.resume(returning: ResolvedSource(
                    source: hit.url, headers: [:], kbps: hit.kbps,
                    lossless: hit.lossless == true, origin: .substitute
                ))
            })
        }
    }

    private struct CustomHit: Codable {
        let url: String
        let kbps: Int
        let lossless: Bool?
    }



    static func bufferPreparedSource(_ outcome: ResolveOutcome, mediaId: String) async throws -> ResolveOutcome {
        let chosen = outcome.source
        guard chosen.origin == .substitute,
              chosen.source.hasPrefix("https://") || chosen.source.hasPrefix("http://"),
              !PlaybackCodecCapabilities.shared.canRenderDolby(codec: chosen.codec, headers: chosen.headers) else {
            return outcome
        }
        do {
            let path = try await streamViaKtor(
                videoId: "\(mediaId)#prepared-\(DiskCache.hashName(chosen.source))",
                url: chosen.source, headers: chosen.headers,
                codec: chosen.codec ?? "unknown", kbps: chosen.kbps,
                durationSeconds: chosen.durationSec
            )
            try Task.checkCancellation()
            let buffered = ResolvedSource(
                source: path, headers: [:], kbps: chosen.kbps,
                lossless: chosen.lossless, codec: chosen.codec,
                durationSec: chosen.durationSec, origin: .substitute
            )
            return ResolveOutcome(source: buffered, leftover: outcome.leftover, decisionNote: outcome.decisionNote)
        } catch is CancellationError {
            outcome.leftover?.cancel()
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            PlaybackDebugLog.shared.record("next-track buffer unavailable; retaining selected stream", about: mediaId)
            return outcome
        }
    }



    func warmUpcoming(around index: Int, generation: UInt64) {
        guard state == .playing || state == .buffering || state == .paused else { return }
        guard NetworkQuality.shared.connected, repeatMode != .one else {
            urlPrefetchTask?.cancel(); urlPrefetchFingerprint = nil
            return
        }







        let metered = NetworkQuality.shared.metered
        let prefetchCount = metered ? 1 : 3
        let prefetch = queue.dropFirst(index + 1).prefix(prefetchCount).filter {
            $0.source.hasPrefix("yt:") && !$0.id.hasPrefix("saavn:")
        }
        let prefs = ResolvePrefs.current()
        let fingerprint = "\(prefs.maxKbps)|metered=\(metered)|" + prefetch.map(\.id).joined(separator: "|")
        guard fingerprint != urlPrefetchFingerprint else { return }
        urlPrefetchFingerprint = fingerprint
        urlPrefetchTask?.cancel()
        guard !prefetch.isEmpty else { return }
        PlaybackDebugLog.shared.record("latency: prefetch url \(prefetch.count) rows")
        urlPrefetchTask = Task.detached(priority: .utility) { [weak self] in
            for entry in prefetch {
                guard !Task.isCancelled else { return }
                let id = entry.id
                let stillQueued = await MainActor.run { [weak self] in
                    guard let self else { return false }
                    guard self.playGeneration == generation,
                          self.queue.contains(where: { $0.id == id }) else { return false }



                    return NetworkQuality.shared.connected && (!NetworkQuality.shared.metered || id == prefetch.first?.id)
                }
                guard stillQueued else { continue }
                let videoId = String(entry.source.dropFirst(3))
                if await StreamFileCache.shared.path(for: videoId, allowQualityFallback: true) != nil { continue }
                _ = try? await InnertubeStreamResolver.shared.resolve(videoId: videoId, maxKbps: prefs.maxKbps)
            }
        }
    }

    func refreshUpcomingURLPrefetch() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.warmUpcoming(around: self.playingIndex, generation: self.playGeneration)
        }
    }

    private final class StreamGate: @unchecked Sendable {
        let lock = NSLock()
        var tasks: [String: (id: UUID, task: Task<String, Error>)] = [:]


        var growing: [String: String] = [:]
        var growingQuality: [String: String] = [:]
        var growingKbps: [String: Int] = [:]
        var durations: [String: Int] = [:]
        var requests: [String: (mediaID: String, path: String?)] = [:]
        var retired: [String: String] = [:]
        var keepingMediaIDs: Set<String> = []
        var verificationPins: [String: Int] = [:]
    }

    private static let streamGate = StreamGate()

    static func retainVerificationStream(_ mediaID: String) {
        streamGate.lock.withLock { streamGate.verificationPins[mediaID, default: 0] += 1 }
    }
    static func releaseVerificationStream(_ mediaID: String) {
        streamGate.lock.withLock {
            let remaining = (streamGate.verificationPins[mediaID] ?? 1) - 1
            streamGate.verificationPins[mediaID] = remaining > 0 ? remaining : nil
        }
        releaseObsoleteStreams()
    }

    static func releaseObsoleteStreams(keeping selected: Set<String>? = nil) {
        let (mediaIDs, requests, retired) = streamGate.lock.withLock {
            if let selected { streamGate.keepingMediaIDs = selected }
            return (streamGate.keepingMediaIDs.union(streamGate.verificationPins.keys), streamGate.requests, streamGate.retired)
        }
        for (requestID, request) in requests {
            let base = String(request.mediaID.prefix(while: { $0 != "#" }))
            if !mediaIDs.contains(base), request.path.map({ sourceHasPlaybackReaders(source: $0) }) != true {
                StreamDownloadBridge.shared.cancel(downloadId: requestID)
            }
        }
        for (path, mediaID) in retired where !mediaIDs.contains(mediaID) && !sourceHasPlaybackReaders(source: path) {
            for suffix in ["", ".grow", ".len", ".complete", ".failed", ".playback-cancelled"] { try? FileManager.default.removeItem(atPath: path + suffix) }
            streamGate.lock.withLock { streamGate.retired[path] = nil }
        }
    }





    static func streamViaKtor(
        videoId: String, url: String, headers: [String: String], codec: String = "unknown", kbps: Int = 0, relativeLoudnessDb: Double? = nil, youtubeVideoId: String? = nil, durationSeconds: Int? = nil, maxKbps: Int = Int(Int32.max), expectedMimeType: String? = nil, firstRangeRetry: Int = 0
    ) async throws -> String {
        let taskKey = videoId + "|" + StreamFileCache.qualityIdentity + "|" + DiskCache.hashName(url)
        let flight: (id: UUID, task: Task<String, Error>) = streamGate.lock.withLock {
            if let existing = streamGate.tasks[taskKey] { return existing }
            let created = Task { try await streamViaKtorOnce(videoId: videoId, url: url, headers: headers, codec: codec, kbps: kbps, relativeLoudnessDb: relativeLoudnessDb, youtubeVideoId: youtubeVideoId, durationSeconds: durationSeconds, maxKbps: maxKbps, expectedMimeType: expectedMimeType, firstRangeRetry: firstRangeRetry) }
            let flight = (id: UUID(), task: created)
            streamGate.tasks[taskKey] = flight
            return flight
        }
        do {
            let path = try await flight.task.value
            streamGate.lock.withLock {
                if streamGate.tasks[taskKey]?.id == flight.id { streamGate.tasks[taskKey] = nil }
            }
            return path
        } catch {
            streamGate.lock.withLock {
                if streamGate.tasks[taskKey]?.id == flight.id { streamGate.tasks[taskKey] = nil }
            }
            throw error
        }
    }

    private static func streamViaKtorOnce(
        videoId: String, url: String, headers: [String: String], codec: String = "unknown", kbps: Int = 0, relativeLoudnessDb: Double? = nil, youtubeVideoId: String? = nil, durationSeconds: Int? = nil, maxKbps: Int = Int(Int32.max), expectedMimeType: String? = nil, firstRangeRetry: Int = 0
    ) async throws -> String {
        let qualityIdentity = StreamFileCache.qualityIdentity
        let requestID = "playback:" + UUID().uuidString
        streamGate.lock.withLock { streamGate.requests[requestID] = (videoId, nil) }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            let lock = NSLock()
            var resumed = false
            var openedPath: String?
            let ready = DownloadCallbackAdapter { path, message in
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                if let path {
                    openedPath = path
                    DiskCache.allowLockedPlayback(URL(fileURLWithPath: path))
                    streamGate.lock.withLock { streamGate.requests[requestID]?.path = path }
                    streamGate.lock.withLock { streamGate.growing[videoId] = path; streamGate.growingQuality[videoId] = qualityIdentity; streamGate.growingKbps[videoId] = kbps }
                    Task {
                        await StreamFileCache.shared.noteGrowing(videoId: videoId, path: path, kbps: kbps, quality: qualityIdentity, relativeLoudnessDb: relativeLoudnessDb, youtubeVideoId: youtubeVideoId, durationSeconds: durationSeconds)
                        continuation.resume(returning: path)
                    }
                } else {
                    continuation.resume(throwing: InnertubeStreamResolver.StreamError(
                        message: message ?? "Stream failed"))
                }
            }
            let done = DownloadCallbackAdapter { path, message in
                streamGate.lock.withLock { streamGate.requests[requestID] = nil }






                lock.lock()
                let waiting = !resumed
                let failedPath = openedPath
                resumed = true
                lock.unlock()
                if waiting, path == nil {
                    continuation.resume(throwing: InnertubeStreamResolver.StreamError(
                        message: message ?? "Stream ended before its first bytes"))
                    return
                }
                if waiting, let path { continuation.resume(returning: path) }
                if let path {
                    Task {
                        await StreamFileCache.shared.store(videoId, path: path, sourceIdentity: DiskCache.hashName(url), codec: codec, kbps: kbps, quality: qualityIdentity, relativeLoudnessDb: relativeLoudnessDb, youtubeVideoId: youtubeVideoId, durationSeconds: durationSeconds)
                        streamGate.lock.withLock {
                            if streamGate.growing[videoId] == path { streamGate.growing[videoId] = nil; streamGate.growingQuality[videoId] = nil; streamGate.growingKbps[videoId] = nil; streamGate.durations[videoId] = nil }
                            streamGate.retired[path] = String(videoId.prefix(while: { $0 != "#" }))
                        }
                        releaseObsoleteStreams()
                    }
                } else if let message {
                    print("[Playback] stream tail failed for \(videoId): \(message)")
                    if let failedPath {
                        streamGate.lock.withLock {
                            if streamGate.growing[videoId] == failedPath { streamGate.growing[videoId] = nil; streamGate.growingQuality[videoId] = nil; streamGate.growingKbps[videoId] = nil; streamGate.durations[videoId] = nil }
                            streamGate.retired[failedPath] = String(videoId.prefix(while: { $0 != "#" }))
                        }
                        Task { await StreamFileCache.shared.dropGrowing(videoId: videoId, path: failedPath) }
                        releaseObsoleteStreams()
                    }
                }
            }


            if let youtubeVideoId {
                StreamDownloadBridge.shared.streamTrackRequest(
                    requestId: requestID, videoId: youtubeVideoId, maxKbps: Int32(clamping: maxKbps),
                    url: url, headers: headers, expectedMimeType: expectedMimeType,
                    firstRangeRetry: Int32(clamping: firstRangeRetry),
                    ready: ready, done: done)
                return
            }
            StreamDownloadBridge.shared.streamSourceRequest(requestId: requestID, url: url, headers: headers, ready: ready, done: done)
        }
    }
}

private final class DoneAdapter: PlayerBridgeDoneCallback {
    private let handler: () -> Void
    init(_ handler: @escaping () -> Void) { self.handler = handler }
    func onDone() { handler() }
}

private struct CounterpartCandidate: Decodable {
    let videoId: String
    let durationText: String?
    let source: String?
}

private final class CounterpartAdapter: VideoVersionBridgeVideoVersionCallback {
    private let handler: (String?, String?) -> Void
    init(_ handler: @escaping (String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, message: String?) { handler(json, message) }
}
