import SwiftUI
import NuvxShared



struct SongActionButtons: View {
    let entry: QueueEntry
    var playlistBrowseId: String? = nil
    var setVideoId: String? = nil
    var playlistOwned: Bool = false
    var showSleepTimer: Bool = true
    var showDebugLog: Bool = false
    @Environment(PlaybackController.self) private var controller
    @Environment(AuthController.self) private var auth
    @Environment(AppModel.self) private var appModel
    @Environment(ToastCenter.self) private var toast

    var body: some View {
        let _ = LikeStore.shared.epoch
        Button("Play") { controller.play([entry], at: 0) }
        Button("Play Next") {
            controller.playNext(entry)
            toast.queueNotice("Playing next: \(entry.title)")
        }
        Button("Add to Queue") {
            controller.addToQueue(entry)
            toast.queueNotice("Added to queue: \(entry.title)")
        }
        Button("Start Radio") { controller.playRadio(entry) }
        if auth.signedIn, let vid = entry.videoId {
            Divider()
            Button(SongRatingActions.likeTitle(vid)) {
                Task { await SongRatingActions.toggleLike(videoId: vid, controller: controller, toast: toast) }
            }
            Button(SongRatingActions.dislikeTitle(vid)) {
                Task { await SongRatingActions.toggleDislike(videoId: vid, controller: controller, toast: toast) }
            }
            Button("Add to Playlist…") {
                appModel.playlistPicker = PlaylistPickerRequest(videoId: vid, title: entry.title)
            }
            if playlistOwned, let setVideoId, let playlistBrowseId {
                Button("Remove from Playlist", role: .destructive) {
                    let title = entry.title
                    toast.requestConfirmation(ConfirmationRequest(
                        title: "Remove from playlist?",
                        message: "“\(title)” will be removed from this playlist. This cannot be undone.",
                        confirm: "Remove from Playlist"
                    ) {
                        Task {



                            if let failure = await LibraryActions.removeFromPlaylist(
                                playlistId: playlistBrowseId, setVideoId: setVideoId, videoId: vid
                            ) {
                                toast.show("Couldn't remove it — \(failure)", kind: .failure)
                            } else {
                                toast.show("Removed from playlist")
                            }
                        }
                    })
                }
            }
        }







        if entry.id == controller.current?.id {
            if controller.songVersions[entry.id] != nil {
                Divider()
                Button("Convert to Audio", systemImage: "music.note") {
                    controller.playSongVersion()
                }
            }
            if controller.canRevertToOriginal {
                Divider()
                Button("Revert to Original") { controller.revertToOriginal() }
            }
            if controller.canUpgradeQuality {
                Divider()
                Button("Upgrade Quality") { controller.upgradeQuality() }
            }
        }
        if !entry.isLocal {
            downloadRow(entry)
        }
        if WebDavStore.shared.canUpload(path: entry.source, id: entry.id) {
            Button("Upload to WebDAV", systemImage: "arrow.up.doc") {
                toast.show("Uploading \(entry.title)…", kind: .info)
                Task {
                    do {
                        let fileName = try await WebDavStore.shared.upload(
                            path: entry.source, title: entry.title, artist: entry.artist
                        )
                        toast.show("Uploaded \(fileName)", kind: .success)
                    } catch {
                        toast.show(error.localizedDescription, kind: .failure)
                    }
                }
            }
        }
        if let albumId = entry.albumId {
            Button("Open Album") {
                appModel.pendingDetail = .detail(browseId: albumId, title: entry.albumName ?? "Album")
            }
        }
        if let artistId = entry.artistId {
            Button("Open Artist") {
                appModel.pendingDetail = .detail(browseId: artistId, title: entry.artist)
            }
        }
        ShareLink(item: shareURL) {
            Label("Share", systemImage: "square.and.arrow.up")
        }
        if showSleepTimer {
            SleepTimerMenu()
        }
        if showDebugLog {
            Button("Track Log…") {
                toast.showTrackLog(entry)
            }
        }
    }

    private var shareURL: URL {
        if let vid = entry.videoId {
            return URL(string: "https://music.youtube.com/watch?v=\(vid)")!
        }
        return URL(string: "https://music.youtube.com")!
    }






    @ViewBuilder
    private func downloadRow(_ entry: QueueEntry) -> some View {
        let store = DownloadStore.shared
        let asset = store.asset(for: entry)
        let activeJob = store.jobs.first { job in
            job.entry.source == entry.source && job.entry.id == entry.id
                && (job.status == .queued || job.status == .running)
        }
        let failedJob = store.jobs.first { job in
            job.entry.source == entry.source && job.entry.id == entry.id && job.status == .failed
        }
        if let activeJob {
            Button("Cancel Download") {
                store.cancel(activeJob.id)
                toast.show("Cancelled \(entry.title)")
            }
        } else if let failedJob {
            Button("Retry Download") {


                switch store.download(entry) {
                case .started:
                    toast.show("Retrying \(entry.title)")
                case .blockedByWifiOnly:
                    toast.show("Downloads are limited to Wi-Fi. Turn that off in Settings to use mobile data.", kind: .failure)
                case .alreadyExists:
                    toast.show("\(entry.title) is already downloading or downloaded", kind: .info)
                case .ignoredLocalTrack:
                    break
                }
            }
        } else if let asset {
            Button("Delete Download", role: .destructive) {
                let title = entry.title
                toast.requestConfirmation(ConfirmationRequest(
                    title: "Delete the download?",
                    message: "“\(title)” will be removed from this device. It will download again when played.",
                    confirm: "Delete Download"
                ) {
                    DownloadStore.shared.delete(asset.path)
                    toast.show("Deleted \(title)")
                })
            }
        } else {
            Button("Download") {
                SongRatingActions.startDownload(entry, toast: toast)
            }
        }
    }
}








@MainActor
enum SongRatingActions {

    static func likeTitle(_ videoId: String) -> String {
        LibraryActions.cachedLike(videoId) == "LIKE" ? "Remove Like" : "Like"
    }

    static func dislikeTitle(_ videoId: String) -> String {
        LibraryActions.cachedLike(videoId) == "DISLIKE" ? "Undo Dislike" : "Dislike"
    }

    static func toggleLike(videoId: String, controller: PlaybackController, toast: ToastCenter) async {



        if let failure = await controller.toggleLikeAndWait(videoId: videoId) {
            toast.show(failure, kind: .failure)
        }
    }








    static func toggleDislike(videoId: String, controller: PlaybackController, toast: ToastCenter) async {
        let wasDisliked = LibraryActions.cachedLike(videoId) == "DISLIKE"
        let previous = wasDisliked
            ? LikeStatus.dislike
            : (LibraryActions.cachedLike(videoId) == "LIKE" ? LikeStatus.like : .indifferent)
        let next = wasDisliked ? "INDIFFERENT" : "DISLIKE"
        if let failure = await controller.rateTrack(videoId: videoId, status: next) {
            toast.show(failure, kind: .failure)
            return
        }
        let currentId = controller.current?.videoId
        if DislikeSkipKt.shouldSkipAfterDislike(
            previousStatus: previous,
            targetVideoId: videoId,
            currentVideoId: currentId
        ) {
            controller.next()
        }
    }


    static func startDownload(_ entry: QueueEntry, toast: ToastCenter) {
        switch DownloadStore.shared.download(entry) {
        case .started:
            toast.show("Downloading \(entry.title)")
        case .blockedByWifiOnly:
            toast.show("Downloads are limited to Wi-Fi. Turn that off in Settings to use mobile data.", kind: .failure)
        case .alreadyExists:
            toast.show("\(entry.title) is already downloading or downloaded", kind: .info)
        case .ignoredLocalTrack:
            break
        }
    }
}







struct ContextMenuPreview: View {
    var artworkUrl: String?
    var artworkData: Data?
    var title: String
    var subtitle: String?

    var roundArtwork: Bool = false


    private let side: CGFloat = 248

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ArtworkView(url: artworkUrl, data: artworkData, side: side)
                .clipShape(roundArtwork
                    ? AnyShape(.circle)
                    : AnyShape(.rect(cornerRadius: 12, style: .continuous)))
                .shadow(color: .black.opacity(0.28), radius: 14, y: 6)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                    .lineLimit(2)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(16)
        .frame(width: 280, alignment: .leading)
    }

    init(artworkUrl: String?, artworkData: Data? = nil, title: String, subtitle: String?, roundArtwork: Bool = false) {
        self.artworkUrl = artworkUrl
        self.artworkData = artworkData
        self.title = title
        self.subtitle = subtitle
        self.roundArtwork = roundArtwork
    }

    init(entry: QueueEntry) {
        self.init(
            artworkUrl: entry.thumbnailUrl, artworkData: entry.artworkData,
            title: entry.title, subtitle: entry.artist
        )
    }

    init(card: ShelfCard, roundArtwork: Bool = false) {
        self.init(
            artworkUrl: card.thumbnailUrl, title: card.title,
            subtitle: card.subtitle, roundArtwork: roundArtwork
        )
    }
}

extension View {




    @ViewBuilder
    func liftPreview<Menu: View, Preview: View>(
        @ViewBuilder menu: () -> Menu,
        @ViewBuilder preview: () -> Preview
    ) -> some View {
        #if os(iOS)
        contextMenu(menuItems: menu, preview: preview)
        #else
        contextMenu(menuItems: menu)
        #endif
    }


    func songContextMenu(
        _ entry: QueueEntry,
        playlistBrowseId: String? = nil,
        playlistOwned: Bool = false
    ) -> some View {
        liftPreview {
            SongActionButtons(
                entry: entry, playlistBrowseId: playlistBrowseId,
                setVideoId: entry.setVideoId, playlistOwned: playlistOwned
            )
        } preview: {
            ContextMenuPreview(entry: entry)
        }
    }



    func shelfCardContextMenu(_ card: ShelfCard, roundArtwork: Bool = false) -> some View {
        liftPreview {
            ShelfCardActionButtons(card: card)
        } preview: {
            ContextMenuPreview(card: card, roundArtwork: roundArtwork)
        }
    }
}




struct ShelfCardActionButtons: View {
    let card: ShelfCard

    var body: some View {
        if let videoId = card.videoId, !videoId.isEmpty {
            SongActionButtons(entry: QueueEntry.youtube(
                videoId: videoId, title: card.title, artist: card.subtitle ?? "",
                thumbnailUrl: card.thumbnailUrl
            ))
        } else {
            BrowseActionButtons(card: card)
        }
    }
}

struct BrowseActionButtons: View {
    let card: ShelfCard
    @Environment(PlaybackController.self) private var controller
    @Environment(AuthController.self) private var auth
    @Environment(AppModel.self) private var appModel
    @Environment(ToastCenter.self) private var toast
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some View {
        if let videoId = card.videoId, !videoId.isEmpty {
            let entry = QueueEntry.youtube(
                videoId: videoId, title: card.title, artist: card.subtitle ?? "",
                thumbnailUrl: card.thumbnailUrl
            )
            Button("Play") { controller.playRadio(entry) }
            Button("Play Next") { controller.playNext(entry) }
            Button("Add to Queue") { controller.addToQueue(entry) }
        }
        if let browseId = card.browseId, !browseId.isEmpty {
            #if os(macOS)
            Button("Open in New Window") {
                openWindow(
                    id: MacBrowseWindow.sceneID,
                    value: MacBrowseWindowRoute.detail(browseId: browseId, title: card.title)
                )
            }
            #endif
            if browseId.hasPrefix("UC") {





                Button("Open") {
                    appModel.pendingDetail = .detail(browseId: browseId, title: card.title)
                }
            } else {
                browseRows(browseId)
            }
        }
    }

    @ViewBuilder
    private func browseRows(_ browseId: String) -> some View {
        Button("Open") {
            appModel.pendingDetail = .detail(browseId: browseId, title: card.title)
        }
        Button("Play") {
            Task { await playCollection(browseId, shuffle: false) }
        }
        Button("Shuffle") {
            Task { await playCollection(browseId, shuffle: true) }
        }



        if isPlaylist(browseId) {
            let pinned = PlatformSettings.shared.getString(key: "pinned_playlists", default: "")
                .split(separator: ",").map(String.init)
            Button(pinned.contains(browseId) ? "Unpin" : "Pin") {
                if !PlaylistPinning.toggle(browseId: browseId) {
                    appModel.pinLimitAlert = true
                }
            }
        }
        if auth.signedIn {
            if browseId.hasPrefix("VL") || browseId.hasPrefix("PL") {
                Button("Rename Playlist…") {
                    appModel.playlistRename = PlaylistRenameRequest(browseId: browseId, title: card.title)
                }
            }
            if browseId.hasPrefix("VL") || browseId.hasPrefix("MPRE") || browseId.hasPrefix("OLAK") {
                Button("Save to Library") {
                    Task {
                        let pid = browseId.hasPrefix("VL") ? String(browseId.dropFirst(2)) : browseId
                        _ = await LibraryActions.ratePlaylist(playlistId: pid, saved: true)
                    }
                }
            }
        }
        downloadRow(browseId)



        if isPlaylist(browseId) {
            ShareLink(item: URL(string: "https://music.youtube.com/playlist?list=\(browseId.hasPrefix("VL") ? String(browseId.dropFirst(2)) : browseId)")!) {
                Label("Share", systemImage: "square.and.arrow.up")
            }
        } else if browseId.hasPrefix("MPRE") || browseId.hasPrefix("OLAK") {
            ShareLink(item: URL(string: "https://music.youtube.com/browse/\(browseId)")!) {
                Label("Share", systemImage: "square.and.arrow.up")
            }
        }
    }

    private func isPlaylist(_ browseId: String) -> Bool {
        browseId.hasPrefix("VL") || browseId.hasPrefix("PL")
    }


    private func downloadRow(_ browseId: String) -> some View {
        CollectionDownloadActions(browseId: browseId, title: card.title)
    }

    private func playCollection(_ browseId: String, shuffle: Bool) async {
        guard let page = try? await InnertubeDetail.shared.browse(browseId: browseId) else { return }
        let entries = page.songs.map { $0.asEntry(fallbackArt: page.thumbnailUrl) }
        if !entries.isEmpty {
            controller.play(entries, context: page.title, contextID: browseId, shuffleRequested: shuffle || controller.shuffleEnabled, shuffleStart: true)
        }
    }
}







struct CollectionDownloadActions: View {
    let browseId: String
    let title: String
    @Environment(ToastCenter.self) private var toast

    var body: some View {
        let store = DownloadStore.shared
        let owner = store.collections.first { $0.browseId == browseId }
        let downloading = owner.map { downloadOwner in
            store.jobs.contains { downloadOwner.requests.contains($0.id) && ($0.status == .queued || $0.status == .running) }
        } ?? false
        let requests = owner?.requests ?? []
        let downloadedCount = store.savedEntries(for: browseId).count
        let downloaded = !requests.isEmpty && downloadedCount == requests.count
        let stateLabel = downloading ? "Waiting" : downloaded ? "Downloaded" : nil

        Button {
            Task {
                switch await DownloadStore.shared.downloadCollection(browseId: browseId) {
                case .started:
                    toast.show("Downloading \(title)")
                case .blockedByWifiOnly:
                    toast.show("Downloads are limited to Wi-Fi. Turn that off in Settings to use mobile data.", kind: .failure)
                case .alreadyExists:
                    toast.show("\(title) is already downloading or downloaded", kind: .info)
                case .ignoredLocalTrack:
                    break
                }
            }
        }
        label: {
            HStack {
                Label("Download All", systemImage: "arrow.down.to.line")
                Spacer()
                if let stateLabel {
                    Text(stateLabel).foregroundStyle(.secondary)
                }
            }
        }

        if let owner, !downloading, owner.requests.allSatisfy({ request in
            store.jobs.first { $0.id == request }?.status != .failed
        }) {
            Button("Delete Download", role: .destructive) {
                toast.requestConfirmation(ConfirmationRequest(
                    title: "Delete the download?",
                    message: "The downloaded songs from “\(title)” will be removed from this device and will stream again when played.",
                    confirm: "Delete Download"
                ) {
                    DownloadStore.shared.removeOwner(owner.id)
                    toast.show("Deleted \(title)")
                })
            }
        }
    }
}

struct PlaylistPickerRequest: Identifiable {
    let id = UUID()
    let videoId: String
    let title: String
}

struct PlaylistPickerView: View {
    let request: PlaylistPickerRequest
    @Environment(\.dismiss) private var dismiss
    @State private var playlists: [UserPlaylistDTO] = []
    @State private var title = ""
    @State private var privacy = "PRIVATE"
    @State private var error: String?

    @State private var selected: Set<String> = []
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                Section("New playlist") {
                    TextField("Title", text: $title)
                    Picker("Privacy", selection: $privacy) {
                        Text("Private").tag("PRIVATE")
                        Text("Unlisted").tag("UNLISTED")
                        Text("Public").tag("PUBLIC")
                    }
                    Button("Create") {
                        Task {
                            _ = await LibraryActions.createPlaylist(
                                title: title,
                                privacy: privacy,
                                videoId: request.videoId.isEmpty ? nil : request.videoId
                            )
                            dismiss()
                        }
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Section("Your playlists") {
                    ForEach(playlists) { list in
                        Button {
                            if selected.contains(list.playlistId) {
                                selected.remove(list.playlistId)
                            } else {
                                selected.insert(list.playlistId)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(list.title)
                                    if !list.subtitle.isEmpty {
                                        Text(list.subtitle).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Image(systemName: selected.contains(list.playlistId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected.contains(list.playlistId) ? Color.accentColor : .secondary)
                                    .font(.title3)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected.contains(list.playlistId) ? .isSelected : [])
                    }
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Add to Playlist")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selected.count > 1 ? "Add to \(selected.count)" : "Add") { addToSelected() }
                        .disabled(selected.isEmpty || adding)
                }
            }
            .task { playlists = await LibraryActions.userPlaylists() }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }



    private func addToSelected() {
        adding = true
        let ids = selected
        let videoId = request.videoId
        Task {
            let failures = await withTaskGroup(of: (String, String?).self) { group in
                for id in ids {
                    group.addTask { (id, await LibraryActions.addToPlaylist(playlistId: id, videoId: videoId)) }
                }
                var failed: [String] = []
                for await (id, err) in group where err != nil { failed.append(id) }
                return failed
            }
            adding = false
            if failures.isEmpty {
                dismiss()
            } else {
                let names = playlists.filter { failures.contains($0.playlistId) }.map(\.title)
                error = "Couldn't add to " + names.joined(separator: ", ")
                selected = Set(failures)
            }
        }
    }
}

struct SleepTimerMenu: View {
    @Environment(PlaybackController.self) private var controller

    var body: some View {
        Menu {
            Button("Sleep 15 min") { controller.startSleep(minutes: 15) }
            Button("Sleep 30 min") { controller.startSleep(minutes: 30) }
            Button("Sleep 45 min") { controller.startSleep(minutes: 45) }
            Button("Sleep 60 min") { controller.startSleep(minutes: 60) }
            Button("Stop after this track") { controller.startSleepAfterTrack() }
            if controller.sleepTimerStatus != nil {
                Button("Cancel timer", role: .destructive) { controller.cancelSleep() }
            }
        } label: {
            if let status = controller.sleepTimerStatus {
                Label("Sleep timer · \(status)", systemImage: "moon.zzz")
            } else {
                Label("Sleep timer", systemImage: "moon.zzz")
            }
        }
    }
}

struct PlaylistRenameRequest: Identifiable {
    let id = UUID()
    let browseId: String
    let title: String
}

struct RenamePlaylistAlert: View {
    @Environment(AppModel.self) private var appModel
    @State private var title = ""

    var body: some View {
        TextField("Title", text: $title)
            .onAppear { title = appModel.playlistRename?.title ?? "" }
        Button("Save") {
            if let req = appModel.playlistRename {
                let name = title
                Task { _ = await LibraryActions.renamePlaylist(playlistId: req.browseId, title: name) }
            }
            appModel.playlistRename = nil
        }
        Button("Cancel", role: .cancel) { appModel.playlistRename = nil }
    }
}









struct ConfirmationDialog<ConfirmLabel: View>: View {
    let title: String
    let message: String
    let destructive: Bool
    @ViewBuilder let confirmLabel: () -> ConfirmLabel
    let onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss


    init(
        title: String,
        message: String,
        confirm: String,
        destructive: Bool = true,
        onConfirm: @escaping () -> Void
    ) where ConfirmLabel == Text {
        self.title = title
        self.message = message
        self.destructive = destructive
        self.confirmLabel = { Text(confirm) }
        self.onConfirm = onConfirm
    }

    init(
        title: String,
        message: String,
        destructive: Bool = true,
        onConfirm: @escaping () -> Void,
        @ViewBuilder confirmLabel: @escaping () -> ConfirmLabel
    ) {
        self.title = title
        self.message = message
        self.destructive = destructive
        self.confirmLabel = confirmLabel
        self.onConfirm = onConfirm
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text(title)
                    .font(.headline)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Button(action: { onConfirm(); dismiss() }) {
                    confirmLabel()
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(destructive ? .red : .accentColor)
                Button("Cancel", role: .cancel) { dismiss() }
                    .frame(maxWidth: .infinity)
            }
            .padding(20)
            .navigationTitle("Confirm")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 340, minHeight: 220)
        #endif
    }
}







struct TrackLogSheet: View {
    let entry: QueueEntry
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast

    var body: some View {
        NavigationStack {
            Group {
                if lines.isEmpty {
                    ContentUnavailableView(
                        "No log lines for this track",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text("The engine has not logged anything about “\(entry.title)” yet.")
                    )
                } else {
                    List(lines, id: \.self) { line in
                        Text(line)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                    }
                    #if os(iOS)
                    .listStyle(.plain)
                    #endif
                }
            }
            .navigationTitle("Track Log")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                if !lines.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        Button("Copy") {
                            PlayerParity.copyToPasteboard(lines.joined(separator: "\n"))
                            toast.show("Track log copied")
                        }
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 420)
        #endif
    }







    private var lines: [String] {
        let dump = PlaybackDebugLog.shared.dump()
        guard !dump.isEmpty else { return [] }
        let needles = [entry.videoId, entry.id].compactMap { $0 }.filter { !$0.isEmpty }
        return dump.split(separator: "\n").map(String.init).filter { line in
            needles.contains { line.contains($0) }
        }
    }
}









struct SpotifyCanvasSettingsView: View {
    @State private var cookie = PlatformSettings.shared.getString(key: "spotify_spdc_token", default: "")
    @State private var autoHide = PlatformSettings.shared.getBoolean(key: "canvas_autohide", default: false)
    @State private var prioritizeSpotify = PlatformSettings.shared.getBoolean(key: "prioritize_spotify_canvas", default: false)





    static func shouldShowCanvas(hasCanvasURL: Bool) -> Bool {
        guard PlatformSettings.shared.getBoolean(key: "animated_canvas", default: true) else { return false }
        if PlatformSettings.shared.getBoolean(key: "canvas_autohide", default: false), !hasCanvasURL {
            return false
        }
        return hasCanvasURL
    }

    var body: some View {
        Form {
            Section {
                SecureField("sp_dc cookie", text: $cookie)
                    #if os(iOS)
                    .textContentType(.password)
                    #endif
            } header: {
                Text("Spotify Cookie")
            } footer: {
                Text("Paste the sp_dc cookie from an open Spotify web session. nuvx uses it only to look up looping motion art. Leave blank to skip.")
            }
            Section {
                Toggle("Auto-hide motion art", isOn: $autoHide)
                Toggle("Prefer Spotify catalogue", isOn: $prioritizeSpotify)
            } footer: {
                Text("Auto-hide keeps the still sleeve when no motion clip was found. Prefer Spotify asks Spotify's catalogue first when looking one up.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Spotify Canvas")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onChange(of: cookie) { _, value in AppSettings.shared.setSpotifySpdc(value: value) }
        .onChange(of: autoHide) { _, value in PlatformSettings.shared.putBoolean(key: "canvas_autohide", value: value) }
        .onChange(of: prioritizeSpotify) { _, value in PlatformSettings.shared.putBoolean(key: "prioritize_spotify_canvas", value: value) }
    }
}
