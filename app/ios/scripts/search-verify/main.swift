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

struct SearchHitJSON: Decodable {
    let kind: String
    let videoId: String?
    let title: String
    let browseId: String?
    let sourceQuality: String?
}

private final class SearchAdapter: SearchBridgeSearchCallback {
    private let onResult: (String?, String?, String?) -> Void
    init(onResult: @escaping (String?, String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, continuation: String?, message: String?) {
        onResult(json, continuation, message)
    }
}


func searchPage(_ term: String, scope: String) async throws -> ([SearchHitJSON], String?) {
    try await withCheckedThrowingContinuation { continuation in
        SearchBridge.shared.search(
            query: term,
            scope: scope,
            callback: SearchAdapter { json, token, message in
                guard let json else {
                    continuation.resume(throwing: NSError(
                        domain: "search", code: 1,
                        userInfo: [NSLocalizedDescriptionKey: message ?? "no answer"]
                    ))
                    return
                }
                do {
                    let hits = try JSONDecoder().decode([SearchHitJSON].self, from: Data(json.utf8))
                    continuation.resume(returning: (hits, token))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        )
    }
}


func typeahead(_ term: String) async throws -> [SearchHitJSON] {
    try await withCheckedThrowingContinuation { continuation in
        SearchBridge.shared.searchTypeahead(
            query: term,
            callback: SearchAdapter { json, _, message in
                guard let json else {
                    continuation.resume(throwing: NSError(
                        domain: "typeahead", code: 1,
                        userInfo: [NSLocalizedDescriptionKey: message ?? "no answer"]
                    ))
                    return
                }
                do {
                    let hits = try JSONDecoder().decode([SearchHitJSON].self, from: Data(json.utf8))
                    continuation.resume(returning: hits)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        )
    }
}


func searchContinuation(_ token: String, scope: String) async throws -> ([SearchHitJSON], String?) {
    try await withCheckedThrowingContinuation { continuation in
        SearchBridge.shared.searchContinuation(
            token: token,
            scope: scope,
            callback: SearchAdapter { json, next, message in
                guard let json else {
                    continuation.resume(throwing: NSError(
                        domain: "continuation", code: 1,
                        userInfo: [NSLocalizedDescriptionKey: message ?? "no answer"]
                    ))
                    return
                }
                do {
                    let hits = try JSONDecoder().decode([SearchHitJSON].self, from: Data(json.utf8))
                    continuation.resume(returning: (hits, next))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        )
    }
}

print("search: the real anonymous typeahead and continuation responses")




var typeaheadHits: [SearchHitJSON] = []
do {
    typeaheadHits = try await typeahead("rihanna")
    check("the anonymous typeahead answers with media rows", !typeaheadHits.isEmpty,
          "\(typeaheadHits.count) row(s)")
} catch {
    check("the anonymous typeahead answers with media rows", false, error.localizedDescription)
}

if !typeaheadHits.isEmpty {
    print("  · \(typeaheadHits.prefix(5).map { "\($0.title) [\($0.kind)]" }.joined(separator: " / "))")
    check("every typeahead row has a video id or a browse id",
          typeaheadHits.allSatisfy { !($0.videoId ?? "").isEmpty || !($0.browseId ?? "").isEmpty })
}



var committedHits: [SearchHitJSON] = []
do {
    let page = try await searchPage("rihanna", scope: "ALL")
    committedHits = page.0
    check("the committed search answers", !committedHits.isEmpty, "\(committedHits.count) row(s)")
} catch {
    check("the committed search answers", false, error.localizedDescription)
}



do {
    let first = try await searchPage("rihanna", scope: "SONGS")
    check("a filtered search answers", !first.0.isEmpty, "\(first.0.count) row(s)")
    if let token = first.1, !token.isBlank {
        let second = try await searchContinuation(token, scope: "SONGS")
        check("its continuation returns another page", !second.0.isEmpty,
              "\(second.0.count) row(s), next token \(second.1?.isEmpty == false ? "present" : "absent")")
    } else {
        check("its continuation returns another page", false, "no continuation token in the first page")
    }
} catch {
    check("a filtered search answers", false, error.localizedDescription)
}

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
