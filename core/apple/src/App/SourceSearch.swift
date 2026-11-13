import Foundation
import NuvxShared







final class SourceSearch: Sendable {
    static let shared = SourceSearch()

    func addons(_ query: String) async throws -> [SearchHitDTO] {
        try await withCheckedThrowingContinuation { continuation in
            SourceBridge.shared.searchAddons(
                query: query,
                callback: AddonRowsAdapter { json, message in
                    if let json {
                        do {
                            let hits = try JSONDecoder()
                                .decode([SearchHitDTO].self, from: Data(json.utf8))
                            continuation.resume(returning: hits)
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume(throwing: AddonSearchError(message: message ?? "addon search failed"))
                    }
                }
            )
        }
    }




    func stream(key: String, title: String, artist: String, durationSec: Int?, diagnostic: @escaping @Sendable (String) -> Void = { _ in }) async -> SourceSubstitution.Stream? {
        await withCheckedContinuation { continuation in
            SourceResolverBridge.shared.resolveAddonTrack(
                key: key,
                title: title,
                artist: artist,
                durationSec: durationSec.map { KotlinInt(value: Int32($0)) },
                callback: AddonStreamAdapter { json, message in
                    if let message { diagnostic(message) }
                    continuation.resume(returning: json.flatMap(SourceSubstitution.decode))
                }
            )
        }
    }



    struct AddonSearchError: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
}

private final class AddonRowsAdapter: SourceBridgeRowCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private final class AddonStreamAdapter: SourceResolverBridgeStreamCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}
