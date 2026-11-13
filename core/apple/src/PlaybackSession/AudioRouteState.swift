import AVFoundation
import Observation
import NuvxShared
#if os(iOS)
import UIKit
#endif

@MainActor @Observable
final class AudioRouteState {
    static let shared = AudioRouteState()
    private(set) var name = "System output"
    private(set) var kind = ""
    private(set) var previousKind = ""
    private(set) var spatialCapable = false
    private(set) var monoAudioEnabled = false

    private(set) var systemSpatialEnabled: Bool?
    private var uid = ""

    func refresh(systemSpatialEnabled reported: Bool? = nil) {
        let oldKind = kind
        #if os(iOS)
        monoAudioEnabled = UIAccessibility.isMonoAudioEnabled
        let port = AVAudioSession.sharedInstance().currentRoute.outputs.first
        let newUID = port?.uid ?? ""
        if newUID != uid { systemSpatialEnabled = nil; uid = newUID }
        name = port?.portName ?? "System output"
        kind = port?.portType.rawValue ?? ""
        spatialCapable = port?.isSpatialAudioEnabled ?? false
        if let reported { systemSpatialEnabled = reported }
        if !spatialCapable { systemSpatialEnabled = false }
        #else
        let route = MacAudioRoutes.current()
        name = route.name; kind = route.kind

        systemSpatialEnabled = nil
        #endif
        if kind != oldKind { previousKind = oldKind }

        PlaybackCodecCapabilities.shared.setLosslessOutput(value: carriesLossless)
    }



    var carriesLossless: Bool {
        LosslessRoute.permitsLossless(routeKind: kind)
    }

    var spatialDescription: String {
        if let systemSpatialEnabled { return systemSpatialEnabled ? "Enabled by system" : "Off" }
        return spatialCapable ? "Available · System setting unknown" : "Not reported"
    }
    var permitsCustomSpatial: Bool { systemSpatialEnabled != true && !monoAudioEnabled }
}
