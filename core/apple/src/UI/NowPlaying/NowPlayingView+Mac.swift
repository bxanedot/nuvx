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

    #if os(macOS)
    var macOSBody: some View {




        GeometryReader { geo in
            ZStack {
                MeshBackdrop(seed: controller.current?.id.hashValue ?? 0, artwork: controller.current?.artworkData)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                if usesBlurredArtworkBackdrop(width: geo.size.width, height: geo.size.height) {
                    FullArtworkBlurBackdrop(entry: controller.current)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }

                if PlayerLayout.takesLandscapeShape(
                    width: geo.size.width, height: geo.size.height
                ) {
                    twoColumnPlayer(size: geo.size)
                } else {
                    portraitPlayer
                }
            }
        }
        .toolbar { macPlayerToolbar }
        .toolbar(removing: .title)
        .toolbar(removing: .sidebarToggle)
        .toolbarBackground(.hidden, for: .windowToolbar)
        .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
        .navigationTitle("")
        .environment(\.colorScheme, .dark)
    }

    @ToolbarContentBuilder
    var macPlayerToolbar: some ToolbarContent {




        if #available(macOS 26.0, *) {
            ToolbarItem(placement: .navigation) {
                macBackButton
                    .glassEffect(.regular.interactive(), in: Circle())
                    .background(WindowLeadingReader { closeLeading in
                        trailingInset = max(0, closeLeading - Self.systemTitlebarMargin)
                    })
            }
            .sharedBackgroundVisibility(.hidden)
            ToolbarItem(placement: .navigation) {
                MacFullScreenButton()
                    .buttonStyle(.plain)
                    .frame(width: 32, height: 32)
                    .glassEffect(.regular.interactive(), in: Circle())
            }
            .sharedBackgroundVisibility(.hidden)
            ToolbarSpacer(.flexible, placement: .primaryAction)
        } else {
            ToolbarItem(placement: .navigation) { macBackButton }
            ToolbarItem(placement: .navigation) { MacFullScreenButton() }
        }

        if !controller.hideVolumeBar {
            ToolbarItem(placement: .primaryAction) {
                ToolbarVolumeSlider(volume: Binding(
                    get: { controller.volume },
                    set: { controller.volume = $0 }
                ))
                .help("Volume")
            }
        }

        if #available(macOS 26.0, *) {
            ToolbarSpacer(.fixed, placement: .primaryAction)
            ToolbarItem(placement: .primaryAction) {
                AirPlayRouteButton()
                    .frame(width: 22, height: 22)
                    .frame(width: 32, height: 32)
                    .glassEffect(.regular, in: Circle())
                    .help("AirPlay")
            }
            .sharedBackgroundVisibility(.hidden)
            ToolbarSpacer(.fixed, placement: .primaryAction)
            MacWindowControls()
        } else {
            ToolbarItem(placement: .primaryAction) {
                AirPlayRouteButton()
                    .frame(width: 22, height: 22)
                    .frame(width: 32, height: 32)
                    .background(.ultraThinMaterial, in: Circle())
                    .help("AirPlay")
            }
            MacWindowControls()
        }
    }





    @MainActor private static var cachedTitlebarMargin: CGFloat?

    @MainActor private static var systemTitlebarMargin: CGFloat {
        if let button = MacFullScreenButton.playerWindow?.standardWindowButton(.closeButton),
           !button.isHidden, button.window?.styleMask.contains(.fullScreen) == false {
            let margin = button.convert(button.bounds, to: nil).minX
            cachedTitlebarMargin = margin
            return margin
        }
        return cachedTitlebarMargin ?? 0
    }

    private var macBackButton: some View {
        Button {
            appModel.nowPlayingPresented = false
        } label: {
            Image(systemName: "chevron.left")

                .frame(width: 32, height: 32)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .keyboardShortcut(.cancelAction)
        .labelStyle(.iconOnly)
        .help("Back to Library")
        .accessibilityLabel("Back to Library")
    }

    #endif
}
