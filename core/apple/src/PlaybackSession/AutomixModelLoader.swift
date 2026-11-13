import Foundation
import os

enum AutomixModelPolicy {
    static func paths(
        beat: String,
        vocal: String,
        accelerator: AutomixAccelerator?,
        playbackStarted: Bool,
        enabled: Bool,
        efficient: Bool
    ) -> (beat: String, vocal: String, accelerator: AutomixAccelerator?) {
        guard playbackStarted && enabled else { return ("", "", nil) }


        let accelerated = efficient || beat.isEmpty ? nil : accelerator
        return (beat, efficient ? "" : vocal, accelerated)
    }
}



actor AutomixModelLoader {
    static let shared = AutomixModelLoader()










    private var registered: (identity: String, model: BeatThisCoreML)? {
        didSet {
            let present = registered != nil
            Self.acceleratorFlag.withLock { $0 = present }
        }
    }









    nonisolated static var hasNeuralEngineBeatModel: Bool {
        acceleratorFlag.withLock { $0 }
    }
    private static let acceleratorFlag = OSAllocatedUnfairLock(initialState: false)

    func configure(beat: String, vocal: String, accelerator: AutomixAccelerator?) async -> Bool {
        let ready = configureAnalyzer(beatModelPath: beat, vocalModelPath: vocal)
        guard ready, !beat.isEmpty, let accelerator else {
            release()
            return ready
        }
        if registered?.identity == accelerator.identity { return ready }
        release()
        if let model = BeatThisCoreML(modelURL: accelerator.modelURL) {
            register(model, identity: accelerator.identity)
            return ready
        }



        let rebuilt = await AutomixModelStore.shared.recompileBeatCoreML()
        if let rebuilt, let model = BeatThisCoreML(modelURL: rebuilt.modelURL) {
            register(model, identity: rebuilt.identity)
        } else {
            release()
        }
        return ready
    }

    private func register(_ model: BeatThisCoreML, identity: String) {
        setBeatAccelerator(accelerator: model, identity: identity)
        registered = (identity, model)
    }

    private func release() {
        guard registered != nil else {
            setBeatAccelerator(accelerator: nil, identity: "")
            return
        }
        registered = nil
        setBeatAccelerator(accelerator: nil, identity: "")
    }
}
