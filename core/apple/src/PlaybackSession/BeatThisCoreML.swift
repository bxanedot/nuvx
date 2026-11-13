import CoreML
import Foundation
import os











final class BeatThisCoreML: BeatAccelerator, @unchecked Sendable {
    static let inputFrames = 1500
    static let melBins = 128

    private static let log = OSLog(
        subsystem: Bundle.main.bundleIdentifier ?? "com.nuvx.app",
        category: "Automix"
    )


    static var computeUnits: MLComputeUnits {
        switch UserDefaults.standard.string(forKey: "BeatThisComputeUnits") {
        case "cpu": return .cpuOnly
        default: return .cpuAndNeuralEngine
        }
    }









    static let idleRelease: TimeInterval = 2

    private let modelURL: URL
    private let configuration: MLModelConfiguration

    private var model: MLModel?
    private var lastPrediction = Date.distantPast
    private var releaseScheduled = false



    private let predictionLock = NSLock()
    private let failureLock = NSLock()
    private var loggedFailure = false
    private static let releaseQueue = DispatchQueue(label: "BeatThisCoreML.release", qos: .utility)

    init?(modelURL: URL) {
        self.modelURL = modelURL
        let configuration = MLModelConfiguration()
        configuration.computeUnits = Self.computeUnits
        self.configuration = configuration



        do {
            model = try MLModel(contentsOf: modelURL, configuration: configuration)
        } catch {
            Self.logFailure("could not load \(modelURL.lastPathComponent): \(error.localizedDescription)")
            return nil
        }
        scheduleRelease()
    }



    func infer(mel: Data) -> BeatLogits? {
        let valueCount = Self.inputFrames * Self.melBins
        guard mel.count == valueCount * MemoryLayout<Float>.size else {
            logOnce("unexpected input of \(mel.count) bytes")
            return nil
        }



        guard !MusicUnderstandingAnalyzer.isBackgrounded else { return nil }

        predictionLock.lock()
        defer { predictionLock.unlock() }




        let logits = autoreleasepool { predict(mel) }
        lastPrediction = Date()
        scheduleRelease()
        return logits
    }



    private func scheduleRelease() {
        guard !releaseScheduled else { return }
        releaseScheduled = true
        Self.releaseQueue.asyncAfter(deadline: .now() + Self.idleRelease) { [weak self] in
            self?.releaseIfIdle()
        }
    }

    private func releaseIfIdle() {
        predictionLock.lock()
        defer { predictionLock.unlock() }
        releaseScheduled = false
        let idle = Date().timeIntervalSince(lastPrediction)
        if idle >= Self.idleRelease {
            model = nil
        } else {
            releaseScheduled = true
            Self.releaseQueue.asyncAfter(deadline: .now() + (Self.idleRelease - idle)) { [weak self] in
                self?.releaseIfIdle()
            }
        }
    }

    private func predict(_ mel: Data) -> BeatLogits? {
        do {
            let model: MLModel
            if let loaded = self.model {
                model = loaded
            } else {
                model = try MLModel(contentsOf: modelURL, configuration: configuration)
                self.model = model
            }
            let input = try MLMultiArray(
                shape: [1, NSNumber(value: Self.inputFrames), NSNumber(value: Self.melBins)],
                dataType: .float32
            )
            mel.withUnsafeBytes { source in
                guard let base = source.baseAddress else { return }
                input.dataPointer.copyMemory(from: base, byteCount: source.count)
            }
            let provider = try MLDictionaryFeatureProvider(
                dictionary: ["spectrogram": MLFeatureValue(multiArray: input)]
            )
            let output = try model.prediction(from: provider)
            guard let beat = output.featureValue(for: "beat")?.multiArrayValue,
                  let downbeat = output.featureValue(for: "downbeat")?.multiArrayValue,
                  let beatValues = floats(from: beat),
                  let downbeatValues = floats(from: downbeat)
            else {
                logOnce("prediction did not carry float32 beat and downbeat heads")
                return nil
            }
            return BeatLogits(beat: beatValues, downbeat: downbeatValues)
        } catch {
            logOnce("prediction failed: \(error.localizedDescription)")
            return nil
        }
    }





    private func floats(from array: MLMultiArray) -> Data? {
        guard array.dataType == .float32, array.count == Self.inputFrames else {
            logOnce("unexpected output type or length")
            return nil
        }
        return Data(bytes: array.dataPointer, count: array.count * MemoryLayout<Float>.size)
    }

    private func logOnce(_ message: String) {
        failureLock.lock()
        let first = !loggedFailure
        loggedFailure = true
        failureLock.unlock()
        if first {
            Self.logFailure(message)
        }
    }

    private static func logFailure(_ message: String) {
        os_log(.error, log: log, "Beat This! Core ML: %{public}@", message)
    }
}
