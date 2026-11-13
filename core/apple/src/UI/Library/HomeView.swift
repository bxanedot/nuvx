import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import NuvxShared
import UniformTypeIdentifiers




struct HomeView: View {
    @Bindable var feed: FeedLoader
    @Environment(PlaybackController.self) private var controller
    @Environment(AuthController.self) private var auth
    @Environment(ToastCenter.self) private var toast
    @AppStorage("hide_recents") private var hideRecents = false
    @AppStorage("hide_home_videos") private var hideHomeVideos = false
    @AppStorage("hide_top_picks") private var hideTopPicks = false
    @AppStorage("hide_home_moods") private var hideHomeMoods = false




    var scrollPosition: Binding<ScrollPosition>? = nil

    var body: some View {
        PageStack {
            content
                .navigationTitle("")
                .toolbar {
                    #if os(iOS)

                    ToolbarItem(placement: .topBarTrailing) { TopBarAccountButton() }
                    #endif
                }
                .refreshable { await loadFeed(force: true) }
                .task(id: auth.sessionEpoch) { await loadFeed(force: false) }
                .onAppear {







                    if case .loaded = feed.phase,
                       feed.loadedDuringTrackId != controller.current?.id {
                        Task { await loadFeed(force: true) }
                    }
                }
                .onChange(of: feed.refreshError) { _, message in




                    if let message { toast.show(message) }
                }
        }
    }



    private func loadFeed(force: Bool) async {
        await feed.load(force: force, epoch: auth.sessionEpoch)
        feed.noteLoaded(duringTrackId: controller.current?.id)
    }

    @ViewBuilder
    private var content: some View {
        switch feed.phase {
        case .loading:
            ScrollView { HomeFeedSkeleton() }
        case .failed(let message):
            EmptyStateView(
                icon: Image(.nuvxMusicNote),
                title: "Your feed couldn't load",
                subtitle: message,
                buttonTitle: "Retry"
            ) { Task { await feed.load() } }
        case .loaded(let shelves):
            let visibleShelves = shelves.compactMap { shelf -> FeedShelf? in
                if hideRecents && isRecentsShelf(shelf) { return nil }
                let items = hideHomeVideos ? shelf.items.filter { $0.isVideo != true } : shelf.items
                guard !items.isEmpty else { return nil }
                return FeedShelf(title: shelf.title, items: items, subtitle: shelf.subtitle)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    NuvxBrandMark()
                        .padding(.top, 8)
                        .padding(.bottom, 6)
                    if !auth.signedIn {
                        SignInBanner { auth.loginPresented = true }
                    }
                    if !hideTopPicks {
                        NuvxTopPicksShelf(shelves: visibleShelves)
                    }
                    ForEach(Array(visibleShelves.enumerated()), id: \.element.id) { index, shelf in
                        if index == 1 && !hideHomeMoods {
                            NuvxHomeMoods()
                        }
                        if index == 0, isRecentsShelf(shelf) {
                            RecentShelf(shelf: shelf)
                                .onAppear {
                                    if shelf.id == shelves.last?.id { Task { await feed.loadMore() } }
                                }
                        } else if index == 0 {
                            ShelfCarousel(shelf: shelf, preferTrack: true)
                                .onAppear {
                                    if shelf.id == shelves.last?.id { Task { await feed.loadMore() } }
                                }
                        } else {
                            ShelfCarousel(shelf: shelf, preferTrack: true)
                                .onAppear {
                                    if shelf.id == shelves.last?.id {
                                        Task { await feed.loadMore() }
                                    }
                                }
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
            .modifier(ExternalScrollPosition(position: scrollPosition))
        }
    }



    private func isRecentsShelf(_ shelf: FeedShelf) -> Bool {
        let title = shelf.title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()


        return title == "recents" || title == "recently played"
    }
}

private enum RecentShelfLayout {
    static let columnSpacing: CGFloat = 12

    static func columnWidth(viewportWidth: CGFloat) -> CGFloat {
        min(viewportWidth * 0.88, 400)
    }

    static func placeholderCount(availableWidth: CGFloat, itemWidth: CGFloat, spacing: CGFloat) -> Int {
        max(1, Int(ceil((availableWidth + spacing) / (itemWidth + spacing))))
    }
}



private struct HomeFeedSkeleton: View {
    @AppStorage("hide_recents") private var hideRecents = false
    @State private var viewType = PlatformSettings.shared.getString(
        key: "home_recents_view_type", default: "LIST"
    )

    private var isList: Bool { viewType.uppercased() != "GRID" }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            NuvxBrandMark().padding(.top, 8)
            if !hideRecents {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    RecentsSkeletonBlock(height: 22, cornerRadius: 6).frame(width: 112)
                    Spacer(minLength: 8)
                    RecentsSkeletonBlock(height: 32, cornerRadius: 16).frame(width: 32)
                }
                if isList {
                    GeometryReader { geometry in
                        let columnWidth = RecentShelfLayout.columnWidth(viewportWidth: geometry.size.width + 48)
                        let count = RecentShelfLayout.placeholderCount(
                            availableWidth: geometry.size.width, itemWidth: columnWidth,
                            spacing: RecentShelfLayout.columnSpacing
                        )
                        HStack(alignment: .top, spacing: RecentShelfLayout.columnSpacing) {
                            ForEach(0..<count, id: \.self) { _ in
                                VStack(spacing: 0) {
                                    ForEach(0..<4, id: \.self) { index in
                                        HStack(spacing: 12) {
                                            RecentsSkeletonBlock(height: 48, cornerRadius: 7).frame(width: 48)
                                            VStack(alignment: .leading, spacing: 7) {
                                                RecentsSkeletonBlock(height: 14, cornerRadius: 4)
                                                    .frame(width: [150.0, 112.0, 134.0, 96.0][index])
                                                RecentsSkeletonBlock(height: 12, cornerRadius: 4)
                                                    .frame(width: [92.0, 122.0, 78.0, 106.0][index])
                                            }
                                            Spacer(minLength: 4)
                                            RecentsSkeletonBlock(height: 20, cornerRadius: 10).frame(width: 20)
                                        }
                                        .frame(height: 56)
                                    }
                                }
                                .frame(width: columnWidth, alignment: .leading)
                            }
                        }
                        .frame(width: geometry.size.width, alignment: .leading)
                        .clipped()
                    }
                    .frame(height: 4 * 56)
                } else {
                    GeometryReader { geometry in
                        let count = RecentShelfLayout.placeholderCount(
                            availableWidth: geometry.size.width, itemWidth: 160, spacing: 14
                        )
                        HStack(alignment: .top, spacing: 14) {
                            ForEach(0..<count, id: \.self) { _ in
                                VStack(alignment: .leading, spacing: 8) {
                                    RecentsSkeletonBlock(height: 160, cornerRadius: 10).frame(width: 160)
                                    RecentsSkeletonBlock(height: 14, cornerRadius: 4).frame(width: 118)
                                    RecentsSkeletonBlock(height: 12, cornerRadius: 4).frame(width: 82)
                                }
                            }
                        }
                        .frame(width: geometry.size.width, alignment: .leading)
                        .clipped()
                    }
                    .frame(height: 202)
                }
            }
            }
            ForEach(0..<2, id: \.self) { shelf in
                SkeletonShelf(titleWidth: shelf == 0 ? 140 : 110, adaptive: true)
            }
        }

        .padding(.horizontal, 24)
        .padding(.vertical, 20)
    }
}



private struct RecentsSkeletonBlock: View {
    let height: CGFloat
    var cornerRadius: CGFloat = 6
    @State private var sweep = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(.quaternary)
                if !reduceMotion {
                    LinearGradient(
                        colors: [.clear, .white.opacity(0.22), .clear],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: geometry.size.width * 0.55)


                    .animation(.linear(duration: 1.4).repeatForever(autoreverses: false)) {
                        $0.offset(x: sweep ? geometry.size.width : -geometry.size.width * 0.55)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
        .frame(height: height)
        .onAppear { sweep = true }
        .accessibilityHidden(true)
    }
}





struct RecentShelf: View {
    let shelf: FeedShelf
    @Environment(PlaybackController.self) private var controller



    @State private var viewType: String = PlatformSettings.shared.getString(
        key: "home_recents_view_type", default: "LIST"
    )



    @State private var gridWidth: CGFloat = 0

    private var isList: Bool { viewType.uppercased() != "GRID" }


    private var heroSide: CGFloat {
        guard gridWidth > 0 else { return 240 }
        return min(gridWidth * 0.70, 320)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Recents").font(.title2.weight(.bold)).lineLimit(1)
                    if let subtitle = shelf.subtitle, !subtitle.isEmpty {
                        Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Button {
                    viewType = isList ? "GRID" : "LIST"
                    PlatformSettings.shared.putString(key: "home_recents_view_type", value: viewType)
                } label: {
                    Image(isList ? .nuvxGridView : .nuvxListView)
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(.secondary)
                        .frame(width: 19, height: 19)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .help(isList ? "Switch to grid view" : "Switch to list view")
                .accessibilityLabel(isList ? "Switch to grid view" : "Switch to list view")
            }
            if isList {
                GeometryReader { geometry in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(Array(shelf.items.chunked(4).enumerated()), id: \.offset) { _, column in
                                VStack(spacing: 0) {
                                    ForEach(column) { card in
                                        RecentTrackRow(card: card)
                                    }
                                }


                                .frame(width: RecentShelfLayout.columnWidth(viewportWidth: geometry.size.width), alignment: .leading)
                            }
                        }
                        .padding(.horizontal, 24)
                        .padding(.vertical, 2)
                    }
                }
                .frame(height: 4 * 56 + 4)
                .padding(.horizontal, -24)
            } else {




                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(shelf.items) { card in
                            HeroCard(card: card, preferTrack: true, side: heroSide)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .frame(height: heroSide * 0.92 + 4)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { gridWidth = $0 }
            }
        }
    }
}




private struct RecentTrackRow: View {
    let card: ShelfCard
    @Environment(PlaybackController.self) private var controller


    private var isCurrentTrack: Bool {
        guard let videoId = card.videoId, !videoId.isEmpty else { return false }
        return controller.current?.id == videoId
    }

    var body: some View {
        HStack(spacing: 8) {
            Button {
                if let videoId = card.videoId, !videoId.isEmpty {
                    controller.playRadio(QueueEntry.youtube(
                        videoId: videoId, title: card.title, artist: card.subtitle ?? "",
                        thumbnailUrl: card.thumbnailUrl
                    ), context: "Recents")
                }
            } label: {
                HStack(spacing: 12) {
                    ArtworkView(url: card.thumbnailUrl, data: nil, side: 48)
                        .clipShape(.rect(cornerRadius: 7, style: .continuous))
                        .modifier(NowPlayingCardAccent(
                            isCurrent: isCurrentTrack,
                            isPlaying: controller.isPlaying
                        ))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(card.title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(isCurrentTrack ? Theme.accent : .primary)
                            .lineLimit(1)
                        if let subtitle = card.subtitle, !subtitle.isEmpty {
                            Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 4)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .shelfCardContextMenu(card)

            Menu { ShelfCardActionButtons(card: card) } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: 36)
                    .contentShape(Circle())
            }
            #if os(macOS)

            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            #endif
            .tint(.gray)
            .accessibilityLabel("More actions for \(card.title)")
        }
        .padding(.vertical, 4)
    }
}

private extension Array {

    func chunked(_ size: Int) -> [[Element]] {
        guard size > 0 else { return [self] }
        var out: [[Element]] = []
        var index = startIndex
        while index < endIndex {
            let end = self.index(index, offsetBy: size, limitedBy: endIndex) ?? endIndex
            out.append(Array(self[index..<end]))
            index = end
        }
        return out
    }
}
