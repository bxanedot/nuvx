import SwiftUI
import NuvxShared











struct WebDavLibraryView: View {
    @Environment(PlaybackController.self) private var controller
    @State private var store = WebDavStore.shared


    @State private var loadedFor: String = ""

    var body: some View {
        content
            .navigationTitle("WebDAV")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                TopBarLeadingItems()
                ToolbarItem(placement: .topBarTrailing) {
                    TopBarAccountButton()
                }
            }
            #endif
            .task(id: store.url) {



                guard loadedFor != store.url else { return }
                loadedFor = store.url
                await store.load(force: true)
            }
            .refreshable { await store.load(force: true) }
    }

    @ViewBuilder
    private var content: some View {
        switch store.state {
        case .notConfigured:
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "No remote library yet",
                subtitle: "Point nuvx at a WebDAV share in Sources and it will appear here.",
                buttonTitle: nil,
                action: nil
            )
        case .loading:
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "Nothing on that share",
                subtitle: "The server answered and there is no audio in it. If you expected a folder here, it may be somewhere else on the share.",
                buttonTitle: nil,
                action: nil
            )
        case .failed(let message):
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "Can’t read that share",
                subtitle: message,
                buttonTitle: "Try Again",
                action: { Task { await store.load(force: true) } }
            )
        case .loaded:
            list
        }
    }

    private var list: some View {
        List {
            Section {
                header
            }
            if !conflicts.isEmpty {
                Section {
                    Button {
                        activeConflict = conflicts.first
                    } label: {
                        HStack {
                            Image(systemName: "exclamationmark.triangle")
                                .foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(conflictTitle)
                                    .foregroundStyle(.primary)
                                Text("Same song in Downloads and on this share. Choose which copy wins.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                } header: {
                    Text("Sync Conflicts")
                }
            }
            ForEach(store.albums) { album in
                Section {
                    ForEach(Array(album.songs.enumerated()), id: \.element.videoId) { _, song in
                        SongRow(
                            entry: entry(for: song),
                            play: { play(song) }
                        )
                        .listRowInsets(EdgeInsets(top: 2, leading: 24, bottom: 2, trailing: 24))
                    }
                } header: {
                    AlbumHeader(album: album)
                }
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #else
        .listStyle(.inset)
        #endif
        .navigationTitle("WebDAV")
        .sheet(item: $activeConflict) { conflict in
            WebDavConflictAlert(
                conflict: conflict,
                rest: conflicts.filter { $0.id != conflict.id },
                onDone: { next in activeConflict = next }
            )
        }
    }
    @State private var activeConflict: WebDavConflict?






    private var conflicts: [WebDavConflict] {
        WebDavConflictDetector.conflicts(
            remote: store.albums.flatMap(\.songs),
            local: DownloadStore.shared.items
        )
    }

    private var conflictTitle: String {
        conflicts.count == 1 ? "1 track in two places" : "\(conflicts.count) tracks in two places"
    }






    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(store.subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(countLabel)
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var countLabel: String {
        let albums = store.albums.count
        let tracks = store.trackCount
        let albumWord = albums == 1 ? "album" : "albums"
        let trackWord = tracks == 1 ? "track" : "tracks"
        return "\(albums) \(albumWord) · \(tracks) \(trackWord)"
    }

    private func entry(for song: Song) -> QueueEntry { QueueEntry.from(song) }






    private func play(_ song: Song) {
        let entries = store.albums.flatMap { $0.songs }.map(entry(for:))
        guard let index = entries.firstIndex(where: { $0.id == song.videoId }) else { return }
        controller.play(entries, at: index)
    }
}


private struct AlbumHeader: View {
    let album: WebDavStore.RemoteAlbum

    var body: some View {
        HStack(spacing: 10) {





            ArtworkView(
                url: album.coverUrl,
                data: nil,
                side: 34,
                remoteId: album.coverUrl == nil ? album.id : nil
            )
            .clipShape(.rect(cornerRadius: 5, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(album.name ?? "Unknown album")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text("\(album.songs.count) tracks")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 3)
        .textCase(nil)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(album.name ?? "Unknown album"), \(album.songs.count) tracks")
    }
}











struct WebDavConflict: Identifiable, Equatable {

    let id: String
    let title: String
    let artist: String
    let remoteAlbum: String?
    let localPath: String
    let localSize: Int64?
    let localModified: Date?
}









enum WebDavConflictDetector {
    static func conflicts(remote: [Song], local: [DownloadedTrack]) -> [WebDavConflict] {
        var out: [WebDavConflict] = []
        for item in local {
            let localTitle = normalize(item.title)
            guard !localTitle.isEmpty else { continue }
            let localArtist = normalize(item.artist)
            guard let match = remote.first(where: { song in
                normalize(song.title) == localTitle && artistsAgree(localArtist, normalize(song.artist))
            }) else { continue }
            let attributes = try? FileManager.default.attributesOfItem(atPath: item.path)
            let size = (attributes?[.size] as? NSNumber)?.int64Value
            let modified = attributes?[.modificationDate] as? Date
            out.append(WebDavConflict(
                id: "\(match.videoId)|\(item.path)",
                title: item.title,
                artist: item.artist,
                remoteAlbum: match.albumName,
                localPath: item.path,
                localSize: size,
                localModified: modified
            ))
        }
        return out.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    static func normalize(_ raw: String) -> String {
        raw.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func artistsAgree(_ local: String, _ remote: String) -> Bool {
        if local.isEmpty || remote.isEmpty { return true }
        if local == remote { return true }
        return local.contains(remote) || remote.contains(local)
    }
}








struct WebDavConflictAlert: View {
    let conflict: WebDavConflict
    let rest: [WebDavConflict]

    let onDone: (WebDavConflict?) -> Void

    @Environment(ToastCenter.self) private var toast
    @State private var applyToAll = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("“\(conflict.title)” is in two places")
                    .font(.headline)
                Text("This download and a track on your WebDAV share name the same song.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Group {
                    HStack {
                        Text("On this device").foregroundStyle(.secondary)
                        Spacer()
                        Text(localDetail).monospacedDigit()
                    }
                    HStack {
                        Text("On the share").foregroundStyle(.secondary)
                        Spacer()
                        Text(remoteDetail)
                    }
                }
                .font(.subheadline)
                if !rest.isEmpty {
                    Toggle("Apply to all \(rest.count + 1) conflicts", isOn: $applyToAll)
                        .font(.subheadline)
                }
                Spacer(minLength: 8)
                Button("Keep Both") {
                    toast.show("Kept both copies of “\(conflict.title)”")
                    advance(deletingLocal: false, applyEverywhere: true)
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                Button("Keep Remote — Delete Local Copy", role: .destructive) {
                    advance(deletingLocal: true, applyEverywhere: applyToAll)
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                Button("Skip", role: .cancel) {
                    advance(deletingLocal: false, applyEverywhere: applyToAll)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(20)
            .navigationTitle("Sync Conflict")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 400)
        #endif
    }

    private var localDetail: String {
        var parts: [String] = []
        if let size = conflict.localSize {
            parts.append(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
        }
        if let modified = conflict.localModified {
            parts.append(modified.formatted(date: .abbreviated, time: .omitted))
        }
        return parts.isEmpty ? "Downloaded file" : parts.joined(separator: " · ")
    }

    private var remoteDetail: String {
        conflict.remoteAlbum ?? "On the share"
    }







    private func advance(deletingLocal: Bool, applyEverywhere: Bool) {
        let targets = applyEverywhere && deletingLocal ? [conflict] + rest : [conflict]
        if deletingLocal {
            var removed = 0
            for target in targets {
                if (try? FileManager.default.removeItem(atPath: target.localPath)) != nil {
                    removed += 1
                }
            }
            DownloadStore.shared.refresh()
            toast.show(removed == 1
                ? "Deleted the local copy of “\(conflict.title)”"
                : "Deleted \(removed) local copies")
        }
        if applyEverywhere {
            onDone(nil)
        } else {
            onDone(rest.first)
        }
    }
}
