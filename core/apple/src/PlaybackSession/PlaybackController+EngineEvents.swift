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
    func handleState(_ newState: PlaybackState) {
        guard dolbyRenderer == nil else { return }




        if state == .buffering && (newState == .playing || newState == .stopped) { return }


#if os(iOS)



        if (state == .paused && newState == .playing)
            || (state == .playing && newState == .paused) { return }
#endif
        if state == .paused && newState == .stopped { return }
        state = newState
        if let current {
            nowPlaying.update(
                id: current.id, title: current.title, artist: current.artist,
                duration: duration, artworkData: current.artworkData,
                thumbnailUrl: current.thumbnailUrl,
                isPlaying: newState == .playing
            )
        }
        widgetPublisher.publish(entry: current, isPlaying: newState == .playing,
                                canNext: canPlayNext, canPrevious: canPlayPrevious)
        if newState == .stopped {
            nowPlaying.stop()
        }
        if newState == .paused { persistSession() }
    }

    func handleHandoff(_ info: TrackInfoRec) {





        if repeatMode != .one {
            if let next = nextEntry, info.title == next.title, info.artist == next.artist,
               sourceHeadersByPath[info.source] != nil {

            } else if let current, info.title == current.title, info.artist == current.artist {



                loadedSourcePath = info.source
                if let headers = sourceHeadersByPath.removeValue(forKey: info.source) {
                    loadedSourceHeaders = headers
                }
                if info.durationSeconds > 0 { duration = info.durationSeconds }
                return
            } else {



                NSLog("[Nuvx] ignoring handoff for untracked voice %@ — %@", info.title, info.artist)
                return
            }
        }


        if repeatMode == .one {

        } else if let next = nextPlayableIndex(after: playingIndex) {

            playingIndex = next
        } else if repeatMode == .all, !queue.isEmpty {
            playingIndex = 0
        } else {
            return
        }
        let outgoingPosition = position
        let sameSong = current?.id == queue[playingIndex].id
        autoplayRefresh.invalidate()
        current = queue[playingIndex]
        if let current { streamRecovery.select(current.id, resetAttempts: true) }
        historyVideoID = current.flatMap(youtubeVideoID)
        duration = info.durationSeconds
        position = 0
        if let entry = current {
            if !sameSong {
                refreshArtwork(entry, source: info.source)
                fetchLyrics(for: entry)
                fetchCanvas(for: entry)
            }
            nerd = dolbyRenderer?.nerd ?? engine.nerdStats()
            racingLossless = QualityUpgrade.isRacing(entry.id)
            scrobbleArmed = false
            scrobbleSent = false
            PlaybackTrackerBridge.shared.onTrackChanged(positionSeconds: Int64(outgoingPosition))
            if let id = historyVideoID {
                QueueBuilderBridge.shared.rememberPlayed(videoId: id)
                PlaybackTrackerBridge.shared.onPlaying(videoId: id)
            }
            publishPresence()
            nowPlaying.update(
                id: entry.id, title: entry.title, artist: entry.artist,
                duration: info.durationSeconds, artworkData: entry.artworkData,
                thumbnailUrl: entry.thumbnailUrl, isPlaying: isPlaying, position: position
            )
            engineLoadedId = entry.id
            loadedSourcePath = info.source
            loadedSourceHeaders = sourceHeadersByPath.removeValue(forKey: info.source) ?? [:]
            beginSmartMixIfNeeded()
            if dolbyRenderer == nil { lookForBetterCopy(entry, codec: info.codec, kbps: info.kbps) }
        }
        persistSession()
        syncEngineQueueNext()
        scheduleSequencing()
        maybeAutoplay(lowWaterRefill: true)
    }

    func handleTrackEnded(_ reason: TrackEndReason, source: String) {
        guard state != .paused else { return }
        if reason == .error {
            _ = recoverInterruptedStream(source: source, explanation: "The audio decoder stopped; requesting a fresh stream at the same position.")
            return
        }
        guard reason == .natural else { return }




        if !source.isEmpty, let loaded = loadedSourcePath, loaded != source { return }
        if resumeAfterFailedDownload(source: source.isEmpty ? loadedSourcePath : source) { return }
        if historyVideoID != nil {
            PlaybackTrackerBridge.shared.onPlaybackFinished(positionSeconds: Int64(position))
        }
        if sleepAfterTrack {
            sleepAfterTrack = false
            pausePlayback(releaseAudioSession: true)
            try? engine.holdTrackEnd(hold: false)
            try? engine.setSleepGain(gain: 1)
            return
        }
        if let current, !scrobbleSent {
            ScrobbleBridge.shared.scrobble(
                artist: scrobbleArtist(for: current), title: current.title, album: current.albumName,
                durationSec: Swift.Int32(duration)
            )
            scrobbleSent = true
        }



        PlaybackDebugLog.shared.record("natural end without handoff; index=\(playingIndex) count=\(queue.count)", about: current?.id)
        dolbyRenderer?.stop()
        dolbyRenderer = nil
        engineLoadedId = nil
        switch repeatMode {
        case .one:



            restartCurrentAfterNaturalEnd()
        case .all:
            dolbyRenderer?.stop()
            dolbyRenderer = nil
            engineLoadedId = nil
            advanceToNext(userInitiated: false)
        case .off:
            if let next = nextPlayableIndex(after: playingIndex) {
                automaticLoadEntryId = queue[next].id
                loadCurrent(next, refreshAutoplay: false)
                return
            }
            state = .stopped
            position = 0
            nowPlaying.updateRate(0.0, position: 0)
            nowPlaying.stop()
            deactivateAudioSessionIfIdle()
        }
    }






    private func resumeAfterFailedDownload(source: String?) -> Bool {
        guard let source else { return false }
        let failures = engine.drainSourceErrors()
        let failed = FileManager.default.fileExists(atPath: source + ".failed") || failures.contains { $0.source == source }
        guard failed else { return false }
        return recoverInterruptedStream(source: source, explanation: "Playback was interrupted; requesting a fresh stream at the same position.")
    }




    private func restartCurrentAfterNaturalEnd() {
        guard queue.indices.contains(playingIndex) else { return }
        let index = playingIndex
        scrobbleArmed = false
        scrobbleSent = false
        dolbyRenderer?.stop()
        dolbyRenderer = nil
        engineLoadedId = nil
        loadCurrent(index, refreshAutoplay: false, reuseCurrentSource: true)
    }


    func repeatableCurrentSource() -> ResolvedSource? {
        guard let source = loadedSourcePath, source.hasPrefix("/"),
              FileManager.default.fileExists(atPath: source),
              !FileManager.default.fileExists(atPath: source + ".failed"),
              !FileManager.default.fileExists(atPath: source + ".playback-cancelled") else { return nil }
        return ResolvedSource(source: source, headers: loadedSourceHeaders,
            kbps: Int(nerd?.kbps ?? 0), lossless: QualityUpgrade.Format.isLosslessCodec(nerd?.codec), codec: nerd?.codec,
            durationSec: duration > 0 ? Int(duration.rounded()) : nil,
            loudnessDb: currentLoudnessDb, origin: resolvedOrigin[current?.id ?? ""] ?? .cache,
            youtubeVideoId: historyVideoID)
    }

    func handleDuration(_ seconds: Double) {
        duration = seconds
        publishSmartWindow()




        if lyricsNeedsDurationRefresh, seconds > 0, let entry = current {
            lyricsNeedsDurationRefresh = false
            fetchLyrics(for: entry)
        }
    }










    func refetchLyrics() {
        guard let entry = current else { return }
        fetchLyrics(for: entry)
    }

    func handleError(_ message: String) {
        lastError = message
        if let id = current?.id, QualityUpgrade.forcedStream(id) != nil {
            QualityUpgrade.refuseUpgrades(id)
            debugLog.record("broke on its upgrade; no more swaps", about: id)
        }
    }



    var upNextEntry: QueueEntry? { nextEntry }



    func nextPlayableIndex(after index: Int) -> Int? {
        let now = Date()
        return queue.indices.first { $0 > index && (unplayableUntil[queue[$0].id] ?? .distantPast) <= now }
    }





    @discardableResult
    func markUnplayable(_ entry: QueueEntry, after error: Error) -> Bool {
        guard NetworkQuality.shared.connected else { return false }
        let nsError = error as NSError
        let message = error.localizedDescription
        guard nsError.domain != NSURLErrorDomain,
              !InnertubeStreamResolver.StreamError.isBotCheck(error),
              !message.localizedCaseInsensitiveContains("offline"),
              !message.localizedCaseInsensitiveContains("network connection") else { return false }
        unplayableUntil = unplayableUntil.filter { $0.value > Date() }
        unplayableUntil[entry.id] = Date().addingTimeInterval(600)
        return true
    }

    var nextEntry: QueueEntry? {
        if repeatMode == .one {


            return automixEnabled ? current : nil
        }
        if let next = nextPlayableIndex(after: playingIndex) { return queue[next] }


        if repeatMode == .all, !queue.isEmpty { return queue[0] }
        return nil
    }
}
