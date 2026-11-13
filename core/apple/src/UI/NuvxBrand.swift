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
    @Environment(PlaybackController.self) private var controller
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

    private var hasSong: Bool { controller.current != nil }

    private var glassSpring: Animation? {
        reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.72)
    }

    var body: some View {
        if let last = visibleDestinations.last {
            HStack(spacing: 8) {
                tabsRow
                if hasSong {
                    PlaybackPill(inline: true, barHeight: 62)
                }
                NavStandaloneTab(
                    tab: last.tab,
                    title: last.title,
                    symbol: last.symbol,
                    selected: selection == last.tab.rawValue,
                    onSelect: onSelect
                )
            }
            .animation(glassSpring, value: hasSong)
        }
    }

    private var tabsRow: some View {
        HStack(spacing: 0) {
            ForEach(Array(visibleDestinations.dropLast()), id: \.tab) { destination in
                NavTabButton(
                    tab: destination.tab,
                    title: destination.title,
                    symbol: destination.symbol,
                    selected: selection == destination.tab.rawValue,
                    inline: hasSong,
                    namespace: selectedPill,
                    onSelect: onSelect
                )
            }
        }
        .padding(4)
        .frame(height: 62)
        .background {
            Capsule().fill(Theme.Colors.surface.opacity(0.96))
        }
        .overlay {
            Capsule().strokeBorder(Theme.Colors.outline, lineWidth: 0.5)
        }
        .animation(glassSpring, value: selection)
    }
}

private struct NavTabButton: View {
    let tab: AppModel.Tab
    let title: String
    let symbol: String
    let selected: Bool
    var inline = false
    let namespace: Namespace.ID
    let onSelect: (AppModel.Tab) -> Void

    @State private var bounce = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            if selected {
                bounce += 1
            } else {
                Haptics.play(.select)
            }
            onSelect(tab)
        } label: {
            Group {
                if inline {
                    tabIcon
                        .frame(width: 36)
                } else {
                    VStack(spacing: 1) {
                        tabIcon
                        tabLabel
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .foregroundStyle(Color.primary.opacity(selected ? 1.0 : 0.65))
            .padding(.vertical, 6.5)
            .background {
                if selected && !inline {
                    Capsule()
                        .fill(Color.primary.opacity(0.14))
                        .matchedGeometryEffect(id: "selection", in: namespace)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(title)))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .onChange(of: selected) { _, isSelected in
            if isSelected { bounce += 1 }
        }
    }

    private var tabIcon: some View {
        Image(systemName: symbol)
            .font(.system(size: 22, weight: .semibold))
            .frame(height: 28)
            .scaleEffect(selected ? 1.08 : 1.0)
            .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.72), value: selected)
            .symbolEffect(.bounce, value: bounce)
    }

    private var tabLabel: some View {
        Text(LocalizedStringKey(title))
            .font(.system(size: 10, weight: .medium))
            .tracking(0.5)
            .lineLimit(1)
    }
}

private struct NavStandaloneTab: View {
    let tab: AppModel.Tab
    let title: String
    let symbol: String
    let selected: Bool
    let onSelect: (AppModel.Tab) -> Void

    @State private var bounce = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            if selected {
                bounce += 1
            } else {
                Haptics.play(.select)
            }
            onSelect(tab)
        } label: {
            standaloneIcon
                .frame(width: 62, height: 62)
                .background {
                    Circle().fill(Theme.Colors.surface.opacity(0.96))
                }
                .overlay {
                    Circle().strokeBorder(Theme.Colors.outline, lineWidth: 0.5)
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(title)))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .onChange(of: selected) { _, isSelected in
            if isSelected { bounce += 1 }
        }
    }

    private var standaloneIcon: some View {
        Image(systemName: symbol)
            .font(.system(size: 22, weight: .semibold))
            .frame(height: 28)
            .foregroundStyle(Color.primary.opacity(selected ? 1.0 : 0.65))
            .scaleEffect(selected ? 1.08 : 1.0)
            .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.72), value: selected)
            .symbolEffect(.bounce, value: bounce)
    }
}
