#if os(macOS)
import AppKit
#endif
import Observation
import NuvxShared


struct LocalTrack: Identifiable, Hashable {
    let path: String
    var title: String
    var artist: String
    var album: String
    var durationSeconds: Double
    var artwork: Data?
    var trackNumber: UInt32 = 0






    let dateAdded: Date?
    let dateModified: Date?
    var artworkURL: String? = nil

    var id: String { path }
}





@MainActor
@Observable
final class LocalLibrary {
    static let shared = LocalLibrary()

    private(set) var tracks: [LocalTrack] = [] {
        didSet { viewRowsJSON = nil; visibleTracksCache = nil; tracksByPath = nil }
    }
    @ObservationIgnored private var viewRowsJSON: String?
    @ObservationIgnored private var tracksByPath: [String: LocalTrack]?
    @ObservationIgnored private var visibleTracksCache: (sort: LocalMusicSort, query: String, tracks: [LocalTrack])?
    private(set) var scanned = false
    var onChange: (() -> Void)?

    private var scanTask: Task<Void, Never>?
    private var scanID = UUID()
    private var securityURL: URL?




    func restore() {
        if let override = ProcessInfo.processInfo.environment["NUVX_LIBRARY_PATH"],
           !override.isEmpty {
            scan(folder: URL(fileURLWithPath: override))
            return
        }
        let stored = PlatformSettings.shared.getString(key: "local_library_path", default: "")
        guard !stored.isEmpty, let data = Data(base64Encoded: stored) else { return }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data,
                                options: [],
                                relativeTo: nil,
                                bookmarkDataIsStale: &stale) else { return }
        _ = url.startAccessingSecurityScopedResource()
        securityURL = url
        scan(folder: url)
    }


    func chooseFolder() {
#if os(macOS)
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.message = "Choose the folder holding your music library"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        _ = url.startAccessingSecurityScopedResource()
        securityURL = url
        if let bookmark = try? url.bookmarkData() {
            AppSettings.shared.setLocalLibraryPath(value: bookmark.base64EncodedString())
        }
        scan(folder: url)
#else
        pickingPlaceholder()
#endif
    }

    func scanPicked(_ url: URL) {
        _ = url.startAccessingSecurityScopedResource()
        securityURL = url
        if let bookmark = try? url.bookmarkData() {
            AppSettings.shared.setLocalLibraryPath(value: bookmark.base64EncodedString())
        }
        scan(folder: url)
    }

    #if os(iOS)
    private func pickingPlaceholder() {}
    #endif

    private func scan(folder: URL) {
        scanTask?.cancel()
        let id = UUID(); scanID = id
        let work = Task.detached(priority: .utility) { Self.readTracks(folder: folder) }
        scanTask = Task {
            let result = await withTaskCancellationHandler { await work.value } onCancel: { work.cancel() }
            guard scanID == id, !Task.isCancelled else { return }
            tracks = result; scanned = true; onChange?()
        }
    }

    func waitForScan() async { await scanTask?.value }

    nonisolated private static func readTracks(folder: URL) -> [LocalTrack] {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.isRegularFileKey]
        guard let enumerator = fm.enumerator(at: folder,
                                             includingPropertiesForKeys: keys,
                                             options: [.skipsHiddenFiles, .skipsPackageDescendants]) else {
            return []
        }
        let audioPaths = enumerator
            .compactMap { $0 as? URL }
            .filter { url in
                let ext = url.pathExtension.lowercased()
                return ["mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav", "aiff", "aif", "webm"]
                    .contains(ext)
            }
            .map(\.path)

        var found: [LocalTrack] = []
        for path in audioPaths {
            if Task.isCancelled { return [] }
            let fileName = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
            let dates = fileDates(at: path)
            if let meta = readTrackMetadataSummary(path: path) {



                found.append(LocalTrack(
                    path: path,
                    title: meta.title.isEmpty ? fileName : meta.title,
                    artist: meta.artist.isEmpty ? "Unknown artist" : meta.artist,
                    album: meta.album,
                    durationSeconds: meta.durationSeconds,
                    artwork: nil,
                    trackNumber: meta.trackNumber,
                    dateAdded: dates.added,
                    dateModified: dates.modified,
                    artworkURL: LocalArtwork.reference(path: path)
                ))
            } else {
                found.append(LocalTrack(
                    path: path,
                    title: fileName,
                    artist: "Unknown artist",
                    album: "",
                    durationSeconds: 0,
                    artwork: nil,
                    dateAdded: dates.added,
                    dateModified: dates.modified
                ))
            }
        }





        return found.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }






    nonisolated private static func fileDates(at path: String) -> (added: Date?, modified: Date?) {
        let url = URL(fileURLWithPath: path)
        let keys: Set<URLResourceKey> = [.creationDateKey, .contentModificationDateKey]
        guard let values = try? url.resourceValues(forKeys: keys) else { return (nil, nil) }
        return (values.creationDate, values.contentModificationDate)
    }



    var sort: LocalMusicSort = .titleAscending

    var viewType: LocalViewType = .list

    var query: String = ""






    var visibleTracks: [LocalTrack] {

        _ = tracks.count
        if let cached = visibleTracksCache, cached.sort == sort, cached.query == query { return cached.tracks }
        if viewRowsJSON == nil { viewRowsJSON = makeViewRowsJSON() }
        guard let json = viewRowsJSON else { return tracks }
        let document = LocalLibraryBridge.shared.view(tracksJson: json, order: sort.rawValue, query: query)
        guard let answer = document.data(using: .utf8),
              let ordered = try? JSONSerialization.jsonObject(with: answer) as? [[String: Any]]
        else { return tracks }
        if tracksByPath == nil {
            tracksByPath = Dictionary(tracks.map { ($0.path, $0) }, uniquingKeysWith: { first, _ in first })
        }
        let result = ordered.compactMap { row -> LocalTrack? in
            guard let path = row["path"] as? String else { return nil }
            return tracksByPath?[path]
        }
        visibleTracksCache = (sort, query, result)
        return result
    }

    private func makeViewRowsJSON() -> String? {
        let rows = tracks.map { track -> [String: Any] in
            var row: [String: Any] = [
                "path": track.path,
                "title": track.title,
                "artist": track.artist,
                "album": track.album,
                "durationSeconds": track.durationSeconds,
            ]



            if let added = track.dateAdded { row["dateAddedSeconds"] = added.timeIntervalSince1970 }
            if let modified = track.dateModified {
                row["dateModifiedSeconds"] = modified.timeIntervalSince1970
            }
            return row
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows),
              let json = String(data: data, encoding: .utf8)
        else { return nil }
        return json
    }


    var sortOptions: [LocalMusicSort] { LocalMusicSort.allCases }

    func setSort(_ next: LocalMusicSort) {
        sort = next
        LocalLibraryBridge.shared.setSort(order: next.rawValue, callback: SortAck { _ in })
    }

    func setViewType(_ next: LocalViewType) {
        viewType = next
        LocalLibraryBridge.shared.setViewType(viewType: next.rawValue, callback: SortAck { _ in })
    }


    func restoreViewPreferences() {
        guard !restoredViewPreferences else { return }
        restoredViewPreferences = true
        LocalLibraryBridge.shared.currentSort(callback: CurrentSort { order, view in
            Task { @MainActor in
                self.sort = LocalMusicSort(rawValue: order) ?? .titleAscending
                self.viewType = LocalViewType(rawValue: view) ?? .list
            }
        })
    }

    private var restoredViewPreferences = false

    var albumGroups: [(name: String, artist: String, tracks: [LocalTrack])] {
        Dictionary(grouping: tracks) { "\($0.artist)|\($0.album.isEmpty ? "—" : $0.album)" }
            .values
            .map { group in
                let ordered = group.sorted { lhs, rhs in
                    localTrackComesBefore(
                        trackNumber: lhs.trackNumber, path: lhs.path,
                        trackNumber: rhs.trackNumber, path: rhs.path
                    )
                }
                return (name: ordered.first?.album ?? "", artist: ordered.first?.artist ?? "", tracks: ordered)
            }
            .sorted { ($0.artist, $0.name) < ($1.artist, $1.name) }
    }

    var artistGroups: [(name: String, tracks: [LocalTrack])] {
        Dictionary(grouping: tracks, by: \.artist)
            .map { (name: $0.key, tracks: $0.value) }
            .sorted { $0.name < $1.name }
    }
}







enum LocalMusicSort: String, CaseIterable, Identifiable {
    case titleAscending = "TITLE_ASC"
    case titleDescending = "TITLE_DESC"
    case dateAdded = "DATE_ADDED"
    case dateModified = "DATE_MODIFIED"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .titleAscending: return "Title"
        case .titleDescending: return "Title (Reverse)"
        case .dateAdded: return "Date Added"
        case .dateModified: return "Date Modified"
        }
    }
}






enum LocalViewType: String, CaseIterable, Identifiable {
    case list = "LIST"
    case grid = "GRID"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .list: return "list.bullet"
        case .grid: return "square.grid.2x2"
        }
    }

    var label: String {
        switch self {
        case .list: return "List"
        case .grid: return "Grid"
        }
    }
}

private final class SortAck: LocalLibraryBridgeSetSortCallback {
    private let handler: (Bool) -> Void
    init(_ handler: @escaping (Bool) -> Void) { self.handler = handler }
    func onResult(ok: Bool) { handler(ok) }
}

private final class CurrentSort: LocalLibraryBridgeSortCallback {
    private let handler: (String, String) -> Void
    init(_ handler: @escaping (String, String) -> Void) { self.handler = handler }
    func onResult(order: String, viewType: String) { handler(order, viewType) }
}
