import Foundation
import CryptoKit
import Observation
import AppleArchive
import CoreML
import os
import System




struct AutomixAccelerator: Sendable {
    let modelURL: URL
    let identity: String
}

private let automixModelLog = OSLog(
    subsystem: Bundle.main.bundleIdentifier ?? "com.nuvx.app",
    category: "Automix"
)
























@MainActor
@Observable
final class AutomixModelStore {



    enum ModelID: String, CaseIterable, Identifiable {
        case beat
        case beatCoreML
        case vocals

        var id: String { rawValue }
    }


    struct ModelSpec {
        let id: ModelID
        let fileName: String
        let displayName: String
        let detail: String
        let byteCount: Int64
        let sha256: String
        let url: URL

        var sizeText: String {
            ByteCountFormatter.string(fromByteCount: byteCount, countStyle: .file)
        }





        static let beat = ModelSpec(
            id: .beat,
            fileName: "beat_this.onnx",
            displayName: "Beat + downbeat detection",
            detail: "Beat This! reads the beat grid and downbeats, so transitions land on the music rather than near it.",
            byteCount: 23_478_460,
            sha256: "9f10e5efa37d3e120da2121141825fe54b23b487b32dd25a54f89039fd8f85cb",
            url: bundled("beat_this.onnx")
        )





        static let beatCoreML = ModelSpec(
            id: .beatCoreML,
            fileName: "beat_this_final0_coreml.aar",
            displayName: "Accelerated beat detection",
            detail: "Runs the same Beat This! model on the Neural Engine, several times faster than the CPU path. Requires the beat model.",



            byteCount: 18_826_124,
            sha256: "cd00f20bd602f4d1532f0b683fe418f278affead3494f954032039d902b5b88f",
            url: bundled("beat_this_final0_coreml.aar")
        )




        static let coreMLCompiledName = "BeatThisFinal0.mlmodelc"
        static let coreMLPackageName = "BeatThisFinal0.mlpackage"






        static let vocals = ModelSpec(
            id: .vocals,
            fileName: "vocals_umxhq_int8.onnx",
            displayName: "Vocal + clash detection",
            detail: "open-unmix reads how much singing is in each moment, so Automix can keep two vocals from overlapping.",
            byteCount: 23_209_009,
            sha256: "7eba6bebce0cc2e2d3b5c0e490a8074e66ce27f64170c6802b245740dcaf21ef",
            url: bundled("vocals_umxhq_int8.onnx")
        )



        private static func bundled(_ name: String) -> URL {
            let resources = Bundle.main.resourceURL ?? Bundle.main.bundleURL
            return resources.appendingPathComponent("Models", isDirectory: true)
                .appendingPathComponent(name)
        }
    }

    struct Record: Codable {
        var sha256: String
        var bytes: Int64
        var installedAt: Date
    }



    enum Status: Equatable {
        case notInstalled

        case paused(Int64)
        case downloading(Double)
        case verifying
        case installed



        case outdated

        case blockedByWifi
        case failed(String)

        var isBusy: Bool {
            switch self {
            case .downloading, .verifying: return true
            default: return false
            }
        }

        var isInstalled: Bool { self == .installed }
    }

    static let shared = AutomixModelStore()

    private(set) var status: [ModelID: Status] = [:]



    var onChanged: (() -> Void)?

    let directory: URL




    private let specs: [ModelID: ModelSpec]


    private let compileCoreML: @Sendable (URL, URL) -> Bool
    private var records: [String: Record] = [:]
    private var tasks: [ModelID: Task<Void, Never>] = [:]



    private var discarded: Set<ModelID> = []


    private var compiles: [String: CompileJob] = [:]



    private var compileEpochs: [ModelID: Int] = [:]

    private struct CompileJob {
        let token: UUID
        let task: Task<CompileOutcome, Never>
    }

    private enum CompileOutcome {
        case installed
        case failed


        case discarded
    }

    init(
        directory: URL? = nil,
        specs: [ModelSpec]? = nil,
        compileCoreML: @escaping @Sendable (URL, URL) -> Bool = { archive, destination in
            AutomixModelStore.compileCoreMLArchive(from: archive, to: destination)
        }
    ) {
        self.directory = directory ?? Self.defaultDirectory
        self.specs = Dictionary(
            (specs ?? [ModelSpec.beat, ModelSpec.beatCoreML, ModelSpec.vocals]).map { ($0.id, $0) },
            uniquingKeysWith: { _, last in last }
        )
        self.compileCoreML = compileCoreML
        for id in ModelID.allCases {
            status[id] = .notInstalled
        }
    }





    static var defaultDirectory: URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return root.appendingPathComponent("Nuvx/Models", isDirectory: true)
    }





    private static func makeSession(delegate: URLSessionDataDelegate) -> URLSession {
        let configuration = URLSessionConfiguration.default



        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 60 * 60
        configuration.waitsForConnectivity = true


        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }



    func spec(_ id: ModelID) -> ModelSpec {
        if let injected = specs[id] { return injected }
        switch id {
        case .beat: return .beat
        case .beatCoreML: return .beatCoreML
        case .vocals: return .vocals
        }
    }

    func status(of id: ModelID) -> Status { status[id] ?? .notInstalled }




    var allInstalled: Bool { ModelID.allCases.allSatisfy { status(of: $0).isInstalled } }


    var beatAvailable: Bool { status(of: .beat).isInstalled || isUsingPreviousGraph(.beat) }



    func isUsingPreviousGraph(_ id: ModelID) -> Bool {
        guard !status(of: id).isInstalled else { return false }
        let previous = directory.appendingPathComponent(previousName(for: spec(id)), isDirectory: false)
        return FileManager.default.fileExists(atPath: previous.path)
    }

    var anyBusy: Bool { ModelID.allCases.contains { status(of: $0).isBusy } }

    var totalBytesOnDisk: Int64 {
        ModelID.allCases.reduce(0) { total, id in
            var bytes = total + sizeOnDisk(of: spec(id).fileName) + sizeOnDisk(of: previousName(for: spec(id)))
            if id == .beatCoreML {
                bytes += sizeOnDisk(of: ModelSpec.coreMLCompiledName)
                bytes += sizeOnDisk(of: ModelSpec.coreMLCompiledName + ".prev")
            }
            return bytes
        }
    }

    func installedPath(_ id: ModelID) -> String? {
        guard status(of: id).isInstalled else { return nil }
        return fileURL(for: spec(id)).path
    }













    var analyzerPaths: (beat: String, vocal: String) {
        (analyzerPath(for: .beat), analyzerPath(for: .vocals))
    }

    private func analyzerPath(for id: ModelID) -> String {
        if let downloaded = installedPath(id) { return downloaded }
        let previous = directory.appendingPathComponent(previousName(for: spec(id)), isDirectory: false)
        if FileManager.default.fileExists(atPath: previous.path) { return previous.path }
        return ""
    }




    var beatCoreML: AutomixAccelerator? {
        let spec = spec(.beatCoreML)
        if status(of: .beatCoreML).isInstalled,
           let model = compiledModelURL(previous: false) {
            return AutomixAccelerator(modelURL: model, identity: spec.sha256)
        }
        let previousArchive = directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
        if FileManager.default.fileExists(atPath: previousArchive.path),
           let model = compiledModelURL(previous: true),
           let identity = records[previousName(for: spec)]?.sha256 {
            return AutomixAccelerator(modelURL: model, identity: identity)
        }
        return nil
    }











    func refresh() async {
        ensureDirectory()
        records = loadRecords()




        var changed = removeLegacyVocals()

        for id in ModelID.allCases where !status(of: id).isBusy {
            let spec = spec(id)
            let file = fileURL(for: spec)
            let part = partURL(for: spec)
            let fallback = directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
            var hasPrevious = FileManager.default.fileExists(atPath: fallback.path)

            if FileManager.default.fileExists(atPath: file.path) {
                let size = sizeOnDisk(of: spec.fileName)
                if size == spec.byteCount, records[spec.fileName]?.sha256 == spec.sha256 {

                    if hasPrevious {
                        try? FileManager.default.removeItem(at: fallback)
                        hasPrevious = false
                        changed = true
                    }
                    if id == .beatCoreML { discardCoreMLPrevious() }
                    status[id] = .installed
                    continue
                }
                if size == spec.byteCount, hashMatches(file, spec: spec) {
                    records[spec.fileName] = Record(sha256: spec.sha256, bytes: spec.byteCount, installedAt: Date())
                    saveRecords()
                    if hasPrevious {
                        try? FileManager.default.removeItem(at: fallback)
                        hasPrevious = false
                    }
                    if id == .beatCoreML { discardCoreMLPrevious() }
                    status[id] = .installed
                    changed = true
                    continue
                }





                if let previous = records[spec.fileName],
                   previous.bytes == size,
                   hashMatches(file, sha256: previous.sha256) {
                    do {


                        try? FileManager.default.removeItem(at: fallback)
                        try FileManager.default.moveItem(at: file, to: fallback)
                        if id == .beatCoreML {
                            records[previousName(for: spec)] = records[spec.fileName]
                            moveCompiledModel(previous: true)
                        }
                        records[spec.fileName] = nil
                        saveRecords()
                        hasPrevious = true
                        changed = true
                    } catch {



                    }
                } else {
                    try? FileManager.default.removeItem(at: file)
                    records[spec.fileName] = nil
                    saveRecords()
                    changed = true
                }
            }

            let partSize = sizeOnDisk(of: spec.fileName + ".part")
            if partSize > 0 {


                if partSize == spec.byteCount {
                    status[id] = .verifying
                    let outcome = await installVerified(part: part, spec: spec)
                    status[id] = outcome
                    if case .installed = outcome { changed = true }
                } else if partSize > spec.byteCount {
                    try? FileManager.default.removeItem(at: part)
                    status[id] = hasPrevious ? .outdated : .notInstalled
                } else {
                    status[id] = .paused(partSize)
                }
            } else {
                status[id] = hasPrevious ? .outdated : .notInstalled
            }
        }

        if await reconcileCoreML() { changed = true }
        if changed { onChanged?() }
    }



    private func compiledModelURL(previous: Bool) -> URL? {
        let url = compiledDestination(previous: previous)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func compiledDestination(previous: Bool) -> URL {
        directory.appendingPathComponent(
            ModelSpec.coreMLCompiledName + (previous ? ".prev" : ""),
            isDirectory: true
        )
    }


    private func moveCompiledModel(previous: Bool) {
        let source = compiledDestination(previous: false)
        let destination = compiledDestination(previous: true)




        try? FileManager.default.removeItem(at: destination)
        guard FileManager.default.fileExists(atPath: source.path) else { return }
        try? FileManager.default.moveItem(at: source, to: destination)
    }

    private func discardCoreMLPrevious() {
        let spec = spec(.beatCoreML)
        try? FileManager.default.removeItem(at: compiledDestination(previous: true))
        if records[previousName(for: spec)] != nil {
            records[previousName(for: spec)] = nil
            saveRecords()
        }
    }







    @discardableResult
    private func reconcileCoreML() async -> Bool {
        let spec = spec(.beatCoreML)
        var changed = false
        let previousArchive = directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
        if FileManager.default.fileExists(atPath: previousArchive.path) {
            let name = previousName(for: spec)
            if records[name] == nil {
                let digest = hash(of: previousArchive)
                if !digest.isEmpty {
                    records[name] = Record(sha256: digest, bytes: sizeOnDisk(of: name), installedAt: Date())
                    saveRecords()
                    changed = true
                }
            }
            if compiledModelURL(previous: true) == nil {




                let prior = status(of: .beatCoreML)
                let showingProgress = !prior.isBusy
                if showingProgress { status[.beatCoreML] = .verifying }
                let epoch = compileEpochs[.beatCoreML, default: 0]
                switch await compileCoreMLArchiveAsync(previousArchive, to: compiledDestination(previous: true)) {
                case .installed:


                    guard compileEpochs[.beatCoreML, default: 0] == epoch else { return changed }
                    if showingProgress { status[.beatCoreML] = prior }
                    changed = true
                case .failed:
                    guard compileEpochs[.beatCoreML, default: 0] == epoch else { return changed }
                    if showingProgress { status[.beatCoreML] = prior }
                case .discarded:
                    return changed
                }
            }
        }
        if status(of: .beatCoreML).isInstalled, compiledModelURL(previous: false) == nil {
            status[.beatCoreML] = .verifying
            let epoch = compileEpochs[.beatCoreML, default: 0]
            switch await compileCoreMLArchiveAsync(fileURL(for: spec), to: compiledDestination(previous: false)) {
            case .installed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return changed }
                status[.beatCoreML] = .installed
                changed = true
            case .failed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return changed }
                status[.beatCoreML] = .failed("The beat model downloaded but could not be prepared for Core ML.")
                return true
            case .discarded:
                return changed
            }
        }
        return changed
    }




    @discardableResult
    func recompileBeatCoreML() async -> AutomixAccelerator? {
        let spec = spec(.beatCoreML)
        if status(of: .beatCoreML).isInstalled {
            let destination = compiledDestination(previous: false)
            try? FileManager.default.removeItem(at: destination)
            status[.beatCoreML] = .verifying
            let epoch = compileEpochs[.beatCoreML, default: 0]
            switch await compileCoreMLArchiveAsync(fileURL(for: spec), to: destination) {
            case .installed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return nil }
                status[.beatCoreML] = .installed
                return AutomixAccelerator(modelURL: destination, identity: spec.sha256)
            case .failed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return nil }
                status[.beatCoreML] = .failed("The beat model could not be prepared for Core ML.")
                return nil
            case .discarded:
                return nil
            }
        }
        let previousArchive = directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
        if FileManager.default.fileExists(atPath: previousArchive.path),
           let identity = records[previousName(for: spec)]?.sha256 {
            let destination = compiledDestination(previous: true)
            try? FileManager.default.removeItem(at: destination)
            let prior = status(of: .beatCoreML)
            let showingProgress = !prior.isBusy
            if showingProgress { status[.beatCoreML] = .verifying }
            let epoch = compileEpochs[.beatCoreML, default: 0]
            switch await compileCoreMLArchiveAsync(previousArchive, to: destination) {
            case .installed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return nil }
                if showingProgress { status[.beatCoreML] = prior }
                return AutomixAccelerator(modelURL: destination, identity: identity)
            case .failed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return nil }
                if showingProgress { status[.beatCoreML] = prior }
                return nil
            case .discarded:
                return nil
            }
        }
        return nil
    }








    private func compileCoreMLArchiveAsync(_ archive: URL, to destination: URL) async -> CompileOutcome {
        let key = destination.path
        if let running = compiles[key] { return await running.task.value }
        let token = UUID()
        let epoch = compileEpochs[.beatCoreML, default: 0]
        let compiler = compileCoreML
        let scratch = destination.deletingLastPathComponent()
            .appendingPathComponent(".compile-\(token.uuidString)", isDirectory: true)
        let task = Task { @MainActor () -> CompileOutcome in
            let compiled = await Task.detached(priority: .utility) {
                compiler(archive, scratch)
            }.value


            guard compileEpochs[.beatCoreML, default: 0] == epoch else {
                try? FileManager.default.removeItem(at: scratch)
                return .discarded
            }
            guard compiled else {
                try? FileManager.default.removeItem(at: scratch)
                return .failed
            }
            do {
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: scratch, to: destination)
                return .installed
            } catch {
                try? FileManager.default.removeItem(at: scratch)
                return .failed
            }
        }
        compiles[key] = CompileJob(token: token, task: task)
        let outcome = await task.value
        if compiles[key]?.token == token { compiles[key] = nil }
        return outcome
    }













    func updateOutdated() {
        guard AutomixModelsPolicy.allowMetered else { return }
        for id in ModelID.allCases {
            if case .outdated = status(of: id) { start(id, allowMetered: true) }
        }
    }


    func start(_ id: ModelID, allowMetered: Bool) {
        guard !status(of: id).isBusy, !status(of: id).isInstalled else { return }
        guard tasks[id] == nil else { return }
        tasks[id] = Task { [weak self] in
            await self?.download(id, allowMetered: allowMetered)
            self?.tasks[id] = nil
        }
    }








    func installBundledDefaults() async {
        for id in [ModelID.beat, ModelID.vocals] where !status(of: id).isInstalled {
            await download(id, allowMetered: true)
        }
    }

    func download(_ id: ModelID, allowMetered: Bool) async {
        let spec = spec(id)
        guard !status(of: id).isInstalled else { return }
        guard !status(of: id).isBusy else { return }



        discarded.remove(id)

        if spec.url.isFileURL {
            ensureDirectory()
            let part = partURL(for: spec)
            do {
                if FileManager.default.fileExists(atPath: part.path) {
                    try FileManager.default.removeItem(at: part)
                }
                try FileManager.default.copyItem(at: spec.url, to: part)
                status[id] = .verifying
                let outcome = await installVerified(part: part, spec: spec)
                guard !discarded.contains(id) else { return }
                status[id] = outcome
                if case .installed = outcome { onChanged?() }
            } catch {
                status[id] = .failed("The bundled model could not be installed: \(error.localizedDescription)")
            }
            return
        }

        guard allowMetered else {
            status[id] = .blockedByWifi
            return
        }

        ensureDirectory()


        if id == .beatCoreML, spec.byteCount > 0,
           sizeOnDisk(of: spec.fileName) == spec.byteCount,
           records[spec.fileName]?.sha256 == spec.sha256 {
            status[id] = .verifying
            let destination = compiledDestination(previous: false)
            try? FileManager.default.removeItem(at: destination)
            let epoch = compileEpochs[.beatCoreML, default: 0]
            switch await compileCoreMLArchiveAsync(fileURL(for: spec), to: destination) {
            case .installed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return }
                status[id] = .installed
                onChanged?()
            case .failed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return }
                status[id] = .failed("The beat model could not be prepared for Core ML.")
            case .discarded:
                return
            }
            return
        }
        let part = partURL(for: spec)
        var existing = sizeOnDisk(of: spec.fileName + ".part")

        if existing > spec.byteCount {
            try? FileManager.default.removeItem(at: part)
            existing = 0
        }
        if existing == spec.byteCount {
            status[id] = .verifying
            let outcome = await installVerified(part: part, spec: spec)
            guard !discarded.contains(id) else { return }
            status[id] = outcome
            if case .installed = outcome { onChanged?() }
            return
        }

        status[id] = .downloading(spec.byteCount > 0 ? Double(existing) / Double(spec.byteCount) : 0)

        var request = URLRequest(url: spec.url)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if existing > 0 {
            request.setValue("bytes=\(existing)-", forHTTPHeaderField: "Range")
        }

        let delegate = PartWriter(
            part: part,
            append: existing > 0,
            initial: existing,
            onProgress: { [weak self] reached in
                Task { @MainActor in self?.noteProgress(id, bytes: reached) }
            }
        )
        let session = Self.makeSession(delegate: delegate)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: request)

        do {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    delegate.attach(continuation)
                    task.resume()
                }
            } onCancel: {
                task.cancel()
            }

            status[id] = .verifying
            let outcome = await installVerified(part: part, spec: spec)
            guard !discarded.contains(id) else { return }
            status[id] = outcome
            if case .installed = outcome { onChanged?() }
        } catch is CancellationError {
            guard !discarded.contains(id) else { return }
            let reached = sizeOnDisk(of: spec.fileName + ".part")
            status[id] = reached > 0 ? .paused(reached) : .notInstalled
        } catch let error as URLError where error.code == .cancelled {
            guard !discarded.contains(id) else { return }
            let reached = sizeOnDisk(of: spec.fileName + ".part")
            status[id] = reached > 0 ? .paused(reached) : .notInstalled
        } catch {
            guard !discarded.contains(id) else { return }
            let reached = sizeOnDisk(of: spec.fileName + ".part")
            status[id] = .failed(
                reached > 0
                    ? "\(error.localizedDescription) · \(Self.bytes(reached)) kept, try again to resume"
                    : error.localizedDescription
            )
        }
    }

    func cancel(_ id: ModelID) {
        tasks[id]?.cancel()
        tasks[id] = nil
    }

    func cancelAll() {
        for id in ModelID.allCases { cancel(id) }
    }




    func remove(_ id: ModelID) {


        discarded.insert(id)
        cancel(id)
        let spec = spec(id)
        try? FileManager.default.removeItem(at: fileURL(for: spec))
        try? FileManager.default.removeItem(at: partURL(for: spec))
        try? FileManager.default.removeItem(
            at: directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
        )
        if id == .beatCoreML {




            compileEpochs[id, default: 0] += 1
            compiles[compiledDestination(previous: false).path] = nil
            compiles[compiledDestination(previous: true).path] = nil
            try? FileManager.default.removeItem(at: compiledDestination(previous: false))
            try? FileManager.default.removeItem(at: compiledDestination(previous: true))
            records[previousName(for: spec)] = nil
        }
        records[spec.fileName] = nil
        saveRecords()
        status[id] = .notInstalled
        onChanged?()
    }



    @discardableResult
    func verify(_ id: ModelID) async -> Bool {
        let spec = spec(id)
        let file = fileURL(for: spec)
        guard FileManager.default.fileExists(atPath: file.path) else {
            let hasPrevious = isUsingPreviousGraph(id)
            status[id] = hasPrevious ? .outdated : .notInstalled
            return hasPrevious
        }
        status[id] = .verifying
        if sizeOnDisk(of: spec.fileName) == spec.byteCount, hashMatches(file, spec: spec) {
            records[spec.fileName] = Record(sha256: spec.sha256, bytes: spec.byteCount, installedAt: Date())
            saveRecords()

            try? FileManager.default.removeItem(
                at: directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
            )
            records[previousName(for: spec)] = nil
            if id == .beatCoreML, compiledModelURL(previous: false) == nil {
                let epoch = compileEpochs[.beatCoreML, default: 0]
                switch await compileCoreMLArchiveAsync(file, to: compiledDestination(previous: false)) {
                case .installed:
                    guard compileEpochs[.beatCoreML, default: 0] == epoch else { return false }
                    try? FileManager.default.removeItem(at: compiledDestination(previous: true))
                case .failed:
                    guard compileEpochs[.beatCoreML, default: 0] == epoch else { return false }
                    status[id] = .failed("The beat model could not be prepared for Core ML.")
                    return false
                case .discarded:
                    return false
                }
            }
            status[id] = .installed
            return true
        }
        try? FileManager.default.removeItem(at: file)
        if id == .beatCoreML {
            try? FileManager.default.removeItem(at: compiledDestination(previous: false))
        }
        records[spec.fileName] = nil
        saveRecords()

        if isUsingPreviousGraph(id) {
            status[id] = .outdated
            onChanged?()
            return true
        }
        status[id] = .failed("The downloaded file did not match its checksum and was removed.")
        onChanged?()
        return false
    }



    fileprivate func noteProgress(_ id: ModelID, bytes: Int64) {
        guard status(of: id).isBusy else { return }
        let total = spec(id).byteCount
        guard total > 0 else { return }
        status[id] = .downloading(min(1, max(0, Double(bytes) / Double(total))))
    }



    private func fileURL(for spec: ModelSpec) -> URL {
        directory.appendingPathComponent(spec.fileName, isDirectory: false)
    }

    private func partURL(for spec: ModelSpec) -> URL {
        directory.appendingPathComponent(spec.fileName + ".part", isDirectory: false)
    }



    private func previousName(for spec: ModelSpec) -> String {
        spec.fileName + ".prev"
    }





    static let legacyVocalsFileName = "vocals_umxhq.onnx"

    @discardableResult
    private func removeLegacyVocals() -> Bool {
        var changed = false
        for suffix in ["", ".part", ".prev"] {
            let name = Self.legacyVocalsFileName + suffix
            let url = directory.appendingPathComponent(name, isDirectory: false)
            if FileManager.default.fileExists(atPath: url.path) {
                try? FileManager.default.removeItem(at: url)
                changed = true
            }
            if records[name] != nil {
                records[name] = nil
                changed = true
            }
        }
        if changed { saveRecords() }
        return changed
    }

    private func ensureDirectory() {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try? url.setResourceValues(values)
    }

    private func sizeOnDisk(of name: String) -> Int64 {
        let url = directory.appendingPathComponent(name, isDirectory: false)
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            return 0
        }
        if isDirectory.boolValue {


            guard let enumerator = FileManager.default.enumerator(
                at: url,
                includingPropertiesForKeys: [.fileSizeKey]
            ) else { return 0 }
            var total: Int64 = 0
            while let entry = enumerator.nextObject() as? URL {
                let size = (try? entry.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                total += Int64(size)
            }
            return total
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }


    private func installVerified(part: URL, spec: ModelSpec) async -> Status {
        guard sizeOnDisk(of: spec.fileName + ".part") == spec.byteCount else {
            return .failed("The download was incomplete.")
        }
        guard hashMatches(part, spec: spec) else {
            try? FileManager.default.removeItem(at: part)
            return .failed("The downloaded file did not match its checksum and was removed.")
        }
        let destination = fileURL(for: spec)
        do {
            if FileManager.default.fileExists(atPath: destination.path) {


                _ = try FileManager.default.replaceItemAt(destination, withItemAt: part)
            } else {


                try FileManager.default.moveItem(at: part, to: destination)
            }
        } catch {
            return .failed(error.localizedDescription)
        }

        try? FileManager.default.removeItem(
            at: directory.appendingPathComponent(previousName(for: spec), isDirectory: false)
        )
        records[previousName(for: spec)] = nil
        if spec.id == .beatCoreML {


            try? FileManager.default.removeItem(at: compiledDestination(previous: true))
            let epoch = compileEpochs[.beatCoreML, default: 0]
            switch await compileCoreMLArchiveAsync(destination, to: compiledDestination(previous: false)) {
            case .installed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return .notInstalled }
            case .discarded:


                return .notInstalled
            case .failed:
                guard compileEpochs[.beatCoreML, default: 0] == epoch else { return .notInstalled }
                records[spec.fileName] = Record(sha256: spec.sha256, bytes: spec.byteCount, installedAt: Date())
                saveRecords()
                return .failed("The beat model downloaded but could not be prepared for Core ML.")
            }
        }
        records[spec.fileName] = Record(sha256: spec.sha256, bytes: spec.byteCount, installedAt: Date())
        saveRecords()
        return .installed
    }

    private func hashMatches(_ url: URL, spec: ModelSpec) -> Bool {
        hashMatches(url, sha256: spec.sha256)
    }

    private func hashMatches(_ url: URL, sha256 expected: String) -> Bool {
        hash(of: url) == expected
    }

    private func hash(of url: URL) -> String {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 4 * 1024 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }






    nonisolated static func compileCoreMLArchive(from archive: URL, to destination: URL) -> Bool {
        let work = FileManager.default.temporaryDirectory
            .appendingPathComponent("nuvx-coreml-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: work) }
        do {
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
            try extractAppleArchive(archive, into: work)
            guard let package = coreMLPackage(in: work) else {
                throw AutomixModelError.noModelInArchive
            }
            let compiled = try MLModel.compileModel(at: package)
            defer { try? FileManager.default.removeItem(at: compiled.deletingLastPathComponent()) }
            try moveIntoPlace(compiled, destination)
            return true
        } catch {
            os_log(.error, log: automixModelLog, "Automix Core ML: %{public}@", error.localizedDescription)
            return false
        }
    }




    nonisolated static func extractAppleArchive(_ archive: URL, into directory: URL) throws {
        do {
            try extractArchive(archive, into: directory, compressed: false)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try extractArchive(archive, into: directory, compressed: true)
        }
    }

    nonisolated private static func extractArchive(
        _ archive: URL,
        into directory: URL,
        compressed: Bool
    ) throws {
        guard let bytes = ArchiveByteStream.fileStream(
            path: FilePath(archive.path),
            mode: .readOnly,
            options: [],
            permissions: FilePermissions(rawValue: 0o644)
        ) else {
            throw AutomixModelError.extractionFailed
        }
        defer { try? bytes.close() }

        let source: ArchiveByteStream
        if compressed {
            guard let decompressed = ArchiveByteStream.decompressionStream(readingFrom: bytes) else {
                throw AutomixModelError.extractionFailed
            }
            source = decompressed
        } else {
            source = bytes
        }
        defer { if compressed { try? source.close() } }

        guard let archiveStream = ArchiveStream.decodeStream(readingFrom: source) else {
            throw AutomixModelError.extractionFailed
        }
        defer { try? archiveStream.close() }
        guard let extractStream = ArchiveStream.extractStream(
            extractingTo: FilePath(directory.path),
            flags: [.ignoreOperationNotPermitted]
        ) else {
            throw AutomixModelError.extractionFailed
        }
        defer { try? extractStream.close() }
        _ = try ArchiveStream.process(readingFrom: archiveStream, writingTo: extractStream)
    }

    nonisolated private static func coreMLPackage(in directory: URL) -> URL? {
        let expected = directory.appendingPathComponent(ModelSpec.coreMLPackageName, isDirectory: true)
        if FileManager.default.fileExists(atPath: expected.path) { return expected }
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: nil
        ) else { return nil }
        while let entry = enumerator.nextObject() as? URL {
            if entry.pathExtension == "mlpackage" { return entry }
        }
        return nil
    }

    nonisolated private static func moveIntoPlace(_ source: URL, _ destination: URL) throws {
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: source)
        } else {
            try FileManager.default.moveItem(at: source, to: destination)
        }
    }



    private var recordsURL: URL {
        directory.appendingPathComponent("installed.json", isDirectory: false)
    }

    private func loadRecords() -> [String: Record] {
        guard let data = try? Data(contentsOf: recordsURL) else { return [:] }
        return (try? JSONDecoder().decode([String: Record].self, from: data)) ?? [:]
    }

    private func saveRecords() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: recordsURL, options: .atomic)
    }



    static func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }
}

enum AutomixModelError: LocalizedError {
    case badStatus(Int)
    case extractionFailed
    case noModelInArchive

    var errorDescription: String? {
        switch self {
        case .badStatus(let code):
            return "The model server answered \(code). Try again in a moment."
        case .extractionFailed:
            return "The Core ML archive could not be opened."
        case .noModelInArchive:
            return "The Core ML archive did not contain a model."
        }
    }
}












private final class PartWriter: NSObject, URLSessionDataDelegate {
    private let part: URL
    private let append: Bool
    private let onProgress: (Int64) -> Void

    private let lock = NSLock()
    private var handle: FileHandle?
    private var opened = false
    private var written: Int64 = 0
    private var base: Int64
    private var continuation: CheckedContinuation<Void, Error>?
    private var finished: Result<Void, Error>?

    init(part: URL, append: Bool, initial: Int64, onProgress: @escaping (Int64) -> Void) {
        self.part = part
        self.append = append
        self.base = initial
        self.onProgress = onProgress
    }




    func attach(_ continuation: CheckedContinuation<Void, Error>) {
        lock.lock()
        if let finished {
            lock.unlock()
            continuation.resume(with: finished)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    private func finish(_ result: Result<Void, Error>) {
        lock.lock()
        if let continuation {
            self.continuation = nil
            lock.unlock()
            continuation.resume(with: result)
        } else if finished == nil {



            finished = result
            lock.unlock()
        } else {
            lock.unlock()
        }
    }



    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 || code == 206 || code == 416 else {
            completionHandler(.cancel)
            finish(.failure(AutomixModelError.badStatus(code)))
            return
        }

        lock.lock()
        if code == 416 {


            lock.unlock()
            completionHandler(.cancel)
            finish(.success(()))
            return
        }
        if code == 200 {

            base = 0
            written = 0
            openHandle(truncate: true)
        } else {
            openHandle(truncate: !append)
        }
        lock.unlock()

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let target = handle
        written += Int64(data.count)
        let reached = base + written
        lock.unlock()

        guard let target else { return }
        do {
            try target.write(contentsOf: data)
        } catch {
            finish(.failure(error))
            return
        }
        onProgress(reached)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        closeHandle()
        if let error {
            finish(.failure(error))
        } else {
            finish(.success(()))
        }
    }


    private func openHandle(truncate: Bool) {
        guard !opened else { return }
        opened = true
        if truncate {
            FileManager.default.createFile(atPath: part.path, contents: nil)
            base = 0
        }
        guard let file = try? FileHandle(forWritingTo: part) else { return }
        if !truncate {
            _ = try? file.seekToEnd()
        }
        handle = file
    }

    private func closeHandle() {
        lock.lock()
        let file = handle
        handle = nil
        lock.unlock()
        try? file?.close()
    }
}
