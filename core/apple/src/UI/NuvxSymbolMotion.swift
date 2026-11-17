import SwiftUI
import NuvxShared

/// Play and pause morph into each other, the same motion as the Android app's GlyphStudio Replace
/// transition. Still when nuvx's "reduce animation" or the system's Reduce Motion is on.
struct PlayPauseMorph: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let still = reduceMotion || PlatformSettings.shared.getBoolean(key: "reduce_animation", default: false)
        content.contentTransition(still ? .identity : .symbolEffect(.replace))
    }
}

extension View {
    func playPauseMorph() -> some View { modifier(PlayPauseMorph()) }
}
