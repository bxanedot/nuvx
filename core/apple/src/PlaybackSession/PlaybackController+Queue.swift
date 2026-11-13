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

    func play(
        _ entries: [QueueEntry],
        at index: Int = 0,
        context: String? = nil,
        contextID: String? = nil,
        shuffleRequested: Bool? = nil,
        shuffleStart: Bool = false,
        boundedContext: Bool = false,
        continuationToken: String? = nil
    ) {
        noteLocalIntent()
        guard entries.indices.contains(index) else { return }
        let entry = entries[index]
        if boundedContext, current?.id == entry.id, playbackContextID == contextID,
           !shuffleStart, shuffleRequested == nil || shuffleRequested == shuffleEnabled,
           state == .buffering || state == .playing {
            return
        }


        if !boundedContext, current?.id == entry.id,
           playbackContextID == contextID,
           !shuffleStart, shuffleRequested == nil || shuffleRequested == shuffleEnabled,
           queue.filter({ $0.contextOrder != nil }).sorted(by: { ($0.contextOrder ?? 0) < ($1.contextOrder ?? 0) }).map(\.id) == entries.map(\.id),
           state == .buffering || state == .playing {
            return
        }
        let useShuffle = shuffleRequested ?? shuffleEnabled
        let canonicalEntries = entries.enumerated().map { offset, value in
            var value = value
            value.contextOrder = offset
            value.fromAutoplay = false
            return value
        }
        var entries = canonicalEntries
        var index = index
        if shuffleStart, useShuffle, !automixSequencingEnabled {
            entries = PlaybackQueuePolicy.startingWith(entries, seed: Int.random(in: entries.indices))
            index = 0
        }
        let ordered = PlaybackQueuePolicy.orderList(
            entries, after: index, automix: automixSequencingEnabled,
            shuffle: useShuffle, scores: tasteScores(entries)
        )
        contextContinuationTask?.cancel()
        contextContinuationTask = nil
        contextWindowGeneration &+= 1
        contextWindowInstalling = true
        let materialized: [QueueEntry]
        let materializedIndex: Int
        if boundedContext, contextID != nil {
            playbackContextEntries = canonicalEntries
            contextWindowEntries = ordered
            playbackContextContinuation = continuationToken
            let range = PlaybackQueueWindow.initialRange(count: ordered.count, selectedIndex: index)
            materialized = Array(ordered[range])
            materializedIndex = index - range.lowerBound
            contextWindowBaseIndex = range.lowerBound
            contextWindowCursor = range.upperBound
        } else {
            playbackContextEntries = []
            contextWindowEntries = []
            playbackContextContinuation = nil
            contextWindowCursor = 0
            contextWindowBaseIndex = 0
            materialized = ordered
            materializedIndex = index
        }
        let selected = materialized[materializedIndex]
        queue = materialized
        playbackContext = context
        playbackContextID = contextID
        shuffleEnabled = useShuffle
        queueEditRevision &+= 1
        autoplayRefresh.invalidate()
        restoredStart = nil
        repeatAllStash = []
        repeatAllStashSeed = nil
        playingIndex = materializedIndex
        contextWindowInstalling = false
        playbackContextRevision &+= 1
        startEngineIfNeeded()
        if engineLoadedId == selected.id, current?.source == selected.source, state == .playing {
            current = queue[materializedIndex]
            persistSession()
            maybeAutoplay(force: true)
            syncEngineQueueNext()
            scheduleSequencing()
            return
        }
        loadCurrent(materializedIndex)
        maintainContextWindow()
    }

    var automixSequencingEnabled: Bool {
        automixEnabled && PlatformSettings.shared.getBoolean(key: "automix_smart_sequence", default: true)
    }

    func isPlaybackContextActive(_ contextID: String) -> Bool {
        playbackContextState(contextID) != .inactive
    }

    func isPlaybackContextPlaying(_ contextID: String) -> Bool {
        playbackContextState(contextID) == .playing
    }

    func ownsPlaybackContextWindow(_ contextID: String) -> Bool {
        playbackContextID == contextID && !playbackContextEntries.isEmpty
            && playbackContextState(contextID) != .inactive
    }

    private func playbackContextState(_ contextID: String) -> PlaybackQueuePolicy.HeroState {
        PlaybackQueuePolicy.heroState(
            origin: playbackContextID, listID: contextID, hasQueue: !queue.isEmpty,
            stopped: state == .stopped, playingOrBuffering: state == .playing || state == .buffering
        )
    }

    func togglePlaybackContext(
        _ entries: [QueueEntry],
        at index: Int = 0,
        title: String,
        contextID: String,
        shuffleRequested: Bool? = nil,
        continuationToken: String? = nil
    ) {
        if isPlaybackContextActive(contextID) {
            togglePlayPause()
        } else {
            play(entries, at: index, context: title, contextID: contextID, shuffleRequested: shuffleRequested,
                 shuffleStart: true, boundedContext: true, continuationToken: continuationToken)
        }
    }



    func maintainContextWindow() {
        guard !contextWindowInstalling, contextWindowGeneration > 0,
              playbackContextID != nil, !playbackContextEntries.isEmpty else { return }
        trimPlayedContextPrefix()
        let upcoming = max(0, queue.count - playingIndex - 1)
        let available = max(0, contextWindowEntries.count - contextWindowCursor)
        let append = PlaybackQueueWindow.appendCount(upcomingCount: upcoming, canonicalRemaining: available)
        if append > 0 {
            let end = contextWindowCursor + append
            queue.append(contentsOf: contextWindowEntries[contextWindowCursor..<end])
            contextWindowCursor = end
            queueEditRevision &+= 1
            syncEngineQueueNext()
            persistSession()
        }
        let remaining = max(0, contextWindowEntries.count - contextWindowCursor)
        if PlaybackQueueWindow.shouldFetchContinuation(
            upcomingCount: max(0, queue.count - playingIndex - 1),
            canonicalRemaining: remaining,
            hasContinuation: playbackContextContinuation?.isEmpty == false
        ) {
            fetchContextContinuation()
        }
    }

    private func trimPlayedContextPrefix() {
        guard repeatMode != .all, playingIndex > 1 else { return }
        let removeCount = playingIndex - 1
        let removedContextRows = queue.prefix(removeCount).filter { $0.contextOrder != nil }.count
        contextWindowInstalling = true
        queue.removeFirst(removeCount)
        playingIndex -= removeCount
        contextWindowBaseIndex += removedContextRows
        queueEditRevision &+= 1
        contextWindowInstalling = false
    }



    func prependPreviousContextEntry() -> Bool {
        guard playbackContextID != nil, contextWindowBaseIndex > 0,
              contextWindowEntries.indices.contains(contextWindowBaseIndex - 1) else { return false }
        contextWindowBaseIndex -= 1
        queue.insert(contextWindowEntries[contextWindowBaseIndex], at: 0)
        playingIndex = 0
        return true
    }

    private func fetchContextContinuation() {
        guard contextContinuationTask == nil,
              let token = playbackContextContinuation, !token.isEmpty,
              let contextID = playbackContextID else { return }
        let generation = contextWindowGeneration
        contextContinuationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for (attempt, delay) in [0, 2, 5, 15].enumerated() {
                if delay > 0 {
                    do { try await Task.sleep(for: .seconds(delay)) }
                    catch { return }
                }
                guard !Task.isCancelled, self.contextWindowGeneration == generation,
                      self.playbackContextID == contextID,
                      self.playbackContextContinuation == token else { return }
                do {
                    let page = try await InnertubeDetail.shared.more(token: token)
                    guard !Task.isCancelled, self.contextWindowGeneration == generation,
                          self.playbackContextID == contextID,
                          self.playbackContextContinuation == token else { return }
                    var known = Set(self.playbackContextEntries.map(\.id))
                    let fallbackArt = self.playbackContextEntries.first?.thumbnailUrl
                    var additions = page.songs.map { $0.asEntry(fallbackArt: fallbackArt) }
                        .filter { known.insert($0.id).inserted }
                    let firstOrder = self.playbackContextEntries.count
                    for index in additions.indices {
                        additions[index].contextOrder = firstOrder + index
                        additions[index].fromAutoplay = false
                    }
                    self.playbackContextEntries.append(contentsOf: additions)
                    self.contextWindowEntries.append(contentsOf: additions)
                    self.playbackContextContinuation = page.continuation == token || additions.isEmpty
                        ? nil : page.continuation
                    self.playbackContextRevision &+= 1
                    self.contextContinuationTask = nil
                    self.maintainContextWindow()
                    self.persistSession()
                    return
                } catch {
                    self.debugLog.record("detail continuation attempt \(attempt + 1) failed; retaining playback context token")
                }
            }
            if self.contextWindowGeneration == generation { self.contextContinuationTask = nil }
        }
    }







    var canRevertToOriginal: Bool {
        guard let entry = current, let id = entry.videoId, entry.source.hasPrefix("yt:") else { return false }
        return !OriginalVersion.shared.isPinned(videoId: id) && !playingYouTubesOwn
    }



    var canUpgradeQuality: Bool {
        guard let entry = current, let id = entry.videoId, entry.source.hasPrefix("yt:") else { return false }
        return OriginalVersion.shared.isPinned(videoId: id) || playingYouTubesOwn
    }








    private var playingYouTubesOwn: Bool {
        guard let entry = current, let origin = resolvedOrigin[entry.id] else { return false }
        return origin == .youtube || origin == .cache
    }









    func revertToOriginal() {
        guard let entry = current, let id = entry.videoId, entry.source.hasPrefix("yt:") else { return }
        OriginalVersion.shared.pin(videoId: id)



        Task { await StreamFileCache.shared.forget(videoId: id) }
        QualityUpgrade.forget(id)
        debugLog.record("reverted to YouTube's own upload", about: id)


        restartCurrent(keepingPosition: smartVersionAlignment)
    }









    func playVideoVersion(videoId: String, durationText: String?) {
        guard let entry = current, entry.source.hasPrefix("yt:"), entry.videoId != videoId,
              queue.indices.contains(playingIndex) else { return }
        var version = QueueEntry.youtube(
            videoId: videoId, title: entry.title, artist: entry.artist,
            thumbnailUrl: entry.thumbnailUrl, durationText: durationText, albumName: entry.albumName,
            artistId: entry.artistId, albumId: entry.albumId, fromAutoplay: entry.fromAutoplay
        )
        version.contextOrder = entry.contextOrder
        songVersions[videoId] = entry
        OriginalVersion.shared.pin(videoId: videoId)
        queue[playingIndex] = version
        debugLog.record("video toggle: the music video \(videoId) plays in place of the song", about: entry.id)
        restartCurrent(keepingPosition: smartVersionAlignment)
    }



    func playSongVersion() {
        guard let entry = current, let song = songVersions.removeValue(forKey: entry.id),
              queue.indices.contains(playingIndex) else { return }
        OriginalVersion.shared.unpin(videoId: entry.id)
        queue[playingIndex] = song
        debugLog.record("video toggle: the song plays again in place of \(entry.id)", about: song.id)
        restartCurrent(keepingPosition: smartVersionAlignment)
    }







    func upgradeQuality() {
        guard let entry = current, let id = entry.videoId else { return }
        OriginalVersion.shared.unpin(videoId: id)
        QualityUpgrade.askByHand(id)
        debugLog.record("asked again for a better copy", about: id)
        restartCurrent(keepingPosition: smartVersionAlignment)
    }



    func restartCurrent(keepingPosition: Bool) {
        guard queue.indices.contains(playingIndex) else { return }
        let resumeAt = keepingPosition ? position : 0


        playGeneration &+= 1
        dolbyRenderer?.stop()
        dolbyRenderer = nil
        engineLoadedId = nil
        loadedSourcePath = nil
        loadedSourceHeaders = [:]
        sourceHeadersByPath.removeAll(keepingCapacity: true)
        loadCurrent(playingIndex, startAt: resumeAt)
    }




    var outputDevice: OutputDeviceRec { engine.outputDevice() }

    var outputHealth: OutputHealthRec { engine.outputHealth() }

    func persistSession() {
        guard !queue.isEmpty, queue.indices.contains(playingIndex) else { return }
        PlatformSettings.shared.putString(key: "last_playback_context", value: playbackContext ?? "")
        var tracks = queue
        if duration > 0, tracks.indices.contains(playingIndex) {
            tracks[playingIndex].durationText = QueueEntry.formatDuration(duration)
        }
        LastPlayed.save(
            tracks: tracks,
            index: playingIndex,
            position: position,
            repeatMode: repeatMode,
            shuffleEnabled: shuffleEnabled,
            volume: volume,
            contextID: playbackContextID, contextTitle: playbackContext,
            contextEntries: playbackContextEntries,
            orderedContextEntries: contextWindowEntries,
            contextContinuation: playbackContextContinuation,
            contextCursor: contextWindowCursor,
            contextBaseIndex: contextWindowBaseIndex
        )
    }



    private func rememberRestoredStart(_ position: Double, of entryId: String? = nil) {
        let id = entryId
            ?? (queue.indices.contains(playingIndex) ? queue[playingIndex].id : current?.id)
        restoredStart = id.map { (entryId: $0, position: position) }
    }

    func restoreSession() {
        guard let snap = LastPlayed.load() else { return }
        let savedContext = PlatformSettings.shared.getString(key: "last_playback_context", default: "")
        playbackContext = snap.contextTitle ?? (savedContext.isEmpty ? nil : savedContext)
        playbackContextID = snap.contextID
        contextContinuationTask?.cancel()
        contextContinuationTask = nil
        contextWindowGeneration &+= 1
        contextWindowInstalling = true
        playbackContextEntries = snap.contextEntries
        contextWindowEntries = snap.orderedContextEntries
        playbackContextContinuation = snap.contextContinuation
        contextWindowCursor = min(max(0, snap.contextCursor), contextWindowEntries.count)
        contextWindowBaseIndex = min(max(0, snap.contextBaseIndex), contextWindowEntries.count)
        queue = snap.tracks
        playingIndex = snap.index
        contextWindowInstalling = false
        current = queue[snap.index]
        position = snap.position
        duration = current?.durationSeconds ?? 0
        repeatMode = snap.repeatMode
        shuffleEnabled = snap.shuffleEnabled
        volume = snap.volume
        repeatAllStash = []
        repeatAllStashSeed = nil
        rememberRestoredStart(snap.position, of: current?.id)
        maintainContextWindow()
        dolbyRenderer?.stop()
        dolbyRenderer = nil
        engineLoadedId = nil
        loadedSourcePath = nil
        loadedSourceHeaders = [:]
        sourceHeadersByPath.removeAll(keepingCapacity: true)
        state = .paused
        if let entry = current {
            nowPlaying.update(
                id: entry.id, title: entry.title, artist: entry.artist,
                duration: duration, artworkData: entry.artworkData,
                thumbnailUrl: entry.thumbnailUrl, isPlaying: false, position: position
            )
            widgetPublisher.publish(entry: entry, isPlaying: false,
                                    canNext: playingIndex + 1 < queue.count,
                                    canPrevious: playingIndex > 0)



            fetchLyrics(for: entry)
        }
    }

    func playNext(_ entry: QueueEntry) {
        playNext([entry])
    }








    func playNext(_ entries: [QueueEntry]) {
        guard !entries.isEmpty else { return }
        noteLocalIntent()
        let wasEmpty = queue.isEmpty
        let prepared = entries.map { entry -> QueueEntry in
            var entry = entry
            entry.contextOrder = nil
            entry.fromAutoplay = false
            return entry
        }


        for entry in prepared {
            removeAutoplayDuplicate(of: entry)
        }
        let at = min(playingIndex + 1, queue.count)
        queue.insert(contentsOf: prepared, at: at)
        queueEditRevision &+= 1
        persistSession()

        if wasEmpty, isIdle {
            playingIndex = 0
            loadCurrent(0)
            return
        }
        syncEngineQueueNext()
    }


    func playRadio(_ entry: QueueEntry, context: String? = nil) {
        play([entry], at: 0, context: context)
    }


    func moveQueue(from source: IndexSet, to destination: Int) {
        guard let from = source.first else { return }
        let media3 = destination > from ? destination - 1 : destination
        moveQueueItem(from: from, to: media3)
    }



    func moveQueueItem(from: Int, to requested: Int) {
        guard queue.indices.contains(from), from != playingIndex else { return }
        let section = autoplaySectionStart
        let inMix = from >= section
        let lo = inMix ? section : playingIndex + 1
        let hi = inMix ? queue.count : section
        guard from >= lo, from < hi || (inMix && from < queue.count) else { return }
        let maxTo = inMix ? queue.count - 1 : max(lo, hi - 1)
        var to = min(max(requested, lo), maxTo)
        if !inMix { to = min(to, hi - 1) }
        if to == from || to < lo { return }
        var copy = queue
        var item = copy.remove(at: from)
        if !inMix { item.contextOrder = nil }
        let insertAt = min(max(to, 0), copy.count)
        copy.insert(item, at: insertAt)
        queue = copy
        queueEditRevision &+= 1
        persistSession()
        syncEngineQueueNext()
    }


    var autoplaySectionStart: Int {
        Self.autoplaySectionStart(fromAutoplay: queue.map(\.fromAutoplay), currentIndex: playingIndex)
    }


    var firstMovableQueueIndex: Int {
        min(playingIndex + 1, autoplaySectionStart)
    }

    static func autoplaySectionStart(fromAutoplay: [Bool], currentIndex: Int) -> Int {
        let after = min(max(currentIndex + 1, 0), fromAutoplay.count)
        if after >= fromAutoplay.count { return fromAutoplay.count }
        return (after..<fromAutoplay.count).first { fromAutoplay[$0] } ?? fromAutoplay.count
    }










    func addToQueue(_ entry: QueueEntry) {
        noteLocalIntent()
        let wasEmpty = queue.isEmpty
        var entry = entry
        entry.contextOrder = nil
        entry.fromAutoplay = false
        removeAutoplayDuplicate(of: entry)
        let at = min(autoplaySectionStart, queue.count)
        queue.insert(entry, at: at)
        queueEditRevision &+= 1
        persistSession()
        if wasEmpty, isIdle {

            playingIndex = 0
            loadCurrent(0)
            return
        }
        syncEngineQueueNext()
    }

    private func removeAutoplayDuplicate(of entry: QueueEntry) {
        let duplicateIndices = queue.indices.filter {
            $0 > playingIndex && queue[$0].fromAutoplay && queue[$0].id == entry.id
        }
        guard !duplicateIndices.isEmpty else { return }
        queue.remove(atOffsets: IndexSet(duplicateIndices))
    }


    private var isIdle: Bool {
        engineLoadedId == nil && state != .playing && state != .buffering
    }


    func withLocalIntentSuppressed<T>(_ body: () -> T) -> T {
        intentSuppression += 1
        defer { intentSuppression -= 1 }
        return body()
    }

    func noteLocalIntent() {
        guard intentSuppression == 0 else { return }
        onLocalIntent?()
    }
}
