import Foundation
import Network
import Observation
import NuvxShared


@MainActor
@Observable
final class NetworkQuality {
    static let shared = NetworkQuality()
    private(set) var metered = false
    private(set) var connected = true
    @ObservationIgnored private let monitor = NWPathMonitor()


    @ObservationIgnored private var pathSignature: String?

    private(set) var pathChanges = 0

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let expensive = path.isExpensive || path.isConstrained
            let signature = "\(path.status)|" + path.availableInterfaces.filter { path.usesInterfaceType($0.type) }
                .map { "\($0.type):\($0.name)" }.sorted().joined(separator: ",")
            Task { @MainActor in
                guard let self else { return }
                let wasConnected = self.connected
                self.metered = expensive; self.connected = path.status == .satisfied
                StreamDownloadBridge.shared.networkStatus(available: self.connected)
                AppSettings.shared.setMeteredConnection(value: KotlinBoolean(bool: expensive))
                DownloadStore.shared.networkPolicyChanged()
                if let previous = self.pathSignature, previous != signature {
                    self.pathChanges += 1


                    if self.connected && wasConnected { StreamDownloadBridge.shared.networkChanged() }
                }
                self.pathSignature = signature
            }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
    }

    var maxKbps: Swift.Int32 {
        AppSettings.shared.effectiveAudioQuality(metered: metered).maxKbps
    }

    var canvasAllowed: Bool {
        let enabled = PlatformSettings.shared.getBoolean(key: "animated_canvas", default: true)
        if !enabled { return false }
        if metered {
            return PlatformSettings.shared.getBoolean(key: "canvas_over_cellular", default: false)
        }
        return true
    }
}
