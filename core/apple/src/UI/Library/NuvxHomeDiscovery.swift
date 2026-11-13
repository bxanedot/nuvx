import SwiftUI



struct NuvxTopPicksShelf: View {
    let shelves: [FeedShelf]
    @State private var width: CGFloat = 0

    private var picks: [(card: ShelfCard, section: String)] {
        var seen = Set<String>()
        return Array(shelves
            .filter { !$0.title.localizedCaseInsensitiveContains("recent") }
            .flatMap { shelf in shelf.items.map { (card: $0, section: shelf.title) } }
            .filter { item in
                (item.card.browseId != nil || item.card.videoId != nil) &&
                    seen.insert(item.card.id).inserted
            }
            .prefix(8))
    }

    private var cardWidth: CGFloat { min(max(width, 320) * 0.60, 280) }

    var body: some View {
        if !picks.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                FeedSectionHeader(title: "Top picks for you")
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 14) {
                        ForEach(picks, id: \.card.id) { pick in
                            ShelfCardTap(card: pick.card, preferTrack: true) {
                                ZStack(alignment: .bottomLeading) {
                                    GeometryReader { geometry in
                                        ArtworkView(url: pick.card.thumbnailUrl, data: nil, side: geometry.size.height)
                                            .frame(width: geometry.size.width, height: geometry.size.height)
                                            .clipped()
                                    }
                                    LinearGradient(colors: [.clear, .black.opacity(0.90)],
                                                   startPoint: .center, endPoint: .bottom)
                                    VStack(alignment: .leading, spacing: 7) {
                                        Text(pick.section.uppercased())
                                            .font(.caption.weight(.bold))
                                            .foregroundStyle(.white.opacity(0.85))
                                            .lineLimit(1)
                                        Text(pick.card.title)
                                            .font(.title2.weight(.bold))
                                            .lineLimit(3)
                                        if let subtitle = pick.card.subtitle {
                                            Text(subtitle).font(.subheadline).lineLimit(2)
                                        }
                                    }
                                    .foregroundStyle(.white)
                                    .padding(18)
                                }
                                .frame(width: cardWidth, height: cardWidth / 0.75)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .contentShape(RoundedRectangle(cornerRadius: 12))
                            }
                            .shelfCardContextMenu(pick.card)
                            .modifier(MacCardHoverLift())
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
    }
}



struct NuvxHomeMoods: View {
    @State private var moods = MoodGenreLoader()
    private let tones: [Color] = [
        Color(red: 0.28, green: 0.35, blue: 0.57),
        Color(red: 0.46, green: 0.27, blue: 0.38),
        Color(red: 0.27, green: 0.43, blue: 0.35),
        Color(red: 0.45, green: 0.32, blue: 0.21),
    ]

    var body: some View {
        Group {
            if case .loaded(let sections) = moods.phase {
                var seen = Set<String>()
                let categories = Array(sections.flatMap(\.items).filter {
                    seen.insert($0.id).inserted
                }.prefix(12))
                if !categories.isEmpty {
                    VStack(alignment: .leading, spacing: 14) {
                        FeedSectionHeader(title: "Find your mood")
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 12) {
                                ForEach(Array(categories.enumerated()), id: \.element.id) { index, category in
                                    PushLink {
                                        MoodGenrePlaylistsView(category: category)
                                    } label: {
                                        Text(category.title)
                                            .font(.headline)
                                            .foregroundStyle(.white)
                                            .frame(width: 144, height: 88)
                                            .background(tones[index % tones.count],
                                                        in: RoundedRectangle(cornerRadius: 12))
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
        }
        .task { await moods.load(force: false) }
    }
}
