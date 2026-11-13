import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import NuvxShared
import UniformTypeIdentifiers


struct ExploreView: View {
    @State private var moods = MoodGenreLoader()
    @Environment(AuthController.self) private var auth
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        PageStack {
            content
                .navigationTitle("Explore")
                .toolbar {
                    #if os(iOS)
                    TopBarLeadingItems()
                    ToolbarItem(placement: .topBarTrailing) { TopBarAccountButton() }
                    #endif
                }
                .refreshable {
                    await moods.load(force: true)
                }
                .task(id: auth.sessionEpoch) {
                    await moods.load(force: false)
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch moods.phase {
        case .loading:
            ScrollView { moodGrid.padding(.horizontal, 24).padding(.vertical, 20) }
        case .failed(let message):
            EmptyStateView(
                icon: Image(.nuvxExplore),
                title: "Nothing to explore right now",
                subtitle: message,
                buttonTitle: "Retry"
            ) { Task { await moods.load(force: true) } }
        case .loaded:
            ScrollView {
                moodGrid
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
        }
    }

    @State private var gridWidth: CGFloat = 0

    @ViewBuilder
    private var moodGrid: some View {
        switch moods.phase {
        case .loading:


            LazyVGrid(columns: moodColumns, spacing: 12) {
                ForEach(0..<(moodColumnCount * 6), id: \.self) { _ in
                    MoodTileSkeleton()
                }
            }
        case .failed:
            EmptyView()
        case .loaded(let sections):




            let tiles = sections.flatMap(\.items).dedupedById()
            LazyVGrid(columns: moodColumns, spacing: 12) {
                ForEach(tiles) { item in
                    PushLink {
                        MoodGenrePlaylistsView(category: item)
                    } label: {
                        MoodTile(category: item)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { gridWidth = $0 }
        }
    }




    private var moodColumnCount: Int {
        let row = gridWidth - 48
        let count = Int((row + 12) / (220 + 12))
        return min(6, max(2, count))
    }

    private var moodColumns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 12), count: moodColumnCount)
    }
}






private struct MoodTile: View {
    let category: MoodGenre


    private let stripeFraction: CGFloat = 0.28





    private var colorIndex: Int {
        let hash = category.title.utf16.reduce(Int32(0)) { value, unit in
            value &* 31 &+ Int32(unit)
        }
        return Int((UInt32(bitPattern: hash) & 0x7fff_ffff) % 8)
    }

    private func argb(_ value: Int64) -> Color {
        Color(.sRGB,
              red: Double((value >> 16) & 0xFF) / 255,
              green: Double((value >> 8) & 0xFF) / 255,
              blue: Double(value & 0xFF) / 255,
              opacity: Double((value >> 24) & 0xFF) / 255)
    }

    private func lerp(_ from: (Double, Double, Double), to: (Double, Double, Double), _ t: Double) -> (Double, Double, Double) {
        (from.0 + (to.0 - from.0) * t, from.1 + (to.1 - from.1) * t, from.2 + (to.2 - from.2) * t)
    }

    private var baseRGB: (Double, Double, Double) {
        if let stripeColor = category.stripeColor {
            let value = UInt64(bitPattern: stripeColor)
            return (Double((value >> 16) & 0xFF) / 255, Double((value >> 8) & 0xFF) / 255, Double(value & 0xFF) / 255)
        }

        let colors: [(Int, Int, Int)] = [
            (0xCC, 0x6A, 0x55), (0xC0, 0x7A, 0x92), (0x9C, 0x8A, 0xC0), (0x80, 0x90, 0xC8),
            (0xD0, 0xA0, 0x60), (0x6A, 0x88, 0xB0), (0x7A, 0xAE, 0xD0), (0x86, 0xB8, 0x90),
        ]
        let (r, g, b) = colors[colorIndex]
        return (Double(r) / 255, Double(g) / 255, Double(b) / 255)
    }

    private var stripeRGB: (Double, Double, Double) {


        lerp(baseRGB, to: (0, 0, 0), 0.08)
    }

    private var shadowRGB: (Double, Double, Double) {
        lerp(baseRGB, to: (0, 0, 0), 0.68)
    }

    private var highlightRGB: (Double, Double, Double) {
        lerp(baseRGB, to: (1, 1, 1), 0.32)
    }

    private var placeholderRGB: (Double, Double, Double) {
        lerp(shadowRGB, to: highlightRGB, 0.45)
    }

    private func rgbColor(_ rgb: (Double, Double, Double)) -> Color {
        Color(red: rgb.0, green: rgb.1, blue: rgb.2)
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            rgbColor(stripeRGB)



            ZStack {
                rgbColor(placeholderRGB)
                if let url = category.thumbnailUrl {


                    let span = (
                        max(0, highlightRGB.0 - shadowRGB.0),
                        max(0, highlightRGB.1 - shadowRGB.1),
                        max(0, highlightRGB.2 - shadowRGB.2)
                    )
                    ArtworkView(url: url, data: nil, fill: true)
                        .grayscale(1)
                        .colorMultiply(rgbColor(span))
                        .blendMode(.plusLighter)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.leading, stripeFraction * 300)


            LinearGradient(
                colors: [.black.opacity(0.24), .clear],
                startPoint: .top,
                endPoint: .init(x: 0.5, y: 0.62)
            )
            Text(category.title)
                .font(.headline.bold())
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.35), radius: 6, x: 0, y: 1)
                .lineLimit(2)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
        }
        .aspectRatio(1.72, contentMode: .fit)
        .clipShape(.rect(cornerRadius: 18, style: .continuous))
        .contentShape(.rect(cornerRadius: 18, style: .continuous))
        .accessibilityLabel(category.title)
    }
}


private struct MoodTileSkeleton: View {
    var body: some View {


        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(.quaternary)
            .aspectRatio(1.72, contentMode: .fit)
            .accessibilityHidden(true)
    }
}

private extension Array where Element == MoodGenre {

    func dedupedById() -> [MoodGenre] {
        var seen = Set<String>()
        return filter { seen.insert($0.id).inserted }
    }
}
