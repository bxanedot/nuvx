import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif


struct MacCardHoverLift: ViewModifier {
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        #if os(macOS)
        content
            .onHover { hovering = $0 }
            .scaleEffect(hovering && !reduceMotion ? 1.03 : 1)
            .shadow(color: .black.opacity(hovering ? 0.20 : 0), radius: hovering ? 12 : 0, y: hovering ? 5 : 0)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: hovering)
        #else
        content
        #endif
    }
}



@MainActor @Observable
final class FeedLoader {
    enum Source { case home, explore }
    enum Phase { case loading, loaded([FeedShelf]), failed(String) }
    static let home = FeedLoader(.home)
    static let explore = FeedLoader(.explore)
    private(set) var phase: Phase = .loading
    private(set) var loadingMore = false
    private(set) var refreshError: String?





    private(set) var loadedDuringTrackId: String?
    private var continuation: String?
    private var loadedEpoch: Int?
    private var context: PageContext?
    private var loadTask: Task<Void, Never>?
    private var requestID = UUID()
    private let source: Source
    private var pageName: String { source == .home ? "home" : "explore" }

    init(_ source: Source) { self.source = source }
    func load() async { await load(force: true, epoch: loadedEpoch) }


    func noteLoaded(duringTrackId: String?) {
        loadedDuringTrackId = duringTrackId
    }

    func load(force: Bool, epoch: Int?) async {
        let current = PageSession.capture()
        if let loadTask, context == current { await loadTask.value; return }
        if !force, context == current, loadedEpoch == epoch, case .loaded = phase { return }
        if context != current {
            loadTask?.cancel(); loadTask = nil
            phase = .loading; continuation = nil
        }
        context = current
        let id = UUID(); requestID = id
        let task = Task { await self.fetch(epoch: epoch, context: current, id: id) }
        loadTask = task
        await task.value
        if requestID == id { loadTask = nil }
    }

    private func fetch(epoch: Int?, context: PageContext, id: UUID) async {
        refreshError = nil
        continuation = nil
        do {
            let started = ContinuousClock.now
            var first = true
            for try await result in InnertubeFeed.shared.progressive(home: source == .home) {
                guard requestID == id, PageSession.generation() == context.generation, !Task.isCancelled else { return }
                if !result.shelves.isEmpty {
                    phase = .loaded(result.shelves)
                    Task { await LaunchReadiness.shared.contentAppeared() }
                    if first {
                        PlaybackDebugLog.shared.record("\(pageName) first content: \(started.duration(to: .now))")
                        first = false
                    }
                }
                continuation = result.continuation
                loadedEpoch = epoch

            }
        } catch {
            guard requestID == id, PageSession.generation() == context.generation, !Task.isCancelled else { return }
            continuation = nil


            AuthController.reportIfSessionExpired(error)
            if case .loaded = phase { refreshError = error.localizedDescription }
            else { phase = .failed(error.localizedDescription) }
        }
    }

    func loadMore() async {
        guard let context, PageSession.generation() == context.generation,
              let token = continuation, !token.isEmpty, !loadingMore else { return }
        let id = requestID
        loadingMore = true
        defer { loadingMore = false }
        do {
            let result = source == .home ? try await InnertubeFeed.shared.moreHome(token: token)
                : try await InnertubeFeed.shared.moreExplore(token: token)
            guard requestID == id, PageSession.generation() == context.generation, !Task.isCancelled else { return }
            continuation = result.continuation
            if case .loaded(let existing) = phase {
                let seen = Set(existing.map(\.title))
                phase = .loaded(existing + result.shelves.filter { !seen.contains($0.title) })
            }
        } catch {
            guard requestID == id, PageSession.generation() == context.generation, !Task.isCancelled else { return }
            AuthController.reportIfSessionExpired(error)
            refreshError = error.localizedDescription
        }
    }
}






struct FeedSectionHeader: View {
    let title: String
    var subtitle: String? = nil
    var onShowAll: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .lineLimit(2)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if let onShowAll {
                Button(action: onShowAll) {
                    HStack(spacing: 2) {
                        Text("Show all")
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                    }
                    .font(.callout)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                .accessibilityLabel("Show all \(title)")
            }
        }
    }
}




struct ShelfCarousel: View {
    let shelf: FeedShelf


    var items: [ShelfCard]? = nil


    var preferTrack: Bool = false
    var onShowAll: (() -> Void)? = nil

    var inset: CGFloat = 24
    @Environment(PlaybackController.self) private var controller
    @Environment(\.horizontalSizeClass) private var sizeClass


    @State private var width: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FeedSectionHeader(title: shelf.title, subtitle: shelf.subtitle, onShowAll: onShowAll)
            ShelfScroller(inset: inset) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(items ?? shelf.items) { card in
                        ShelfCardView(card: card, artworkSide: cardSide, preferTrack: preferTrack)
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }





    private var cardSide: CGFloat { Self.cardSide(width: width, sizeClass: sizeClass) }



    static func cardSide(width: CGFloat, sizeClass: UserInterfaceSizeClass?) -> CGFloat {
        guard sizeClass == .regular, width > 0 else { return Theme.Metrics.shelfCard }
        let quarter = (width - 3 * 14) / 4
        return min(240, max(Theme.Metrics.shelfCard, quarter.rounded(.down)))
    }
}




struct SkeletonGrid: View {
    var showsTitle = false
    var count = 18

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if showsTitle {
                SkeletonBlock(height: 22, cornerRadius: 6).frame(width: 140)
                    .padding(.horizontal, 24)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 180), spacing: 16)], spacing: 20) {
                ForEach(0..<count, id: \.self) { index in
                    VStack(alignment: .leading, spacing: 8) {
                        SkeletonBlock(height: Theme.Metrics.shelfCard, cornerRadius: 12)
                        SkeletonBlock(height: 14, cornerRadius: 4)
                            .frame(width: Theme.Metrics.shelfCard * [0.74, 0.62, 0.8, 0.56][index % 4])
                        SkeletonBlock(height: 12, cornerRadius: 4)
                            .frame(width: Theme.Metrics.shelfCard * [0.5, 0.42, 0.55, 0.38][index % 4])
                    }
                    .frame(width: Theme.Metrics.shelfCard, alignment: .leading)
                }
            }
            .padding(.horizontal, 24)
        }
        .padding(.vertical, 20)
        .accessibilityElement()
        .accessibilityLabel("Loading")
    }
}

private extension View {
    func wrappedInStillScroller(inset: CGFloat) -> some View {
        ScrollView(.horizontal, showsIndicators: false) { self }
            .scrollDisabled(true)
            .contentMargins(.horizontal, inset, for: .scrollContent)
            .padding(.horizontal, -inset)
    }
}




struct SkeletonShelf: View {
    var titleWidth: CGFloat = 140
    var inset: CGFloat = 24


    var adaptive = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0

    var body: some View {
        let side = adaptive
            ? ShelfCarousel.cardSide(width: width, sizeClass: sizeClass)
            : Theme.Metrics.shelfCard
        let count = width > 0 ? Int((width / (side + 14)).rounded(.up)) + 1 : 2
        VStack(alignment: .leading, spacing: 10) {
            SkeletonBlock(height: 22, cornerRadius: 6).frame(width: titleWidth)
            HStack(alignment: .top, spacing: 14) {
                ForEach(0..<count, id: \.self) { index in
                    VStack(alignment: .leading, spacing: 8) {
                        SkeletonBlock(height: side, cornerRadius: 12)
                        SkeletonBlock(height: 14, cornerRadius: 4)
                            .frame(width: side * [0.74, 0.62, 0.8, 0.56][index % 4])
                        SkeletonBlock(height: 12, cornerRadius: 4)
                            .frame(width: side * [0.5, 0.42, 0.55, 0.38][index % 4])
                    }
                    .frame(width: side, alignment: .leading)
                }
            }
            .padding(.vertical, 2)


            .wrappedInStillScroller(inset: inset)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .accessibilityHidden(true)
    }
}









struct ShelfScroller<Content: View>: View {
    var inset: CGFloat = 24
    @ViewBuilder var content: Content

    @State private var position = ScrollPosition(edge: .leading)
    @State private var offset: CGFloat = 0
    @State private var viewport: CGFloat = 0
    @State private var extent: CGFloat = 0
    @State private var hovering = false

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            content.scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .contentMargins(.horizontal, inset, for: .scrollContent)
        .scrollPosition($position)
        .onScrollGeometryChange(for: [CGFloat].self) { geometry in
            [geometry.contentOffset.x, geometry.containerSize.width, geometry.contentSize.width]
        } action: { _, value in
            offset = value[0]; viewport = value[1]; extent = value[2]
        }
        .padding(.horizontal, -inset)
        .overlay(alignment: .leading) { pager(forward: false) }
        .overlay(alignment: .trailing) { pager(forward: true) }
        .onHover { hovering = $0 }
    }

    @ViewBuilder
    private func pager(forward: Bool) -> some View {
        let canPage = forward ? offset + viewport < extent - 1 : offset > -inset + 1
        if hovering, canPage {
            Button {
                let page = max(viewport - inset * 3, 120)
                let target = forward
                    ? min(offset + page, extent - viewport)
                    : max(offset - page, -inset)
                withAnimation(.smooth(duration: 0.35)) { position.scrollTo(x: target) }
            } label: {
                Image(systemName: forward ? "chevron.right" : "chevron.left")
                    .font(.body.weight(.semibold))
                    .frame(width: 34, height: 34)
                    .background(.regularMaterial, in: .circle)
                    .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 8)
            .help(forward ? "Next" : "Previous")
            .accessibilityLabel(forward ? "Scroll shelf forward" : "Scroll shelf back")
            .transition(.opacity)
        }
    }
}





struct ShelfCardTap<Content: View>: View {
    let card: ShelfCard
    var preferTrack: Bool = false
    @ViewBuilder var content: Content
    @Environment(PlaybackController.self) private var controller

    var body: some View {
        Group {
            if preferTrack, let videoId = card.videoId, !videoId.isEmpty {
                trackButton(videoId)
            } else if let browseId = card.browseId, !browseId.isEmpty {
                PushLink(destination: DetailView(browseId: browseId, initialTitle: card.title)) {
                    content
                }
                .buttonStyle(.plain)
            } else if let videoId = card.videoId, !videoId.isEmpty {
                trackButton(videoId)
            } else {
                content
            }
        }
    }

    private func trackButton(_ videoId: String) -> some View {
        Button {
            controller.playRadio(QueueEntry.youtube(
                videoId: videoId, title: card.title, artist: card.subtitle ?? "",
                thumbnailUrl: card.thumbnailUrl
            ))
        } label: {
            content
                .overlay(alignment: .topLeading) {
                    if controller.current?.id == videoId && controller.isBuffering {
                        ProgressView()
                            .controlSize(.small)
                            .padding(8)
                    }
                }
        }
        .buttonStyle(.plain)
    }
}





struct NowPlayingCardAccent: ViewModifier {
    let isCurrent: Bool
    let isPlaying: Bool

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottomLeading) {
                if isCurrent, isPlaying {
                    PlayingBars(color: .white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 5)
                        .background(.black.opacity(0.52), in: Capsule())
                        .padding(6)
                }
            }
    }
}

struct ShelfCardView: View {
    let card: ShelfCard


    var artworkSide: CGFloat = Theme.Metrics.shelfCard
    @Environment(PlaybackController.self) private var controller


    @State private var hovering = false

    var body: some View {
        ShelfCardTap(card: card, preferTrack: preferTrack) {
            cardContent
        }
    }









    var preferTrack: Bool = false



    private var isPinned: Bool {
        guard let browseId = card.browseId, !browseId.isEmpty else { return false }
        return PlaylistPinning.pinnedIds().contains(browseId)
    }

    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .topTrailing) {
                ArtworkView(url: card.thumbnailUrl, data: nil, side: artworkSide)
                    .clipShape(.rect(cornerRadius: 10, style: .continuous))
                    .shadow(color: .black.opacity(0.15), radius: 5, y: 2)
                    .modifier(NowPlayingCardAccent(
                        isCurrent: isCurrentTrack,
                        isPlaying: controller.isPlaying
                    ))
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(6)
                        .background(.thinMaterial, in: Circle())
                        .padding(6)
                        .accessibilityLabel("Pinned")
                }
            }
            Text(card.title)
                .font(.callout.weight(.semibold))
                .foregroundStyle(isCurrentTrack ? Theme.accent : .primary)
                .lineLimit(1)
            if let subtitle = card.subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: artworkSide, alignment: .leading)


        .background {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(hovering ? Color.primary.opacity(0.06) : .clear)
                .padding(-6)
        }
        .contentShape(.rect)
        .onHover { hovering = $0 }
        .modifier(MacCardHoverLift())
        .shelfCardContextMenu(card)
    }


    private var isCurrentTrack: Bool {
        guard let videoId = card.videoId, !videoId.isEmpty else { return false }
        return controller.current?.id == videoId
    }
}






struct ExternalScrollPosition: ViewModifier {
    let position: Binding<ScrollPosition>?

    func body(content: Content) -> some View {
        if let position {
            content.scrollPosition(position)
        } else {
            content
        }
    }
}


enum BrowseDestination: Hashable, Identifiable {
    case detail(browseId: String, title: String)
    var id: String {
        switch self {
        case .detail(let browseId, _): browseId
        }
    }
}



struct SignInBanner: View {
    var onSignIn: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "person.crop.circle")
                .font(.title2)
                .foregroundStyle(.secondary)
                .decorative()
            VStack(alignment: .leading, spacing: 2) {
                Text("Sign in to YouTube Music")
                    .font(.headline)
                Text("Personalized recommendations and your library, on every device.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            Button("Sign In", action: onSignIn)
                .buttonStyle(.bordered)
        }
        .padding(16)
        .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 14, style: .continuous))
    }
}


struct FeedSkeleton: View {

    var padded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            ForEach(0..<3, id: \.self) { index in
                SkeletonShelf(titleWidth: [140, 110, 160][index])
            }
        }
        .padding(.horizontal, padded ? 24 : 0)
        .padding(.vertical, padded ? 20 : 0)
    }
}







struct HeroCard: View {
    let card: ShelfCard
    var preferTrack: Bool = false

    let side: CGFloat
    @Environment(PlaybackController.self) private var controller
    @State private var hovering = false


    private var isCurrentTrack: Bool {
        guard let videoId = card.videoId, !videoId.isEmpty else { return false }
        return controller.current?.id == videoId
    }

    var body: some View {
        ShelfCardTap(card: card, preferTrack: preferTrack) {
            ZStack(alignment: .bottom) {
                ArtworkView(url: card.thumbnailUrl, data: nil, side: side)
                    .frame(width: side, height: side * 0.92)
                LinearGradient(
                    colors: [.clear, .black.opacity(0.10), .black.opacity(0.72)],
                    startPoint: .center,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.title)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(isCurrentTrack ? Theme.accent : .white)
                        .lineLimit(1)
                    if let subtitle = card.subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.78))
                            .lineLimit(1)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(width: side, height: side * 0.92)


            .modifier(NowPlayingCardAccent(
                isCurrent: isCurrentTrack,
                isPlaying: controller.isPlaying
            ))
            .clipShape(.rect(cornerRadius: 18, style: .continuous))
            .shadow(color: .black.opacity(0.15), radius: 5, y: 2)


            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(hovering ? Color.primary.opacity(0.06) : .clear)
                    .padding(-6)
            }
            .contentShape(.rect)
            .onHover { hovering = $0 }
            .modifier(MacCardHoverLift())
            .shelfCardContextMenu(card)
        }
    }
}



struct HeroShelf: View {
    let shelf: FeedShelf
    var preferTrack: Bool = false


    @State private var width: CGFloat = 0


    private var heroSide: CGFloat {
        guard width > 0 else { return 240 }
        return min(width * 0.70, 320)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(shelf.title)
                .font(.title2.weight(.bold))
            ShelfScroller {
                LazyHStack(spacing: 16) {
                    ForEach(shelf.items) { card in
                        HeroCard(card: card, preferTrack: preferTrack, side: heroSide)
                    }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}
