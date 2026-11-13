import SwiftUI
import NuvxShared
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif










final class PerformanceTuner {
    static let shared = PerformanceTuner()




    static let supportedRates = [60, 90, 120]

    #if os(iOS)
    private var link: CADisplayLink?
    #endif

    func apply(highPerformance: Bool, refreshRateHz: Int) {
        #if os(iOS)
        link?.invalidate()
        link = nil
        guard highPerformance else { return }
        let clamped = min(max(refreshRateHz, 30), 240)
        let link = CADisplayLink(target: self, selector: #selector(tick))
        if #available(iOS 15.0, *) {
            let max = Float(clamped)
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: max, preferred: max)
        } else {
            link.preferredFramesPerSecond = min(clamped, 60)
        }
        link.add(to: .main, forMode: .common)
        self.link = link
        #else



        _ = (highPerformance, refreshRateHz)
        #endif
    }

    #if os(iOS)
    @objc private func tick() {}
    #endif
}
