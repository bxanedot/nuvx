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


extension NowPlayingView {
    var desktopLyricsPane: some View {
            LyricsPane(
                lines: controller.displayedLyrics,
                originalLines: controller.lyrics,
                loading: controller.lyricsLoading,
                clock: lyricsClock,
                hasTrack: controller.current != nil,
                sourceLabel: controller.lyricsSourceLabel,
                alignmentInProgress: controller.lyricsAligning,
                alignedLocally: controller.lyricsAlignedLocally,
                onChooseSource: { showLyricsSources = true },
                onSeek: { controller.seek(to: $0) },
                translator: controller.lyricsTranslator,
                trackId: controller.current?.id ?? "",
                offsetMs: lyricsOffsetMs,
                sourceVisible: true,
                onChangeSource: { showLyricsSources = true },
                onRevealControls: {},
                onFocusLyrics: {},
                trackTitle: controller.current?.title ?? "",
                trackArtist: controller.current?.artist ?? "",
                artworkData: controller.current?.artworkData,
                artworkUrl: controller.current?.thumbnailUrl
            )
    }

    var lyricsClock: LyricsClock {
        LyricsClock(playing: controller.isPlaying) { [controller] in controller.livePosition(at: $0) }
    }







    var stage: some View {
        GeometryReader { geo in
            let collapsed = pane != .main



            let full = PlayerLayout.portraitArtworkSide(
                stageWidth: geo.size.width,
                stageHeight: geo.size.height,
                maximumSide: isIPad
                    ? min(
                        PlayerLayout.ipadPortraitArtworkMaxSide,
                        geo.size.width * PlayerLayout.ipadPortraitArtworkWidthFraction
                    )
                    : nil
            )


            let hero = !isIPad && !collapsed && PlayerLayout.usesFullBleedArtwork(
                width: geo.size.width, preferenceEnabled: fullBleedOn
            )


            VStack(spacing: 0) {
                if pane == .lyrics {
                    lyricsHeader
                } else if pane == .main {
                    if isIPad {
                        artworkStage(side: full, collapsed: false)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                    } else {
                        artworkStage(
                            side: hero ? geo.size.width : full,
                            collapsed: false,
                            hero: hero
                        )
                    }
                }
                if collapsed {
                    panel()
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .overlay(alignment: .bottomTrailing) {
                if pane == .lyrics {
                    lyricsToolsButton
                        .padding(.trailing, 20)
                        .padding(.bottom, 12)
                }
            }
            .animation(.snappy(duration: 0.28), value: pane)





            .gesture(
                DragGesture(minimumDistance: 30)
                    .onEnded { value in
                        let dy = value.translation.height
                        let dx = value.translation.width

                        guard abs(dy) > abs(dx) else { return }
                        if dy < -60 && pane == .main {
                            Haptics.play(.expand)
                            pane = .queue
                        } else if dy > 60 && pane != .main {
                            Haptics.play(.tap)
                            pane = .main
                        }
                    }
            )
            .accessibilityHint("Swipe up for Up Next, swipe down to return to the player.")
        }
    }













    @ViewBuilder
    func artworkStage(side: CGFloat, collapsed: Bool, hero: Bool = false) -> some View {
        if collapsed {
            Button {
                Haptics.play(.tap)
                pane = .main
            } label: {
                SleeveArt(
                    entry: controller.current,
                    canvasURL: controller.canvasURL,
                    fallbackURL: controller.canvasFallbackURL,
                    isPlaying: controller.isPlaying,
                    side: side
                )
                .id(controller.current?.id)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back to player")
            .padding(.top, 8)
            .padding(.bottom, 4)
        } else if hero {
            HeroArtwork(
                entry: controller.current,
                canvasURL: controller.canvasURL,
                fallbackURL: controller.canvasFallbackURL,
                isPlaying: controller.isPlaying,
                canvasActive: !VideoPlayback.shared.showsVideo
            )
            .id(controller.current?.id)


            .frame(width: side, height: side)
            .overlay(alignment: .top) {
                if let caption = playbackOriginCaption {
                    Text(caption)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.92))
                        .shadow(color: .black.opacity(0.55), radius: 5, y: 1)
                        .padding(.top, 22)
                }
            }
            .accessibilityLabel(controller.current?.title ?? "Nothing playing")
        } else {
            VideoSurface(cornerRadius: 12, announcesLoading: true, presentation: .card) {
                SleeveArt(
                    entry: controller.current,
                    canvasURL: controller.canvasURL,
                    fallbackURL: controller.canvasFallbackURL,
                    isPlaying: controller.isPlaying,
                    canvasActive: !VideoPlayback.shared.showsVideo,
                    side: side
                )
            }
            .id(controller.current?.id)
            .padding(.top, 12)
            .padding(.bottom, 20)
            .accessibilityLabel(controller.current?.title ?? "Nothing playing")
        }
    }



    @ViewBuilder
    func panel(showsQueueCurrentTrackHeader: Bool = true) -> some View {
        switch pane {
        case .main:
            Color.clear
        case .lyrics:





            LyricsPane(
                lines: controller.displayedLyrics,
                originalLines: controller.lyrics,
                loading: controller.lyricsLoading,
                clock: lyricsClock,
                hasTrack: controller.current != nil,
                sourceLabel: controller.lyricsSourceLabel,
                alignmentInProgress: controller.lyricsAligning,
                alignedLocally: controller.lyricsAlignedLocally,
                onChooseSource: { showLyricsSources = true },
                onSeek: { controller.seek(to: $0) },
                translator: controller.lyricsTranslator,
                trackId: controller.current?.id ?? "",
                offsetMs: lyricsOffsetMs,
                sourceVisible: lyricsControlsOpen,
                sharePicking: sharePicking,
                onSharePicking: { sharePicking = $0 },
                onChangeSource: { showLyricsSources = true },
                onRevealControls: { revealLyricsControls() },
                onFocusLyrics: { hideLyricsControls() },
                trackTitle: controller.current?.title ?? "",
                trackArtist: controller.current?.artist ?? "",
                artworkData: controller.current?.artworkData,
                artworkUrl: controller.current?.thumbnailUrl
            )
        case .queue:
            UpNextPane(
                showsCurrentTrackHeader: showsQueueCurrentTrackHeader,
                onOpenLyrics: { pane = .lyrics }
            )
        }
    }










    private var volumeSpansActionGlyphs: Bool {
        #if os(iOS)
        guard isIPad, pane == .main,
              let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
        else { return false }
        let screen = scene.screen.bounds.size
        let window = scene.coordinateSpace.bounds.size
        return screen.width < screen.height && window == screen
        #else
        false
        #endif
    }

    var deck: some View {
        VStack(spacing: 14) {




            if pane == .main { creditsRow }




            if pane != .lyrics { currentLyricStrip }


            positionControls(showsStatus: pane == .main)
                .padding(.horizontal, 32)
            playerTransport



            if !controller.hideVolumeBar {
                if volumeSpansActionGlyphs {


                    GeometryReader { geometry in
                        volumeRow(hugsGlyphs: true)
                            .padding(.horizontal, actionGlyphInkInset(width: geometry.size.width))
                    }
                    .frame(height: 16)
                } else {
                    deckVolumeRow
                        .padding(.horizontal, 32)
                }
            }
            playerActionRow
        }
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(



            LinearGradient(
                colors: [.clear, .black.opacity(0.30), .black.opacity(0.55)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
            .allowsHitTesting(false)
        )
    }
}
