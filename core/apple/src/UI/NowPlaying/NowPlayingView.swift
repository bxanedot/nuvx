import SwiftUI
import NuvxShared
import UniformTypeIdentifiers
import AVKit
import CoreImage
import CoreImage.CIFilterBuiltins
#if os(iOS)
import UIKit
import AVFAudio
#else
import AppKit
#endif







struct NowPlayingView: View {
    @Environment(PlaybackController.self) var controller
    @Environment(AppModel.self) var appModel
    @Environment(PartyStore.self) var party
    @Environment(\.dismiss) var dismiss
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @Environment(AuthController.self) var auth
    @Environment(ToastCenter.self) private var toast



    @State var pane: PlayerPane = PlayerPane.restored
    #if os(macOS)


    @State var trailingInset: CGFloat = 0
    #endif
    @State var lyricsControlsOpen = true
    @State var lyricsControlActivity = 0
    @State var lyricsScrubbing = false
    @State var volumeDragging = false
    @State var showOutputDevice = false


    @AppStorage("show_music_videos") var showMusicVideos = false


    @AppStorage("player_lyric_line") var showsLyricLine = true
    @AppStorage("hide_status_bar_in_player") private var hideStatusBarInPlayer = false
    @State var showPipeline = false
    @State var showLyricsOffset = false
    @State var showLyricsSources = false



    @State var sharePicking = false

    @State var titleCrawls = false


    @State var lyricsOffsetMs: Int32 = LyricsOffsetBridge.offsetMs()

    var body: some View {



        Group {
            #if os(macOS)
            macOSBody
            #else
            iOSBody
            #endif
        }
        #if os(iOS)
        .statusBarHidden(hideStatusBarInPlayer)
        #endif
        .sheet(isPresented: $showPipeline) {
            AudioPipelineSheet()
        }
        .sheet(isPresented: $showOutputDevice) {
            OutputDeviceSheet()
                .environment(controller)
        }
        .sheet(isPresented: $showLyricsOffset) {
            LyricsOffsetSheet()
                .environment(controller)
        }
        .sheet(isPresented: $showLyricsSources) {



            LyricsProviderSheet()
        }



        .onReceive(NotificationCenter.default.publisher(for: .lyricsOffsetChanged)) { _ in
            lyricsOffsetMs = LyricsOffsetBridge.offsetMs()
        }
        .onChange(of: appModel.queueRevealRequested) { _, requested in
            if requested {
                withAnimation(.easeInOut(duration: 0.2)) { pane = .queue }
                appModel.queueRevealRequested = false
            }
        }
        .onChange(of: appModel.lyricsRevealRequested) { _, requested in
            if requested {
                withAnimation(.easeInOut(duration: 0.2)) { pane = .lyrics }
                appModel.lyricsRevealRequested = false
            }
        }
        .onChange(of: pane) { _, value in
            PlatformSettings.shared.putString(key: "last_player_screen", value: value.persistedValue)
            if value == .lyrics {
                lyricsControlsOpen = true
                lyricsControlActivity += 1
            }
        }
        .task(id: lyricsControlActivity) {
            while pane == .lyrics, lyricsControlsOpen {
                do {
                    try await Task.sleep(for: .seconds(5))
                } catch {
                    return
                }
                guard !Task.isCancelled, pane == .lyrics, lyricsControlsOpen else { return }
                if lyricsScrubbing { continue }
                withAnimation(.easeInOut(duration: 0.22)) { lyricsControlsOpen = false }
                return
            }
        }
    }




    func usesBlurredArtworkBackdrop(width: CGFloat, height: CGFloat) -> Bool {
        pane != .main || PlayerLayout.takesLandscapeShape(width: width, height: height) ||
            width >= PlayerLayout.tabletMinWidth || videoShowing
    }




    var videoShowing: Bool {
        showMusicVideos && VideoPlayback.shared.showsVideo
    }



    var isIPad: Bool {
        #if os(iOS)
        sizeClass == .regular
        #else
        false
        #endif
    }

    var keepsLyricsPlaybackDeckVisible: Bool {
        #if os(iOS)
        isIPad
        #else
        true
        #endif
    }

    var showsPlaybackStatusLine: Bool { true }



    #if os(iOS)
    @ToolbarContentBuilder
    private var playerToolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            if #available(iOS 26.0, *) {
                Button(role: .close) { dismiss() }
            } else {
                Button("Close", systemImage: "xmark") { dismiss() }
            }
        }
        ToolbarItem(placement: .primaryAction) {
            AirPlayRouteButton()
                .frame(width: 22, height: 22)
                .help("AirPlay")
        }
    }
    #endif



    var fullBleedOn: Bool {
        PlatformSettings.shared.getBoolean(key: "full_bleed_artwork", default: true)
    }



    private var hideSongStatus: Bool {
        PlatformSettings.shared.getBoolean(key: "hide_song_status", default: false)
    }



    private var legacyMesh: Bool {
        PlatformSettings.shared.getBoolean(key: "legacy_mesh_gradient", default: false)
    }




    var songStatusLine: String? {
        guard !hideSongStatus else { return nil }
        var parts: [String] = []
        if let source = controller.playbackSourceName { parts.append(source) }
        if let nerd = controller.nerd {
            parts.append(QualityUpgrade.Format.isLosslessCodec(nerd.codec) ? "Lossless" : nerd.codec)
        }
        if controller.racingLossless { parts.append("Checking for better audio…") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }



    var playbackOriginCaption: String? {
        guard !hideSongStatus, let current = controller.current else { return nil }
        if controller.resolvedOrigin[current.id] == .substitute,
           let source = controller.playbackSourceName {
            return "Playing from \(source)"
        }
        if let context = controller.playbackContext, !context.isEmpty {
            return "Playing from \(context)"
        }
        if current.isLocal { return "Playing from Local files" }
        if current.source.hasPrefix("yt:") { return "Playing from YouTube" }
        guard !current.source.isEmpty else { return nil }
        return "Playing from \(current.source)"
    }



}








enum PlayerPane {
    case main, lyrics, queue

    static var restored: PlayerPane {
        switch PlatformSettings.shared.getString(key: "last_player_screen", default: "MAIN") {
        case "LYRICS": .lyrics
        case "QUEUE": .queue
        default: .main
        }
    }

    var persistedValue: String {
        switch self {
        case .main: "MAIN"
        case .lyrics: "LYRICS"
        case .queue: "QUEUE"
        }
    }
}

extension View {

    func playerGlyph() -> some View {
        shadow(color: .black.opacity(0.55), radius: 5, y: 1)
    }
}
