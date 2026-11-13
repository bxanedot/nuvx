import Foundation


enum LosslessRoute {
    static func permitsLossless(routeKind: String, bluetoothCodec: String? = nil) -> Bool {
        let route = routeKind.lowercased()
        guard route.contains("bluetooth") else { return true }
        guard let codec = bluetoothCodec?.lowercased() else { return false }


        return codec.contains("aptx lossless")
    }

    static func cappedLosslessSampleRate(routeKind: String, requested: Double?) -> Double? {
        guard let requested, requested > 0 else { return nil }
        guard routeKind.lowercased().contains("airplay") else { return requested }
        return min(max(requested, 44_100), 48_000)
    }

    static func permitsBitPerfect(routeKind: String, sourceIsLossless: Bool, integerPcm: Bool) -> Bool {
        guard sourceIsLossless, integerPcm else { return false }
        let route = routeKind.lowercased()
        return !route.isEmpty && !route.contains("bluetooth") && !route.contains("airplay")
    }
}
