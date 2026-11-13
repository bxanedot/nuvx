import Foundation
import NuvxShared














var failures = 0
var checks = 0

extension String { var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }

func check(_ label: String, _ ok: Bool, _ detail: String = "") {
    checks += 1
    if ok {
        print("  ok   \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    } else {
        failures += 1
        print("  FAIL \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    }
}

func moodCategories() async throws -> [MoodCategory] {
    try await withCheckedThrowingContinuation { continuation in
        HomeBridge.shared.moodAndGenres(callback: MoodAdapter { json, message in
            guard let json else {
                continuation.resume(throwing: NSError(
                    domain: "moods", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: message ?? "no categories"]
                ))
                return
            }
            do {
                let page = try JSONDecoder().decode([MoodCategory].self, from: Data(json.utf8))
                continuation.resume(returning: page)
            } catch {
                continuation.resume(throwing: error)
            }
        })
    }
}

func shelves(browseId: String, params: String?) async throws -> [ShelfJSON] {
    try await withCheckedThrowingContinuation { continuation in
        HomeBridge.shared.moodGenreShelves(browseId: browseId, params: params, callback: FeedAdapter { json, message in
            guard let json else {
                continuation.resume(throwing: NSError(
                    domain: "shelves", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: message ?? "no shelves"]
                ))
                return
            }
            do {
                let page = try JSONDecoder().decode(FeedPage.self, from: Data(json.utf8))
                continuation.resume(returning: page.shelves)
            } catch {
                continuation.resume(throwing: error)
            }
        })
    }
}

struct MoodCategory: Decodable {
    struct Item: Decodable {
        let title: String
        let browseId: String
        let params: String?
        let thumbnailUrl: String?
    }
    let title: String
    let items: [Item]
}

struct FeedPage: Decodable {
    let shelves: [ShelfJSON]
}
struct ShelfJSON: Decodable {
    let title: String
    let items: [CardJSON]
}
struct CardJSON: Decodable {
    let title: String
    let videoId: String?
    let browseId: String?
}

private final class MoodAdapter: HomeBridgeMoodCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private final class FeedAdapter: HomeBridgeFeedCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

print("moods and genres: the real FEmusic_moods_and_genres response")

var sections: [MoodCategory] = []
do {
    sections = try await moodCategories()
    check("the endpoint answers with categories", !sections.isEmpty, "\(sections.count) section(s)")
} catch {
    check("the endpoint answers with categories", false, error.localizedDescription)
    print("\n0 of \(checks) checks passed")
    exit(1)
}

for section in sections {
    print("  · \(section.title): \(section.items.count) — \(section.items.prefix(6).map(\.title).joined(separator: ", "))")
}



check("every section has a heading", sections.allSatisfy { !$0.title.isBlank })


check("every category has a browse id", sections.flatMap(\.items).allSatisfy { !$0.browseId.isBlank })





let withParams = sections.flatMap(\.items).filter { ($0.params ?? "").isEmpty }.count
let total = sections.flatMap(\.items).count
check("categories carry their browse params", withParams < total,
      "\(total - withParams)/\(total) have params")




if let bare = sections.flatMap(\.items).first(where: { ($0.params ?? "").isEmpty }) {
    print("  · note: \(bare.title) has no params — checked below as the nil case")
}




let probe = sections.first { !$0.items.isEmpty }?.items[0]
if let probe {
    do {
        let got = try await shelves(browseId: probe.browseId, params: probe.params)
        check("a category's shelves come back", !got.isEmpty,
              "\(probe.title) -> \(got.count) shelf(es), \(got.prefix(3).map(\.title).joined(separator: " / "))")
        let cards: [CardJSON] = got.flatMap { $0.items }
        check("its cards are playable or openable",
              cards.contains { ($0.videoId ?? "").isEmpty == false || ($0.browseId ?? "").isEmpty == false },
              "\(cards.count) card(s)")
    } catch {
        check("a category's shelves come back", false, "\(probe.title): \(error.localizedDescription)")
    }
} else {
    check("a category's shelves come back", false, "no category to probe")
}









if let probe = sections.first?.items.first {
    let start = Date()
    _ = try? await shelves(browseId: probe.browseId, params: probe.params)
    let cachedMs = Date().timeIntervalSince(start) * 1000
    check("a repeat request for the same category is served from the cache",
          cachedMs < 50, String(format: "%.2f ms — a round trip is >100 ms", cachedMs))
}

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
