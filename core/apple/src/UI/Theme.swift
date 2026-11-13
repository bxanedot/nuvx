import SwiftUI






















enum Theme {


    static let accent = Color.accentColor


    enum Colors {
        static let background = Color("NuvxBackground")
        static let surface = Color("NuvxSurface")
        static let surfaceVariant = Color("NuvxSurfaceVariant")
        static let outline = Color("NuvxOutline")
    }



    enum Metrics {



        static let s: CGFloat = 4
        static let m: CGFloat = 8
        static let l: CGFloat = 16
        static let xl: CGFloat = 24


        static let artworkRadius: CGFloat = 6
        static let rowRadius: CGFloat = 8
        static let cardRadius: CGFloat = 14



        #if os(iOS)
        static let minHitTarget: CGFloat = 44
        #else
        static let minHitTarget: CGFloat = 32
        #endif



        static let rowArt: CGFloat = 44
        static let shelfCard: CGFloat = 160
        static let gridCard: CGFloat = 140
        static let headerArt: CGFloat = 170
    }


    enum Materials {



        static var opaque: Color {
            #if os(macOS)
            Color(nsColor: .windowBackgroundColor).opacity(0.98)
            #else
            Color(uiColor: .systemBackground).opacity(0.98)
            #endif
        }
    }
}












enum Motion {





    static func once(
        _ reduceMotion: Bool,
        duration: Double = 0.25,
        curve: Animation = .easeInOut
    ) -> Animation {
        reduceMotion ? .easeOut(duration: 0.12) : curve
    }







    static func loop(
        _ reduceMotion: Bool,
        duration: Double,
        curve: Animation = .easeInOut
    ) -> Animation? {
        reduceMotion ? nil : curve
            .repeatForever(autoreverses: true)
            .speed(1.0 / max(duration, 0.01))
    }
}











struct ChromeBackground: ViewModifier {
    enum Weight {

        case thin

        case regular
    }

    let weight: Weight
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        content.background {
            if reduceTransparency {
                Rectangle().fill(opaque)
            } else if #available(iOS 26.0, macOS 26.0, *) {



                Rectangle().fill(.clear).glassEffect(.regular)
            } else {
                switch weight {
                case .thin: Rectangle().fill(.ultraThinMaterial)
                case .regular: Rectangle().fill(.regularMaterial)
                }
            }
        }
    }

    var opaque: Color { Theme.Materials.opaque }
}

extension View {

    func chromeBackground(_ weight: ChromeBackground.Weight = .thin) -> some View {
        modifier(ChromeBackground(weight: weight))
    }
}








struct ChromeGlass<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder
    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(shape.fill(Theme.Materials.opaque))
        } else if #available(iOS 26.0, macOS 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content.background(shape.fill(.ultraThinMaterial))
        }
    }
}





struct ToolbarChrome<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, macOS 26.0, *) {
            content
        } else if reduceTransparency {
            content.background(shape.fill(Theme.Materials.opaque))
        } else {
            content
                .background(shape.fill(.ultraThinMaterial))
                .overlay(shape.stroke(Color.white.opacity(0.22), lineWidth: 0.6))
        }
    }
}

extension View {

    func chromeGlass<S: Shape>(in shape: S) -> some View {
        modifier(ChromeGlass(shape: shape))
    }


    func toolbarChrome<S: Shape>(in shape: S) -> some View {
        modifier(ToolbarChrome(shape: shape))
    }
}







struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design

    init(
        size: CGFloat,
        weight: Font.Weight = .regular,
        design: Font.Design = .default,
        relativeTo: Font.TextStyle = .body
    ) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: relativeTo)
        self.weight = weight
        self.design = design
    }

    func body(content: Content) -> some View {



        content.font(.system(size: size, weight: weight, design: design))
    }
}

extension View {

    func scaledFont(
        size: CGFloat,
        weight: Font.Weight = .regular,
        design: Font.Design = .default,
        relativeTo: Font.TextStyle = .body
    ) -> some View {
        modifier(ScaledFont(size: size, weight: weight, design: design, relativeTo: relativeTo))
    }
}



extension View {




    func minimumHitTarget(_ size: CGFloat = Theme.Metrics.minHitTarget) -> some View {
        frame(minWidth: size, minHeight: size)
            .contentShape(.rect)
    }






    func decorative() -> some View {
        accessibilityHidden(true)
    }
}









enum AdjustableAccessibility {

    static func apply<V: View>(
        to view: V,
        label: String,
        value: Double,
        formatted: String,
        increment: Double = 1,
        decrement: Double = 5,
        onChange: @escaping (Double) -> Void
    ) -> some View {
        view
            .accessibilityElement()
            .accessibilityLabel(label)
            .accessibilityValue(formatted)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: onChange(value + increment)
                case .decrement: onChange(value - decrement)
                @unknown default: break
                }
            }
    }
}
