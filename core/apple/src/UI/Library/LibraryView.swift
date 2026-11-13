import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import NuvxShared
import UniformTypeIdentifiers




struct LibraryView: View {
    @Environment(PlaybackController.self) private var controller
    @Environment(AuthController.self) private var auth
    @Environment(AppModel.self) private var appModel
    @State private var local = LocalLibrary.shared



    var lockedSection: Section? = nil
    var hostsWindowControls: Bool = false

    enum Section: String, CaseIterable, Identifiable {
        case youtube, songs, albums, artists, playlists, subscriptions, podcasts, ondevice, downloads, history, webdav
        var id: String { rawValue }
        var label: String {
            switch self {
            case .youtube: "Recent"
            case .webdav: "WebDAV"
            case .ondevice: "On Device"
            default: rawValue.capitalized
            }
        }
    }

    @State private var pickingFolder = false
    @State private var showSpotifyImport = false

    var body: some View {
        PageStack {
            if let locked = lockedSection {





                lockedContent(locked)
            } else {
                LibraryLandingView(onPickFolder: { pickFolder() })
                .navigationTitle("Library")
                .toolbar {
                    #if os(iOS)
                    TopBarLeadingItems()
                    ToolbarItemGroup(placement: .topBarTrailing) {



                        HStack(spacing: 2) {
                            PushLink {
                                HistoryView()
                            } label: {
                                Image(systemName: "clock")
                                    .scaledFont(size: 15, weight: .medium)
                                    .foregroundStyle(.primary)
                                    .frame(width: 40, height: 34)
                                    .contentShape(.rect)
                            }
                            .buttonStyle(ProfileCircleButtonStyle())
                            .accessibilityLabel("Listening History")

                            Divider()
                                .frame(height: 20)
                                .opacity(0.5)




                            Menu {
                                if auth.signedIn {
                                    Button("New Playlist", systemImage: "music.note.list") {
                                        appModel.playlistPicker = PlaylistPickerRequest(videoId: "", title: "")
                                    }
                                    Button("Import from Spotify", systemImage: "square.and.arrow.down") {
                                        showSpotifyImport = true
                                    }
                                }
                                Button("Scan a Folder", systemImage: "folder") {
                                    pickFolder()
                                }
                            } label: {
                                Image(systemName: "plus")
                                    .scaledFont(size: 15, weight: .medium)
                                    .foregroundStyle(.primary)
                                    .frame(width: 40, height: 34)
                                    .contentShape(.rect)
                            }
                            .buttonStyle(ProfileCircleButtonStyle())
                            .accessibilityLabel("Add to Library")
                        }
                        .padding(.horizontal, 6)
                        .toolbarChrome(in: Capsule())

                        TopBarAccountButton()
                    }
                    #else
                    ToolbarItem {
                        Button {
                            pickFolder()
                        } label: {
                            Image(.nuvxPlus)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 15)
                        }
                        .help("Scan a folder")
                        .accessibilityLabel("Scan a Folder")
                    }
                    #endif
                }
            }
        }
        .onAppear {
            local.restoreViewPreferences()
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                local.scanPicked(url)
            }
        }
        .sheet(isPresented: $showSpotifyImport) {
            SpotifyImportSheet()
        }
    }

    @ViewBuilder
    private func lockedContent(_ locked: Section) -> some View {
        switch locked {
        case .youtube: YoutubeLibraryView()
        case .songs: UnifiedSongsView(title: locked.label, onPickFolder: { pickFolder() }, hostsWindowControls: hostsWindowControls)
        case .albums: UnifiedAlbumsView(title: locked.label, onPickFolder: { pickFolder() }, hostsWindowControls: hostsWindowControls)
        case .artists: UnifiedArtistsView(title: locked.label, onPickFolder: { pickFolder() }, hostsWindowControls: hostsWindowControls)
        case .playlists: SavedPlaylistsView(hostsWindowControls: hostsWindowControls)
        case .subscriptions: SavedShelfView(shelfName: "Subscriptions", title: "Subscriptions", emptySubtitle: "Channels you subscribe to show up here.")
        case .podcasts: PodcastsFeedView()
        case .ondevice: OnDeviceView()
        case .downloads: DownloadsView()
        case .webdav: WebDavLibraryView()
        case .history: HistoryView()
        }
    }

    private func pickFolder() {
        #if os(macOS)
        local.chooseFolder()
        #else
        pickingFolder = true
        #endif
    }
}
