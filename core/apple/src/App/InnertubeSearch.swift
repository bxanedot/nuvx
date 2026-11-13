import Foundation
import NuvxShared

struct SearchHitDTO: Codable, Identifiable, Hashable {
    let kind: String
    let videoId: String?
    let title: String
    let subtitle: String?
    let thumbnailUrl: String?
    let durationText: String?
    let albumName: String?
    let browseId: String?
    let browseType: String?
    let artistId: String?
    let albumId: String?
    let isVideo: Bool?
    let setVideoId: String?


    var sourceQuality: String? = nil

    var id: String { videoId ?? browseId ?? title }

    var isBrowse: Bool { kind == "browse" && browseId != nil }







    var isTopResult: Bool { kind == "top" }


    var isTrack: Bool { !isBrowse && videoId != nil }

    var resolvedArtist: String {
        guard let raw = subtitle?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return ""
        }
        if raw.caseInsensitiveCompare("Unknown artist") == .orderedSame {
            return ""
        }
        if raw.contains(" • ") {
            let segments = raw.components(separatedBy: " • ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            for seg in segments {
                let lower = seg.lowercased()
                if ["song", "video", "album", "single", "ep", "artist", "playlist"].contains(lower) {
                    continue
                }
                if seg.range(of: #"^\d+:\d{2}$"#, options: .regularExpression) != nil {
                    continue
                }
                if seg.caseInsensitiveCompare("Unknown artist") != .orderedSame {
                    return seg
                }
            }
        }
        return raw
    }

    func asEntry() -> QueueEntry {
        let artistName = resolvedArtist
        if let videoId, videoId.hasPrefix("saavn:") {
            return QueueEntry(
                id: videoId, title: title, artist: artistName, source: videoId,
                thumbnailUrl: thumbnailUrl, durationText: durationText, albumName: albumName,
                artworkData: nil, isLocal: false
            )
        }


        if let videoId, videoId.hasPrefix("src:") {
            return QueueEntry(
                id: videoId, title: title, artist: artistName, source: videoId,
                thumbnailUrl: thumbnailUrl, durationText: durationText, albumName: albumName,
                artworkData: nil, isLocal: false
            )
        }
        return QueueEntry.youtube(
            videoId: videoId ?? id,
            title: title,
            artist: artistName,
            thumbnailUrl: thumbnailUrl,
            durationText: durationText,
            albumName: albumName,
            artistId: artistId,
            albumId: albumId,
            setVideoId: setVideoId
        )
    }
}



struct SearchPageDTO {
    let hits: [SearchHitDTO]
    let continuation: String?
}

final class InnertubeSearch: Sendable {
    static let shared = InnertubeSearch()



    func search(_ term: String, scope: String) async throws -> [SearchHitDTO] {
        try await searchPage(term, scope: scope).hits
    }

    func searchPage(_ term: String, scope: String) async throws -> SearchPageDTO {
        try await withCheckedThrowingContinuation { continuation in
            SearchBridge.shared.search(
                query: term,
                scope: scope,
                callback: BridgeCallbackAdapter { json, token, message in
                    if let json {
                        do {
                            let hits = try JSONDecoder()
                                .decode([SearchHitDTO].self, from: Data(json.utf8))
                            continuation.resume(returning: SearchPageDTO(hits: hits, continuation: token))
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume(throwing: SearchError(message: message ?? "search failed"))
                    }
                }
            )
        }
    }


    func searchContinuation(_ token: String, scope: String) async throws -> SearchPageDTO {
        try await withCheckedThrowingContinuation { continuation in
            SearchBridge.shared.searchContinuation(
                token: token,
                scope: scope,
                callback: BridgeCallbackAdapter { json, nextToken, message in
                    if let json {
                        do {
                            let hits = try JSONDecoder()
                                .decode([SearchHitDTO].self, from: Data(json.utf8))
                            continuation.resume(returning: SearchPageDTO(hits: hits, continuation: nextToken))
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume(throwing: SearchError(message: message ?? "search continuation failed"))
                    }
                }
            )
        }
    }

    func suggestions(_ input: String) async -> [String] {
        await withCheckedContinuation { continuation in
            SuggestionsBridge.shared.suggest(input: input, callback: SuggestAdapter { json, _ in
                guard let json, let data = json.data(using: .utf8),
                      let list = try? JSONDecoder().decode([String].self, from: data) else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: list)
            })
        }
    }




    func typeahead(_ input: String) async throws -> [SearchHitDTO] {
        try await withCheckedThrowingContinuation { continuation in
            SearchBridge.shared.searchTypeahead(
                query: input,
                callback: BridgeCallbackAdapter { json, _, message in
                    if let json {
                        do {
                            let hits = try JSONDecoder()
                                .decode([SearchHitDTO].self, from: Data(json.utf8))
                            continuation.resume(returning: hits)
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume(throwing: SearchError(message: message ?? "typeahead failed"))
                    }
                }
            )
        }
    }




    struct SearchError: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
}

private final class BridgeCallbackAdapter: SearchBridgeSearchCallback {
    private let onResult: (String?, String?, String?) -> Void
    init(onResult: @escaping (String?, String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, continuation: String?, message: String?) { onResult(json, continuation, message) }
}

private final class SuggestAdapter: SuggestionsBridgeSuggestionsCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}
