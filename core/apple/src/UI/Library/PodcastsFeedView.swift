import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import NuvxShared



struct PodcastsFeedView: View {
    @Environment(AuthController.self) private var auth
    @State private var phase: Phase = .loading

    private enum Phase {
        case loading
        case loaded([FeedShelf])
        case failed(String)
    }

    var body: some View {
        PageStack {
            content
                .navigationTitle("Podcasts")
                .toolbar {
                    #if os(iOS)
                    TopBarLeadingItems()
                    ToolbarItem(placement: .topBarTrailing) {
                        TopBarAccountButton()
                    }
                    #endif
                }
                .refreshable { await load(force: true) }
                .task(id: auth.sessionEpoch) { await load(force: false) }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ScrollView { FeedSkeleton() }
        case .failed(let message):
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "Podcasts couldn't load",
                subtitle: message,
                buttonTitle: "Retry"
            ) {
                Task { await load(force: true) }
            }
        case .loaded(let shelves):
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    ForEach(shelves) { shelf in
                        ShelfCarousel(shelf: shelf, preferTrack: true)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
        }
    }

    private func load(force: Bool) async {
        phase = .loading
        do {
            let shelves = try await InnertubeFeed.shared.podcasts(force: force)
            guard !Task.isCancelled else { return }
            phase = .loaded(shelves)
        } catch {
            guard !Task.isCancelled else { return }
            AuthController.reportIfSessionExpired(error)
            phase = .failed(error.localizedDescription)
        }
    }
}
