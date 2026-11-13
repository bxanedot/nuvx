import Foundation
import NuvxShared




struct FeedShelf: Codable, Identifiable, Hashable {
    let title: String
    let items: [ShelfCard]
    let subtitle: String?

    var id: String { title }

    enum CodingKeys: String, CodingKey { case title, items, subtitle }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        items = try c.decodeIfPresent([ShelfCard].self, forKey: .items) ?? []
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle)
    }
    init(title: String, items: [ShelfCard], subtitle: String? = nil) {
        self.title = title; self.items = items; self.subtitle = subtitle
    }
}

struct YouTubeSong: Codable, Identifiable, Hashable {
    let videoId: String
    let title: String
    let artist: String
    let thumbnailUrl: String?
    let durationText: String?
    let artistId: String?
    let albumId: String?
    let albumName: String?
    let isVideo: Bool?
    let setVideoId: String?
    var id: String { videoId }
}


struct ShelfCard: Codable, Identifiable, Hashable {
    let title: String
    let subtitle: String?
    let thumbnailUrl: String?
    let videoId: String?
    let browseId: String?
    let isVideo: Bool?

    var id: String { videoId ?? browseId ?? title }

    enum CodingKeys: String, CodingKey { case title, subtitle, thumbnailUrl, videoId, browseId, isVideo }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle)
        thumbnailUrl = try c.decodeIfPresent(String.self, forKey: .thumbnailUrl)
        videoId = try c.decodeIfPresent(String.self, forKey: .videoId)
        browseId = try c.decodeIfPresent(String.self, forKey: .browseId)
        isVideo = try c.decodeIfPresent(Bool.self, forKey: .isVideo)
    }
    init(
        title: String,
        subtitle: String? = nil,
        thumbnailUrl: String? = nil,
        videoId: String? = nil,
        browseId: String? = nil,
        isVideo: Bool? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
        self.videoId = videoId
        self.browseId = browseId
        self.isVideo = isVideo
    }
}

private struct FeedPage: Codable {
    let shelves: [FeedShelf]
    let continuation: String?
}





struct FeedResult: Codable, Sendable {
    let shelves: [FeedShelf]
    let continuation: String?
}




struct MoodGenreSection: Codable, Identifiable {
    let title: String


    var items: [MoodGenre]
    var id: String { title }
}






struct MoodGenre: Codable, Identifiable, Hashable {
    let title: String
    let browseId: String
    let params: String?



    var thumbnailUrl: String?


    let stripeColor: Int64?
    var id: String { "\(browseId)?\(params ?? "")" }
}

final class InnertubeFeed: Sendable {
    static let shared = InnertubeFeed()

    func progressive(home: Bool) -> AsyncThrowingStream<FeedResult, Error> {
        AsyncThrowingStream { stream in
            let callback = ProgressiveFeedAdapter { payload, complete, message in
                if let payload {
                    do {
                        let result = try JSONDecoder().decode(FeedResult.self, from: Data(payload.utf8))
                        stream.yield(result)
                        if complete { stream.finish() }
                    } catch { stream.finish(throwing: error) }
                } else if complete { stream.finish(throwing: FeedError(message: message ?? "Feed unavailable")) }
            }
            let requestId = home ? HomeBridge.shared.homeProgressive(callback: callback)
                : HomeBridge.shared.exploreProgressive(callback: callback)
            stream.onTermination = { _ in HomeBridge.shared.cancelFeed(requestId: requestId) }
        }
    }

    func home() async throws -> FeedResult {
        try await fetch { HomeBridge.shared.home(callback: $0) }
    }

    func moreHome(token: String) async throws -> FeedResult {
        try await fetch { HomeBridge.shared.moreHome(token: token, callback: $0) }
    }

    func explore() async throws -> FeedResult {
        try await fetch { HomeBridge.shared.explore(callback: $0) }
    }

    func moreExplore(token: String) async throws -> FeedResult {
        try await fetch { HomeBridge.shared.moreExplore(token: token, callback: $0) }
    }


    @MainActor
    func moodAndGenres(force: Bool = false) async throws -> [MoodGenreSection] {
        try await PageRequests.load("moodAndGenres", force: force) { try await self.raw_moodAndGenres() }
    }

    private func raw_moodAndGenres() async throws -> [MoodGenreSection] {
        try await withCheckedThrowingContinuation { continuation in
            HomeBridge.shared.moodAndGenres(callback: MoodCallbackAdapter { json, message in
                guard let json else {
                    continuation.resume(throwing: InnertubeFeed.FeedError(
                        message: message ?? "no mood or genre categories"
                    ))
                    return
                }
                do {
                    continuation.resume(returning: try JSONDecoder().decode(
                        [MoodGenreSection].self, from: Data(json.utf8)
                    ))
                } catch {
                    continuation.resume(throwing: error)
                }
            })
        }
    }


    @MainActor
    func moodGenreShelves(browseId: String, params: String?, force: Bool = false) async throws -> [FeedShelf] {
        try await PageRequests.load("mood:\(browseId):\(params ?? "")", force: force) {
            try await self.fetch {
                HomeBridge.shared.moodGenreShelves(browseId: browseId, params: params, callback: $0)
            }.shelves
        }
    }



    @MainActor
    func moodGenreArtwork(browseId: String, params: String?) async -> String? {
        guard let shelves = try? await moodGenreShelves(browseId: browseId, params: params) else { return nil }
        return shelves.lazy.flatMap(\.items).compactMap(\.thumbnailUrl).first
    }



    @MainActor
    func podcasts(force: Bool = false) async throws -> [FeedShelf] {
        try await PageRequests.load("podcasts", force: force) {
            try await self.raw_podcasts()
        }
    }

    private func raw_podcasts() async throws -> [FeedShelf] {
        try await withCheckedThrowingContinuation { continuation in
            HomeBridge.shared.podcasts(callback: FeedCallbackAdapter { json, message in
                if let json {
                    do {
                        let page = try JSONDecoder().decode(FeedPage.self, from: Data(json.utf8))
                        continuation.resume(returning: page.shelves)
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: FeedError(message: message ?? "Podcasts feed failed"))
                }
            })
        }
    }

    @MainActor
    func history(force: Bool = false) async throws -> [YouTubeSong] {
        try await PageRequests.load("history", force: force) { try await self.raw_history() }
    }

    private func raw_history() async throws -> [YouTubeSong] {
        try await withCheckedThrowingContinuation { continuation in
            HomeBridge.shared.history(callback: FeedCallbackAdapter { json, message in
                if let json {
                    do {
                        let songs = try JSONDecoder().decode([YouTubeSong].self, from: Data(json.utf8))
                        continuation.resume(returning: songs)
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: FeedError(message: message ?? "history failed"))
                }
            })
        }
    }

    @MainActor
    func library(force: Bool = false) async throws -> [FeedShelf] {
        try await PageRequests.load("library", force: force) { try await self.raw_library() }
    }

    private func raw_library() async throws -> [FeedShelf] {
        try await withCheckedThrowingContinuation { continuation in
            LibraryBridge.shared.library(callback: LibraryFeedAdapter { json, message in
                if let json {
                    do {
                        let page = try JSONDecoder().decode(FeedPage.self, from: Data(json.utf8))
                        continuation.resume(returning: page.shelves)
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: FeedError(message: message ?? "library failed"))
                }
            })
        }
    }




    @MainActor
    func librarySongs(force: Bool = false) async throws -> [YouTubeSong] {
        try await PageRequests.load("librarySongs", force: force) { try await self.raw_librarySongs() }
    }

    private func raw_librarySongs() async throws -> [YouTubeSong] {
        try await withCheckedThrowingContinuation { continuation in
            LibraryBridge.shared.librarySongs(callback: LibraryFeedAdapter { json, message in
                if let json {
                    do {
                        let songs = try JSONDecoder().decode([YouTubeSong].self, from: Data(json.utf8))
                        continuation.resume(returning: songs)
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: FeedError(message: message ?? "library songs failed"))
                }
            })
        }
    }

    private func fetch(_ call: @escaping (HomeBridgeFeedCallback) -> Void) async throws -> FeedResult {
        try await withCheckedThrowingContinuation { continuation in
            call(FeedCallbackAdapter { json, message in
                if let json {
                    do {
                        let page = try JSONDecoder().decode(FeedPage.self, from: Data(json.utf8))
                        continuation.resume(returning: FeedResult(
                            shelves: page.shelves,
                            continuation: page.continuation
                        ))
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: FeedError(message: message ?? "feed failed"))
                }
            })
        }
    }

    struct FeedError: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
}

private final class FeedCallbackAdapter: HomeBridgeFeedCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private final class MoodCallbackAdapter: HomeBridgeMoodCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private final class ArtworkCallbackAdapter: HomeBridgeArtworkCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(url: String?, message: String?) { onResult(url, message) }
}

private final class LibraryFeedAdapter: LibraryBridgeFeedCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private final class ProgressiveFeedAdapter: HomeBridgeProgressiveCallback {
    let result: (String?, Bool, String?) -> Void
    init(_ result: @escaping (String?, Bool, String?) -> Void) { self.result = result }
    func onResult(json: String?, complete: Bool, message: String?) { result(json, complete, message) }
}
