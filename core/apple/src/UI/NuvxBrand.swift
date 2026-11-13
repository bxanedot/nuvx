import SwiftUI


struct NuvxBrandMark: View {
    @Environment(\.colorScheme) private var scheme
    var height: CGFloat = 31

    var body: some View {
        Image(scheme == .dark ? "nuvx-mark-white" : "nuvx-mark-black")
            .resizable()
            .scaledToFit()

            .frame(width: height * 2.45, height: height * 2.45)
            .frame(height: height)
            .clipped()
            .accessibilityLabel("nuvx")
    }
}



struct NuvxNavigationBar: View {
    @Binding var selection: Int
    var onSelect: (AppModel.Tab) -> Void
    var hideRadio = false
    var hidePodcasts = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selectedPill

    static let destinations: [(tab: AppModel.Tab, title: String, symbol: String)] = [
        (.home, "Home", "house.fill"),
        (.library, "Library", "square.stack.fill"),
        (.radio, "Radio", "dot.radiowaves.left.and.right"),
        (.libraryPodcasts, "Podcasts", "microphone"),
        (.explore, "Explore", "square.grid.2x2.fill"),
        (.search, "Search", "magnifyingglass"),
    ]
    private var visibleDestinations: [(tab: AppModel.Tab, title: String, symbol: String)] {
        Self.destinations.filter { destination in
            (destination.tab != .radio || !hideRadio) &&
                (destination.tab != .libraryPodcasts || !hidePodcasts)
        }
    }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(visibleDestinations, id: \.tab) { destination in
                let selected = selection == destination.tab.rawValue
                Button {
                    onSelect(destination.tab)
                } label: {
                    VStack(spacing: 1) {
                        Image(systemName: destination.symbol)
                            .font(.system(size: 21, weight: .semibold))
                            .frame(height: 28)
                        Text(LocalizedStringKey(destination.title))
                            .font(.caption2.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? Color.primary : Color.secondary)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .padding(.vertical, 3)
                    .background {
                        if selected {
                            Capsule()
                                .fill(Color.primary.opacity(0.14))
                                .matchedGeometryEffect(id: "selection", in: selectedPill)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(LocalizedStringKey(destination.title)))
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(4)
        .background(Theme.Colors.surface.opacity(0.96), in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.Colors.outline, lineWidth: 0.5))
        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.85), value: selection)
    }
}
