import Foundation
import NuvxShared



















enum SourceSubstitution {

    struct Stream: Sendable {
        var url: String
        var headers: [String: String]
        var codec: String?
        var kbps: Int?
        var lossless: Bool
        var isDolbyAtmos: Bool
        var belowRequest: Bool
        var durationSec: Int?
        var sourceConfigId: String?
        var summary: String
    }






    static func substitute(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = nil,
        isExplicit: KotlinBoolean? = nil,
        isVideo: Bool = false,
        playing: Format? = nil,
        diagnostic: @escaping @Sendable (String) -> Void = { _ in }
    ) async -> Stream? {



        if let playing, let durationSec, !isVideo {
            return await upgrade(
                title: title, artist: artist, durationSec: durationSec,
                album: album, isExplicit: isExplicit, isVideo: isVideo, playing: playing, diagnostic: diagnostic
            )
        }
        return await call(diagnostic: diagnostic) { callback in
            SourceResolverBridge.shared.substituteForYouTube(
                title: title,
                artist: artist,
                durationSec: durationSec.map { KotlinInt(value: Int32($0)) },
                album: album,
                isExplicit: isExplicit,
                isVideo: isVideo,
                callback: callback
            )
        }
    }





    static func upgrade(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = nil,
        isExplicit: KotlinBoolean? = nil,
        isVideo: Bool = false,
        playing: Format,
        servedBy: String? = nil,
        diagnostic: @escaping @Sendable (String) -> Void = { _ in }
    ) async -> Stream? {
        guard durationSec != nil, !isVideo else {




            return await substitute(
                title: title, artist: artist, durationSec: durationSec,
                album: album, isExplicit: isExplicit, isVideo: isVideo
            )
        }
        return await call(diagnostic: diagnostic) { callback in
            SourceResolverBridge.shared.upgradeFor(
                title: title,
                artist: artist,
                durationSec: durationSec.map { KotlinInt(value: Int32($0)) },
                album: album,
                isExplicit: isExplicit,
                isVideo: isVideo,
                playingJson: formatJSON(playing),
                servedBy: servedBy,
                callback: callback
            )
        }
    }






    static func prefetch(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = nil,
        isExplicit: KotlinBoolean? = nil,
        isVideo: Bool = false,
        diagnostic: @escaping @Sendable (String) -> Void = { _ in }
    ) async -> Stream? {
        guard !isVideo else { return nil }
        return await call(diagnostic: diagnostic) { callback in
            SourceResolverBridge.shared.prefetchSubstitute(
                title: title,
                artist: artist,
                durationSec: durationSec.map { KotlinInt(value: Int32($0)) },
                album: album,
                isExplicit: isExplicit,
                isVideo: isVideo,
                callback: callback
            )
        }
    }


    static func forDownload(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = nil,
        isExplicit: KotlinBoolean? = nil,
        quality: String
    ) async -> Stream? {
        return await call(cancellable: true) { callback in
            SourceResolverBridge.shared.forDownload(
                title: title,
                artist: artist,
                durationSec: durationSec.map { KotlinInt(value: Int32($0)) },
                album: album,
                isExplicit: isExplicit,
                isVideo: false,
                quality: quality,
                callback: callback
            )
        }
    }




    struct Format: Sendable {
        var codec: String?
        var kbps: Int?
        var lossless: Bool
        var isDolbyAtmos: Bool = false
    }







    private static func formatJSON(_ format: Format) -> String {
        var parts: [String] = []
        if let codec = format.codec { parts.append("\"codec\":\(quote(codec))") }
        if let kbps = format.kbps { parts.append("\"kbps\":\(kbps)") }



        parts.append("\"isLossless\":\(format.lossless ? "true" : "null")")
        if format.isDolbyAtmos { parts.append("\"isDolbyAtmos\":true") }
        return "{" + parts.joined(separator: ",") + "}"
    }

    private static func quote(_ value: String) -> String {
        let escaped = value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(escaped)\""
    }

    private static func call(
        diagnostic: @escaping @Sendable (String) -> Void = { _ in },
        cancellable: Bool = false,
        _ request: (SourceResolverBridgeStreamCallback) -> Void
    ) async -> Stream? {
        let box = ResumeOnce()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
            box.arm(continuation)
            if cancellable && Task.isCancelled { box.resume(nil); return }
            request(ResolveCallback { json, message in
                if let message { diagnostic(message) }




                box.resume(json.flatMap(decode))
            })
            }
        } onCancel: {
            if cancellable { box.resume(nil) }
        }
    }

    static func decode(_ json: String) -> Stream? {
        guard let data = json.data(using: .utf8),
              let document = try? JSONDecoder().decode(StreamDocument.self, from: data)
        else { return nil }
        return Stream(
            url: document.url,
            headers: document.headers,
            codec: document.format.codec,
            kbps: document.format.kbps.map { $0 },
            lossless: document.format.isLossless == true,
            isDolbyAtmos: document.format.isDolbyAtmos,
            belowRequest: document.belowRequest,
            durationSec: document.durationSec,
            sourceConfigId: document.sourceConfigId,
            summary: document.format.summary
        )
    }
}





private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<SourceSubstitution.Stream?, Never>?

    func arm(_ continuation: CheckedContinuation<SourceSubstitution.Stream?, Never>) {
        lock.lock()
        self.continuation = continuation
        lock.unlock()
    }

    func resume(_ stream: SourceSubstitution.Stream?) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: stream)
    }
}

private final class ResolveCallback: SourceResolverBridgeStreamCallback {
    private let handler: (String?, String?) -> Void
    init(_ handler: @escaping (String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, message: String?) { handler(json, message) }
}

private struct StreamDocument: Decodable {
    struct Format: Decodable {
        var codec: String?
        var kbps: Int?
        var isLossless: Bool?
        var isDolbyAtmos: Bool
        var summary: String
        private enum CodingKeys: String, CodingKey { case codec, kbps, isLossless, isDolbyAtmos, summary }
        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            codec = try values.decodeIfPresent(String.self, forKey: .codec)
            kbps = try values.decodeIfPresent(Int.self, forKey: .kbps)
            isLossless = try values.decodeIfPresent(Bool.self, forKey: .isLossless)
            isDolbyAtmos = try values.decodeIfPresent(Bool.self, forKey: .isDolbyAtmos) ?? false
            summary = try values.decodeIfPresent(String.self, forKey: .summary) ?? ""
        }
    }
    var url: String
    var format: Format
    var headers: [String: String]
    var belowRequest: Bool
    var durationSec: Int?
    var sourceConfigId: String?
    private enum CodingKeys: String, CodingKey { case url, format, headers, belowRequest, durationSec, sourceConfigId }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        url = try values.decode(String.self, forKey: .url)
        format = try values.decode(Format.self, forKey: .format)

        headers = try values.decodeIfPresent([String: String].self, forKey: .headers) ?? [:]
        belowRequest = try values.decodeIfPresent(Bool.self, forKey: .belowRequest) ?? false
        durationSec = try values.decodeIfPresent(Int.self, forKey: .durationSec)
        sourceConfigId = try values.decodeIfPresent(String.self, forKey: .sourceConfigId)
    }
}
