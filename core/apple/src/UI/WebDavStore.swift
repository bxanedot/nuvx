import SwiftUI
import Observation
import NuvxShared
import Foundation





















@MainActor
@Observable
final class WebDavStore {

    static let shared = WebDavStore()






    enum State: Equatable {
        case notConfigured
        case loading
        case empty
        case loaded
        case failed(String)
    }

    private(set) var state: State = .notConfigured
    private(set) var albums: [RemoteAlbum] = []
    private(set) var trackCount = 0
    private(set) var uploadingEntryIDs: Set<String> = []






    var url: String { WebDavBridge.shared.url() }
    var username: String { WebDavBridge.shared.username() }
    var hasPassword: Bool { WebDavBridge.shared.hasPassword() }
    var isConfigured: Bool { WebDavBridge.shared.isConfigured() }



    var subtitle: String {
        guard !url.isEmpty else { return "Not set up" }
        return username.isEmpty ? url : "\(url) · \(username)"
    }








    func load(force: Bool = false) async {
        guard isConfigured else {
            state = .notConfigured
            albums = []
            trackCount = 0
            return
        }
        if !force, state == .loading { return }
        if !force, case .loaded = state { return }
        state = .loading
        do {
            let songs = try await fetchLibrary()
            guard !Task.isCancelled else { return }
            albums = Self.group(songs)
            trackCount = songs.count
            state = songs.isEmpty ? .empty : .loaded
        } catch {
            guard !Task.isCancelled else { return }




            albums = []
            trackCount = 0
            state = .failed(Self.describe(error))
        }
    }









    func save(url: String, username: String, password: String?) {
        WebDavBridge.shared.save(url: url, username: username, password: password)
    }


    func forget() {
        WebDavBridge.shared.forget()
        state = .notConfigured
        albums = []
        trackCount = 0
    }






    func test(url: String, username: String, password: String?) async -> String? {
        do {
            _ = try await testShare(url: url, username: username, password: password)
            return nil
        } catch {
            return Self.describe(error)
        }
    }




    func upload(
        path: String,
        title: String,
        artist: String,
        refreshAfterUpload: Bool = true
    ) async throws -> String {
        guard isConfigured, !path.hasPrefix("http://"), !path.hasPrefix("https://") else {
            throw WebDavUploadError.notAvailable
        }
        let fileURL = URL(fileURLWithPath: path)
        guard FileManager.default.isReadableFile(atPath: fileURL.path) else {
            throw WebDavUploadError.unreadable
        }
        let ext = fileURL.pathExtension.lowercased()
        let bridge = WebDavBridge.shared
        guard bridge.supportsUploadExtension(fileExtension: ext) else { throw WebDavUploadError.notAvailable }
        let baseName = bridge.uploadFileName(title: title, artist: artist, fileExtension: ext)
        let suffix = ".\(ext)"
        let stem = baseName.hasSuffix(suffix) ? String(baseName.dropLast(suffix.count)) : baseName
        uploadingEntryIDs.insert(path)
        defer { uploadingEntryIDs.remove(path) }

        for number in 0..<1_000 {
            let fileName = number == 0 ? baseName : "\(stem) (\(number))\(suffix)"
            guard let target = URL(string: bridge.uploadFileUrl(fileName: fileName)) else {
                throw WebDavUploadError.invalidAddress
            }
            var request = URLRequest(url: target)
            request.httpMethod = "PUT"
            request.setValue("*", forHTTPHeaderField: "If-None-Match")
            request.setValue(Self.mimeType(for: ext), forHTTPHeaderField: "Content-Type")
            if let authorization = bridge.uploadAuthorizationHeader(fileUrl: target.absoluteString) {
                request.setValue(authorization, forHTTPHeaderField: "Authorization")
            }

            let response: URLResponse
            do {
                (_, response) = try await URLSession.shared.upload(for: request, fromFile: fileURL)
            } catch {
                throw WebDavUploadError.unreachable
            }
            guard let http = response as? HTTPURLResponse else { throw WebDavUploadError.unreachable }
            if http.statusCode == 412 { continue }
            guard (200..<300).contains(http.statusCode) else {
                throw WebDavUploadError.refused(http.statusCode)
            }
            if refreshAfterUpload {
                await load(force: true)
            }
            return fileName
        }
        throw WebDavUploadError.tooManyConflicts
    }



    func canUpload(path: String, id: String) -> Bool {
        guard isConfigured, !path.isEmpty, !uploadingEntryIDs.contains(path), !bridgeIsRemote(id) else { return false }
        let ext = URL(fileURLWithPath: path).pathExtension
        return WebDavBridge.shared.supportsUploadExtension(fileExtension: ext)
    }

    private func bridgeIsRemote(_ id: String) -> Bool {
        WebDavBridge.shared.isRemoteId(videoId: id)
    }

    private static func mimeType(for ext: String) -> String {
        switch ext {
        case "mp3": "audio/mpeg"
        case "m4a", "mp4": "audio/mp4"
        case "flac": "audio/flac"
        case "ogg", "opus": "audio/ogg"
        case "aac": "audio/aac"
        case "wav": "audio/wav"
        case "webm": "audio/webm"
        default: "application/octet-stream"
        }
    }




    struct RemoteAlbum: Identifiable, Equatable {

        var name: String?

        var coverUrl: String?
        var songs: [Song]


        var id: String { songs.first?.videoId ?? name ?? "album" }
    }







    static func group(_ songs: [Song]) -> [RemoteAlbum] {
        var order: [String] = []
        var grouped: [String: [Song]] = [:]
        var covers: [String: String] = [:]
        for song in songs {
            let key = song.albumName ?? ""
            if grouped[key] == nil {
                grouped[key] = []
                order.append(key)
            }
            grouped[key]?.append(song)




            if covers[key] == nil, let url = song.thumbnailUrl { covers[key] = url }
        }
        return order
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
            .map { key in
                RemoteAlbum(
                    name: grouped[key]?.first?.albumName,
                    coverUrl: covers[key],
                    songs: (grouped[key] ?? []).sorted {
                        $0.title.localizedStandardCompare($1.title) == .orderedAscending
                    },
                )
            }
    }









    static func describe(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == "KotlinException" {
            let said = ns.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if !said.isEmpty { return said }
        }
        if let url = ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL {
            return "Couldn’t reach \(url.host() ?? "that server")."
        }
        return "Couldn’t reach that server."
    }
}

private enum WebDavUploadError: LocalizedError {
    case notAvailable, unreadable, invalidAddress, unreachable, refused(Int), tooManyConflicts

    var errorDescription: String? {
        switch self {
        case .notAvailable: "This track cannot be uploaded to WebDAV."
        case .unreadable: "Could not read this audio file."
        case .invalidAddress, .unreachable: "Could not reach the WebDAV share."
        case .refused(401): "The server did not accept the WebDAV username and password."
        case .refused(403): "The server refused the upload."
        case .refused(507): "The WebDAV share is out of storage."
        case let .refused(status): "The WebDAV server refused the upload (\(status))."
        case .tooManyConflicts: "Could not find an unused name for this upload."
        }
    }
}









private enum WebDavStoreError: Error {
    case noValue
}

private func fetchLibrary() async throws -> [Song] {
    try await withCheckedThrowingContinuation { continuation in
        WebDavBridge.shared.library { songs, error in
            if let error { continuation.resume(throwing: error) }
            else if let songs { continuation.resume(returning: songs) }
            else { continuation.resume(throwing: WebDavStoreError.noValue) }
        }
    }
}

private func testShare(url: String, username: String, password: String?) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
        WebDavBridge.shared.test(url: url, username: username, password: password) { _, error in
            if let error { continuation.resume(throwing: error) }
            else { continuation.resume(returning: "") }
        }
    }
}
