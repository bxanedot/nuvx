import AVFoundation
import Foundation
#if canImport(UIKit)
import UIKit
#endif

#if canImport(MusicUnderstanding)
import MusicUnderstanding
#endif











enum MusicUnderstandingAnalyzer {













    private static var gpuSuspended = false
    private static let gpuSuspendLock = NSLock()


    static func noteBackground() {
#if canImport(UIKit)
        setGpuSuspended(true)
        Task { await MusicAnalysisQueue.shared.cancelAll() }
#endif
    }


    static func noteForeground() {
        setGpuSuspended(false)
    }

    private static func isGpuSuspended() -> Bool {
        gpuSuspendLock.lock()
        defer { gpuSuspendLock.unlock() }
        return gpuSuspended
    }




    static var isBackgrounded: Bool { isGpuSuspended() }




    private static func setGpuSuspended(_ value: Bool) {
        gpuSuspendLock.lock()
        defer { gpuSuspendLock.unlock() }
        gpuSuspended = value
    }


    static var isAvailable: Bool {
        if #available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *) {
            return true
        }
        return false
    }



    typealias Decode = PCMTranscode.Writer








    @discardableResult
    static func analyzeAndSeed(
        filePath: String,
        allowAccelerator: Bool = true,
        decode: Decode? = nil
    ) async -> Bool {
        #if DEBUG



        if ProcessInfo.processInfo.arguments.contains("--verify-sleep") { return false }
        #endif
        guard isAvailable else {
            return AutomixOverlayCache.seedCached(forFilePath: filePath)
        }
        let path = filePath.hasPrefix("file://")
            ? (URL(string: filePath)?.path ?? filePath)
            : filePath
        guard !path.isEmpty, FileManager.default.fileExists(atPath: path) else {
            return false
        }
        guard !Task.isCancelled else { return false }
        guard let fingerprint = AutomixOverlayCache.fingerprint(forFilePath: path) else { return false }


        if AutomixOverlayCache.seedCached(forFilePath: path) { return true }
        guard allowAccelerator else { return false }


        if isGpuSuspended() {
            return AutomixOverlayCache.seedCached(forFilePath: path)
        }


        if #available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *),
           await isForeground()
        {
            let key = "\(path)|\(fingerprint.size)|\(fingerprint.modified)"
            if await MusicAnalysisQueue.shared.perform(key: key, operation: {
                if AutomixOverlayCache.seedCached(forFilePath: path) { return true }
                return await analyzeAvailable(path: path, decode: decode)
            }) {
                return true
            }
        }
        return AutomixOverlayCache.seedCached(forFilePath: path)
    }


    private static func isForeground() async -> Bool {
        #if canImport(UIKit)
        await MainActor.run {
            UIApplication.shared.applicationState == .active
        }
        #else
        true
        #endif
    }

    @available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *)
    private static func analyzeAvailable(path: String, decode: Decode?) async -> Bool {
        #if canImport(MusicUnderstanding)



        guard !isGpuSuspended() else { return false }

        guard await isForeground() else { return false }
        var transcoded: URL?
        defer { if let transcoded { try? FileManager.default.removeItem(at: transcoded) } }
        do {
            guard let fingerprint = AutomixOverlayCache.fingerprint(forFilePath: path) else { return false }
            var url = URL(fileURLWithPath: path)
            if !AVFoundationReadable.canOpen(path) {
                guard let decode, let pcm = await Task.detached(priority: .utility, operation: {
                    PCMTranscode.write(path: path, writer: decode)
                }).value else {
                    NSLog("[Nuvx] Music Understanding: could not decode %@", path)
                    return false
                }
                transcoded = pcm
                url = pcm
            }
            guard !Task.isCancelled else { return false }
            let asset = AVURLAsset(
                url: url,
                options: [AVURLAssetPreferPreciseDurationAndTimingKey: true]
            )
            let session = try await MusicUnderstandingSession(asset: asset)
            guard !Task.isCancelled, !isGpuSuspended(), await isForeground() else { await session.cancel(); return false }


            let wantsRhythm = !AutomixModelLoader.hasNeuralEngineBeatModel
            let result: MusicUnderstandingSession.SessionResult
            do {
                result = try await withTaskCancellationHandler {













                    try await session.analyze(for: wantsRhythm ? [.rhythm, .key] : [.key])
                } onCancel: {
                    Task { await session.cancel() }
                }
            } catch {
                await session.cancel()
                throw error
            }
            guard !Task.isCancelled, !isGpuSuspended(), fingerprint == AutomixOverlayCache.fingerprint(forFilePath: path) else { await session.cancel(); return false }
            let overlay = overlay(from: result)
            AutomixOverlayCache.store(overlay, rhythmAnalysed: wantsRhythm, forFilePath: path, expected: fingerprint)
            MemoryRelief.soon()
            return seedAutomixAnalysis(path: path, overlay: overlay)
        } catch {
            guard !Task.isCancelled else { return false }
            if await isForeground() {
                NSLog("[Nuvx] Music Understanding failed for %@: %@", path, String(describing: error))
            } else {




                setGpuSuspended(true)
            }
            return false
        }
        #else
        return false
        #endif
    }

    #if canImport(MusicUnderstanding)
    @available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *)
    private static func overlay(from result: MusicUnderstandingSession.SessionResult) -> AutomixAnalysisOverlayRec {
        var bpm: Double?
        var beatConfidence: Double?
        var beatInterval: Double?
        var beats: [Double]?
        var downbeats: [Double]?
        if let rhythm = result.rhythm {
            if let value = rhythm.beatsPerMinute, value.isFinite, value > 0 {
                bpm = Double(value)
                beatInterval = 60.0 / Double(value)
                beatConfidence = 0.85
            }
            let beatTimes = rhythm.beats.compactMap { cmSeconds($0) }
            if beatTimes.count >= 2 {
                beats = beatTimes
                if bpm == nil {
                    let span = beatTimes.last! - beatTimes.first!
                    if span > 0 {
                        bpm = Double(beatTimes.count - 1) * 60.0 / span
                        beatInterval = 60.0 / bpm!
                        beatConfidence = 0.7
                    }
                }
            }
            let barTimes = rhythm.bars.compactMap { cmSeconds($0) }
            if !barTimes.isEmpty {
                downbeats = barTimes
            }
        }

        var key: String?
        var keyConfidence: Double?
        if let keyResult = result.key, let first = keyResult.ranges.first {
            key = "\(tonicName(first.value.tonic)) \(first.value.mode == .minor ? "minor" : "major")"
            keyConfidence = 0.8
        }

        var phrases: [Double]?
        var mixIn: Double?
        var outro: Double?
        var audibleStart: Double?
        var contentEnd: Double?
        if let structure = result.structure {
            let phraseStarts = structure.phrases.compactMap { cmSeconds($0.start) }
            if !phraseStarts.isEmpty {
                phrases = phraseStarts
            }
            if let firstSection = structure.sections.first {
                audibleStart = cmSeconds(firstSection.start)
                mixIn = cmSeconds(firstSection.end)
            }
            if let lastSection = structure.sections.last {
                outro = cmSeconds(lastSection.start)
                contentEnd = cmSeconds(lastSection.end)
            }
            if let firstPhrase = structure.phrases.first {
                audibleStart = audibleStart ?? cmSeconds(firstPhrase.start)
            }
        }



        let vocalProbability: Double? = nil

        return AutomixAnalysisOverlayRec(
            bpm: bpm,
            beatConfidence: beatConfidence,
            beatInterval: beatInterval,
            beats: beats,
            downbeats: downbeats,
            key: key,
            keyConfidence: keyConfidence,
            phraseBoundaries: phrases,
            vocalProbability: vocalProbability,
            contentEnd: contentEnd,
            audibleStart: audibleStart,
            outroStart: outro,
            mixInTime: mixIn,
            source: "music_understanding"
        )
    }

    @available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *)
    private static func cmSeconds(_ time: CMTime) -> Double? {
        guard time.isValid, !time.isIndefinite else { return nil }
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite, seconds >= 0 else { return nil }
        return seconds
    }

    @available(iOS 27.0, macOS 27.0, tvOS 27.0, watchOS 27.0, visionOS 27.0, *)
    private static func tonicName(_ tonic: KeyResult.Tonic) -> String {
        switch tonic {
        case .c: return "C"
        case .cSharp: return "C♯"
        case .dFlat: return "D♭"
        case .d: return "D"
        case .dSharp: return "D♯"
        case .eFlat: return "E♭"
        case .e: return "E"
        case .f: return "F"
        case .fSharp: return "F♯"
        case .gFlat: return "G♭"
        case .g: return "G"
        case .gSharp: return "G♯"
        case .aFlat: return "A♭"
        case .a: return "A"
        case .aSharp: return "A♯"
        case .bFlat: return "B♭"
        case .b: return "B"
        @unknown default: return "C"
        }
    }
    #endif
}
