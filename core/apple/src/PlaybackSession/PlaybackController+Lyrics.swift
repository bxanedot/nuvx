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
    func fetchLyrics(for entry: QueueEntry) {
        lyricsAlignmentTask?.cancel()
        lyricsAlignmentTask = nil
        lyricsAligning = false
        lyricsAlignedLocally = nil
        lyricsRequestGeneration &+= 1
        let requestGeneration = lyricsRequestGeneration
        lyricsNeedsDurationRefresh = false
        lyrics = []
        lyricsSourceLabel = nil


        lyricsProviderStates = [:]
        lyricsProviderResults = [:]
        lyricsProviderChoice = Self.storedProviderChoice(for: entry.id)



        attemptedLyrics = false



        lyricsTranslator.reset()



        let localPath = entry.isLocal && !entry.source.isEmpty && !entry.source.hasPrefix("http")
            ? entry.source
            : nil
        let allowNetwork = PlatformSettings.shared.getBoolean(key: "synced_lyrics", default: true)
        if !allowNetwork, localPath == nil {



            lyricsLoading = false
            attemptedLyrics = true
            return
        }
        lyricsLoading = true


        let knownDuration = duration > 0 ? duration : entry.durationSeconds
        let durationMs = Swift.Int64(knownDuration * 1000)
        lyricsNeedsDurationRefresh = durationMs <= 0
        LyricsBridge.shared.fetchWithSourceStates(
            title: entry.title,
            artist: entry.artist,
            durationMs: durationMs,
            album: entry.albumName,
            videoId: entry.videoId,
            localPath: localPath,
            callback: AttributedLyricsAdapter { [weak self] _, label, lines in
                Task { @MainActor in
                    guard let self,
                          self.current?.id == entry.id,
                          self.lyricsRequestGeneration == requestGeneration
                    else { return }



                    let chosen = self.lyricsProviderChoice
                    if let chosen, chosen != label,
                       let chosenLines = self.lyricsProviderResults[chosen], !chosenLines.isEmpty {
                        self.applyLyrics(chosenLines, label: chosen, entry: entry, generation: requestGeneration)
                        return
                    }
                    self.applyLyrics(lines, label: label, entry: entry, generation: requestGeneration)
                }
            },
            onSourceState: SourceStateAdapter { [weak self] source, label, state, lines in
                Task { @MainActor in
                    guard let self,
                          self.current?.id == entry.id,
                          self.lyricsRequestGeneration == requestGeneration
                    else { return }
                    self.lyricsProviderStates[source] = LyricsSourceState(state)
                    if let lines, !lines.isEmpty {
                        self.lyricsProviderResults[source] = lines
                    }
                }
            }
        )
    }



    private func applyLyrics(
        _ lines: [LyricLineDto],
        label: String,
        entry: QueueEntry,
        generation: UInt64
    ) {
        lyrics = lines
        lyricsSourceLabel = label.isEmpty ? nil : label
        lyricsLoading = false


        attemptedLyrics = true
        alignFetchedLyricsIfNeeded(
            lines,
            for: entry,
            requestGeneration: generation
        )
    }




    func selectLyricsProvider(named source: String) {
        guard let lines = lyricsProviderResults[source], !lines.isEmpty,
              let entry = current else { return }
        lyricsProviderChoice = source
        Self.storeProviderChoice(source, for: entry.id)
        lyricsRequestGeneration &+= 1
        applyLyrics(lines, label: source, entry: entry, generation: lyricsRequestGeneration)
    }





    private static let lyricsChoiceStore: any LyricsChoiceStore = UbiquitousLyricsChoiceStore()

    private static func storedProviderChoice(for videoId: String) -> String? {
        lyricsChoiceStore.choice(for: videoId)
    }

    private static func storeProviderChoice(_ source: String, for videoId: String) {
        lyricsChoiceStore.remember(source, for: videoId)
    }




    func alignFetchedLyricsIfNeeded(
        _ lines: [LyricLineDto],
        for entry: QueueEntry,
        requestGeneration: UInt64
    ) {
        guard lines.contains(where: { !$0.isGap && $0.words.isEmpty }),
              let source = loadedSourcePath,
              !source.isEmpty
        else { return }

        let trackDuration = duration > 0 ? duration : entry.durationSeconds
        guard trackDuration > 0 else { return }
        let engine = self.engine
        let headers = loadedSourceHeaders
        let decode: LyricsAudioAligner.Decode = { start, length in


            guard let region = engine.decodeRegionPcm(
                source: source,
                startSeconds: start,
                durationSeconds: length,
                headers: headers,
                targetRate: 16_000
            ) else { return nil }
            let count = region.pcm.count / MemoryLayout<Float>.size
            let samples = [Float](unsafeUninitializedCapacity: count) { buffer, initialized in
                region.pcm.withUnsafeBytes { raw in
                    UnsafeMutableRawBufferPointer(buffer).copyMemory(
                        from: UnsafeRawBufferPointer(rebasing: raw.prefix(count * MemoryLayout<Float>.size))
                    )
                }
                initialized = count
            }
            return LyricsAudioAligner.Region(
                samples: samples,
                sampleRate: Double(region.sampleRate),
                startSeconds: region.startSeconds
            )
        }

        lyricsAligning = true
        lyricsAlignmentTask = Task { @MainActor [weak self] in
            let coverage = await LyricsAudioAligner.align(
                lines: lines,
                durationSeconds: trackDuration,
                decode: decode,
                playhead: { [weak self] in self?.position ?? 0 },
                onUpdate: { [weak self] updated in
                    guard let self,
                          self.current?.id == entry.id,
                          self.lyricsRequestGeneration == requestGeneration
                    else { return }
                    self.lyrics = updated
                }
            )
            MemoryRelief.soon()
            guard let self,
                  self.current?.id == entry.id,
                  self.lyricsRequestGeneration == requestGeneration
            else { return }
            self.lyricsAligning = false



            self.lyricsAlignedLocally = coverage
            self.lyricsAlignmentTask = nil
            PlaybackDebugLog.shared.record(
                "lyrics: local alignment timed \(coverage.timed) of \(coverage.total) lines",
                about: entry.id
            )
        }
    }
}
