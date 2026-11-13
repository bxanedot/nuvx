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
























    #if os(iOS)
    var iOSBody: some View {
        NavigationStack {






            GeometryReader { geo in
                ZStack {
                    MeshBackdrop(seed: controller.current?.id.hashValue ?? 0, artwork: controller.current?.artworkData)
                        .ignoresSafeArea()
                    if !isIPad && pane == .main && !PlayerLayout.takesLandscapeShape(width: geo.size.width, height: geo.size.height) && fullBleedOn && !videoShowing {
                        ArtworkContinuation(entry: controller.current, seam: geo.size.width)
                            .ignoresSafeArea(edges: .bottom)
                    }
                    if usesBlurredArtworkBackdrop(width: geo.size.width, height: geo.size.height) {
                        FullArtworkBlurBackdrop(entry: controller.current)
                            .ignoresSafeArea()
                            .transition(.opacity)
                    }




                    ZStack {
                        if showsTwoColumns(size: geo.size) {
                            twoColumnPlayer(size: geo.size)
                                .transition(.opacity)
                        } else {
                            portraitPlayer
                                .transition(.opacity)
                        }
                    }
                    .animation(isIPad ? .easeInOut(duration: 0.22) : nil, value: pane)
                }
            }







            .overlay(alignment: .top) {
                if isIPad {
                    Capsule()
                        .fill(.white.opacity(0.35))
                        .frame(width: 42, height: 5)
                        .padding(.top, 10)
                        .accessibilityLabel("Close")
                        .accessibilityAction { dismiss() }
                }
            }
            .gesture(
                DragGesture(minimumDistance: 24)
                    .onEnded { value in
                        guard isIPad,
                              value.translation.height > 140,
                              abs(value.translation.width) < 80,
                              value.startLocation.y < 120
                        else { return }
                        dismiss()
                    }
            )
            .toolbar(.hidden, for: .navigationBar)
            .toolbarTitleDisplayMode(.inline)


            .ignoresSafeArea(.container, edges: isIPad ? [] : .top)
        }
    }
    #endif














    private func showsTwoColumns(size: CGSize) -> Bool {
        if PlayerLayout.takesLandscapeShape(width: size.width, height: size.height) {
            return true
        }
        return pane != .main && PlayerLayout.putsPaneBesideStage(
            width: size.width,
            regularWidth: isIPad
        )
    }


    var portraitPlayer: some View {
        VStack(spacing: 0) {
            stage
            if pane != .lyrics || lyricsControlsOpen || keepsLyricsPlaybackDeckVisible {
                deck
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: lyricsControlsOpen)
    }










    func twoColumnPlayer(size: CGSize) -> some View {
        let compact = PlayerLayout.isCompactLandscape(width: size.width, height: size.height)
        let gutter = PlayerLayout.gutter(compact: compact)
        return Group {
            if compact {
                compactLandscapePlayer(size: size, gutter: gutter)
            } else {
                wideTwoColumnPlayer(size: size, gutter: gutter)
            }
        }
    }

    private func compactLandscapePlayer(size: CGSize, gutter: CGFloat) -> some View {
        HStack(spacing: 0) {
            VStack(spacing: 12) {
                GeometryReader { geo in
                    artworkStage(
                        side: max(80, min(geo.size.width, geo.size.height)),
                        collapsed: false
                    )
                }
                playerActionRow
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, gutter)

            Group {
                switch pane {
                case .main:
                    VStack(spacing: 14) {
                        creditsRow
                        currentLyricStrip
                        positionControls
                        playerTransport
                    }
                    .transition(.opacity)
                case .lyrics, .queue:
                    panel(showsQueueCurrentTrackHeader: true)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity)
            .animation(.easeInOut(duration: 0.2), value: pane)
        }
        .frame(maxWidth: PlayerLayout.landscapeMaxWidth)
        .frame(maxWidth: .infinity)
    }

    private func wideTwoColumnPlayer(size: CGSize, gutter: CGFloat) -> some View {
#if os(macOS)
        let maximumWidth = PlayerLayout.macLandscapeMaxWidth
        let artworkMaximum: CGFloat = 460
        let artworkHeightFraction: CGFloat = 0.46
        let leftColumnMaximum: CGFloat = 480
#else
        let maximumWidth = PlayerLayout.ipadLandscapeMaxWidth
        let artworkMaximum: CGFloat = 560
        let artworkHeightFraction: CGFloat = 0.58
        let leftColumnMaximum: CGFloat = 600
#endif
        return HStack(spacing: 36) {

            VStack(spacing: 16) {
                Spacer(minLength: 0)

                GeometryReader { geo in
                    let side = min(geo.size.width, geo.size.height)
                    artworkStage(side: max(180, side), collapsed: false)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                }
                .frame(maxHeight: min(size.height * artworkHeightFraction, artworkMaximum))

                VStack(spacing: 12) {
                    creditsRow




                    if pane != .lyrics {
                        currentLyricStrip
                            .padding(.horizontal, 12)
                    }

                    positionControls
                        .padding(.horizontal, 12)

                    playerTransport

                    #if os(iOS)
                    if !controller.hideVolumeBar {
                        deckVolumeRow
                            .padding(.horizontal, 12)
                    }
                    #endif




                    actionRowPill
                    #if os(iOS)
                    Text(outputCaption)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                        .accessibilityLabel(outputCaption)
                    #endif
                }
                .frame(maxWidth: leftColumnMaximum - 40)

                Spacer(minLength: 0)
            }
            .frame(maxWidth: leftColumnMaximum)


            .padding(.leading, pane == .main ? 0 : gutter)




            if pane != .main {
                Group {
                    if pane == .queue {
                        UpNextPane(
                            showsCurrentTrackHeader: false,
                            onOpenLyrics: { pane = .lyrics }
                        )
                            .transition(.opacity)
                    } else {
                        desktopLyricsPane
                            .transition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.bottom, 72)
                .padding(.trailing, gutter)
                .transition(.opacity)
            }
        }
        .frame(maxWidth: maximumWidth)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topTrailing) {
            if pane == .lyrics {
                lyricsToolsButton
                    .padding(.trailing, gutter)
                    .padding(.top, 18)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            landscapePaneSwitcher
                .padding(.trailing, gutter)
                .padding(.bottom, 12)
        }
        .animation(.easeInOut(duration: 0.2), value: pane)
    }

    private var landscapePaneSwitcher: some View {





        HStack(spacing: 18) {
            actionGlyph(.nuvxLyrics, selected: pane == .lyrics, label: "Lyrics") {
                Haptics.play(.expand)
                pane = pane == .lyrics ? .main : .lyrics
            }
            actionGlyph(.nuvxQueue, selected: pane == .queue, label: "Up Next") {
                Haptics.play(.expand)
                pane = pane == .queue ? .main : .queue
            }
        }
    }

    var lyricsToolsButton: some View {
        Menu {
            Button("Change Lyrics Source", systemImage: "text.magnifyingglass") {
                showLyricsSources = true
            }
            Button("Adjust Lyrics Timing", systemImage: "slider.horizontal.2.square") {
                showLyricsOffset = true
            }
        } label: {
            Image(systemName: "wand.and.stars")
                .scaledFont(size: 16, weight: .semibold)
                .foregroundStyle(.white.opacity(0.9))
                .frame(width: 42, height: 42)
                .chromeGlass(in: Circle())
                .contentShape(Circle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .help("Lyrics options")
        .accessibilityLabel("Lyrics options")
    }
}
