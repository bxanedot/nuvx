import SwiftUI
import NuvxShared






























struct PlaybackCommands: Commands {
    let controller: PlaybackController
    let appModel: AppModel
    let auth: AuthController
    let toast: ToastCenter

    var body: some Commands {




        CommandGroup(replacing: .undoRedo) {}



        CommandGroup(after: .sidebar) {
            Button("Show Player") {
                appModel.nowPlayingPresented = true
            }
            .keyboardShortcut("p", modifiers: [.command, .shift])

            Button("Show Queue") {
                appModel.nowPlayingPresented = true
                appModel.queueRevealRequested = true
            }
            .keyboardShortcut("u", modifiers: .command)

            Button("Show Lyrics") {
                appModel.nowPlayingPresented = true
                appModel.lyricsRevealRequested = true
            }
            .keyboardShortcut("l", modifiers: .command)

            Divider()

            Button("Search") {
                appModel.requestedTab = .search
                appModel.focusSearch = true
            }
            .keyboardShortcut("f", modifiers: .command)
        }

        CommandMenu("Controls") {





            Button(controller.isPlaying ? "Pause" : "Play") {
                controller.togglePlayPause()
            }
            .keyboardShortcut("p", modifiers: .command)
            .disabled(controller.current == nil)

            Button("Next Track") { controller.next() }
                .keyboardShortcut(.rightArrow, modifiers: [.command, .shift])
                .disabled(!controller.canPlayNext)

            Button("Previous Track") { controller.previous() }
                .keyboardShortcut(.leftArrow, modifiers: [.command, .shift])
                .disabled(!controller.canPlayPrevious)

            Divider()

            Button("Seek Forward 10 Seconds") {
                controller.seek(to: min(controller.position + 10, max(controller.duration, 0)))
            }
            .keyboardShortcut(.rightArrow, modifiers: [.command, .option])
            .disabled(controller.duration <= 0)

            Button("Seek Backward 10 Seconds") {
                controller.seek(to: max(controller.position - 10, 0))
            }
            .keyboardShortcut(.leftArrow, modifiers: [.command, .option])
            .disabled(controller.duration <= 0)

            Divider()



            Toggle("Shuffle", isOn: Binding(
                get: { controller.shuffleEnabled },
                set: { _ in controller.toggleShuffle() }
            ))
            .keyboardShortcut("s", modifiers: [.command, .shift])

            Button(repeatTitle) { controller.cycleRepeat() }
                .keyboardShortcut("r", modifiers: [.command, .shift])

            Divider()

            Button("Volume Up") { controller.volume = min(1, controller.volume + 0.05) }
                .keyboardShortcut(.upArrow, modifiers: .command)

            Button("Volume Down") { controller.volume = max(0, controller.volume - 0.05) }
                .keyboardShortcut(.downArrow, modifiers: .command)
        }






        CommandMenu("Track") {
            if let entry = controller.current {



                Button("Play Next") {
                    controller.playNext(entry)
                    toast.queueNotice("Playing next: \(entry.title)")
                }
                Button("Add to Queue") {
                    controller.addToQueue(entry)
                    toast.queueNotice("Added to queue: \(entry.title)")
                }
                Button("Start Radio") { controller.playRadio(entry) }

                Divider()

                if auth.signedIn, let videoId = entry.videoId {
                    Button(SongRatingActions.likeTitle(videoId)) {
                        Task { await SongRatingActions.toggleLike(videoId: videoId, controller: controller, toast: toast) }
                    }
                    Button(SongRatingActions.dislikeTitle(videoId)) {
                        Task { await SongRatingActions.toggleDislike(videoId: videoId, controller: controller, toast: toast) }
                    }
                    Button("Add to Playlist…") {
                        appModel.playlistPicker = PlaylistPickerRequest(videoId: videoId, title: entry.title)
                    }

                    Divider()
                }

                Button(downloadTitle(entry)) {
                    SongRatingActions.startDownload(entry, toast: toast)
                }
                .disabled(!canDownload(entry))

                Button("Show Downloads") { appModel.downloadManagerPresented = true }
            } else {
                Button("Nothing Playing") {}.disabled(true)
            }
        }

        CommandMenu("Go") {
            Button("Home") { appModel.requestedTab = .home }
                .keyboardShortcut("1", modifiers: .command)
            Button("Explore") { appModel.requestedTab = .explore }
                .keyboardShortcut("2", modifiers: .command)
            Button("Library") { appModel.requestedTab = .library }
                .keyboardShortcut("3", modifiers: .command)
            Button("Playlists") { appModel.requestedTab = .libraryPlaylists }
                .keyboardShortcut("4", modifiers: .command)
            Button("Downloads") { appModel.requestedTab = .libraryDownloads }
                .keyboardShortcut("5", modifiers: .command)
            Button("History") { appModel.requestedTab = .libraryHistory }
                .keyboardShortcut("6", modifiers: .command)

            Divider()



            Button("Songs") { appModel.requestedTab = .librarySongs }
            Button("Albums") { appModel.requestedTab = .libraryAlbums }
            Button("Artists") { appModel.requestedTab = .libraryArtists }
            Button("Subscriptions") { appModel.requestedTab = .librarySubscriptions }
            Button("Podcasts") { appModel.requestedTab = .libraryPodcasts }
            Button("On Device") { appModel.requestedTab = .libraryOnDevice }
            Button("WebDAV") { appModel.requestedTab = .libraryWebDav }

            Divider()

            Button("Replay") { appModel.openReplay() }
            Button("Listen Together") { appModel.listenTogetherPresented = true }
        }




        CommandGroup(after: .newItem) {
            Button("Import from Spotify…") {
                appModel.requestedTab = .libraryPlaylists
                appModel.spotifyImportPresented = true
            }
            .keyboardShortcut("i", modifiers: [.command, .shift])

            Button("Import Playlist File…") {
                appModel.requestedTab = .libraryPlaylists
                appModel.playlistFileImportPresented = true
            }
        }
    }






    private var repeatTitle: String {
        switch controller.repeatMode {
        case .off: "Repeat Off"
        case .all: "Repeat All"
        case .one: "Repeat One"
        }
    }

    private func downloadTitle(_ entry: QueueEntry) -> String {
        if DownloadStore.shared.isDownloaded(entry) { return "Downloaded" }
        if DownloadStore.shared.jobs.contains(where: { job in
            job.entry.source == entry.source && job.entry.id == entry.id
                && (job.status == .queued || job.status == .running)
        }) {
            return "Downloading…"
        }
        return "Download"
    }

    private func canDownload(_ entry: QueueEntry) -> Bool {
        downloadTitle(entry) == "Download"
    }
}
