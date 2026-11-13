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


    var lyricsHeader: some View {
        HStack(spacing: 12) {
            Button {
                Haptics.play(.tap)
                pane = .main
            } label: {
                SleeveArt(
                    entry: controller.current,
                    canvasURL: controller.canvasURL,
                    fallbackURL: controller.canvasFallbackURL,
                    isPlaying: controller.isPlaying,
                    side: PlayerLayout.sleeveCollapsedSide
                )
                .id(controller.current?.id)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back to player")

            VStack(alignment: .leading, spacing: 3) {
                Text(controller.current?.title ?? "Nothing playing")
                    .font(.headline.weight(.semibold))
                    .lineLimit(1)
                ArtistCreditText(
                    credit: controller.current?.artist ?? "",
                    artistId: controller.current?.artistId,
                    font: .subheadline,
                    color: .white.opacity(0.7)
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if auth.signedIn, controller.current?.videoId != nil {
                GlassCircleButton(
                    icon: controller.isLiked ? .nuvxHeartFilled : .nuvxHeart,
                    label: controller.isLiked ? "Remove Like" : "Like"
                ) { controller.toggleLike() }
            }
            if controller.displayedLyrics.isEmpty {
                moreMenu
            } else {



                GlassCircleButton(
                    system: sharePicking ? "xmark" : "square.and.arrow.up",
                    label: sharePicking ? "Stop picking lyrics" : "Share lyrics"
                ) {
                    Haptics.play(.tap)
                    sharePicking.toggle()
                }
                .help(sharePicking ? "Stop picking lyrics" : "Share lyrics")
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 14)
        .foregroundStyle(.white)
    }







    @ViewBuilder
    var currentLyricStrip: some View {


        if showsLyricLine { lyricStripButton }
    }

    private var lyricStripButton: some View {
        Button {
            Haptics.play(.expand)
            pane = pane == .lyrics ? .main : .lyrics
        } label: {
            Group {
                if let line = currentLyric {




                    HStack(spacing: 6) {
                        Text(line)
                            .foregroundStyle(.white.opacity(0.92))
                            .multilineTextAlignment(currentLyricIsRightToLeft ? .trailing : .leading)
                            .layoutPriority(1)
                        Image(.nuvxChevronRight)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 9, height: 9)
                            .foregroundStyle(.white.opacity(0.55))
                            .scaleEffect(x: currentLyricIsRightToLeft ? -1 : 1)
                            .accessibilityHidden(true)
                    }
                    .environment(
                        \.layoutDirection,
                        currentLyricIsRightToLeft ? .rightToLeft : .leftToRight
                    )
                } else if controller.lyricsLoading {
                    Text("Looking for lyrics…")
                        .foregroundStyle(.white.opacity(0.5))
                } else if controller.lyricsUnavailable {
                    Text("No lyrics for this track")
                        .foregroundStyle(.white.opacity(0.5))
                } else {



                    Text(controller.current == nil ? "" : "Lyrics off")
                        .foregroundStyle(.white.opacity(0.4))
                }
            }
            .font(.subheadline.weight(.medium))
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .center)
            .shadow(color: .black.opacity(0.45), radius: 5, y: 1)
            .padding(.horizontal, 24)
            .frame(height: 20)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(currentLyric == nil)
        .accessibilityLabel(
            currentLyric.map { "Now singing: \($0)" }
                ?? (controller.lyricsLoading ? "Looking for lyrics" : "No lyrics")
        )
        .accessibilityHint("Opens the lyrics")
    }




    private var currentLyric: String? {
        CurrentLyricLine.line(
            in: controller.displayedLyrics,
            at: controller.position,
            offsetMs: lyricsOffsetMs
        )
    }




    private var currentLyricIsRightToLeft: Bool {
        guard let currentLyric else { return false }
        for scalar in currentLyric.unicodeScalars {
            let value = scalar.value
            guard CharacterSet.letters.contains(scalar) else { continue }
            return (0x0590...0x08FF).contains(value) ||
                (0xFB1D...0xFDFF).contains(value) ||
                (0xFE70...0xFEFF).contains(value) ||
                (0x1EE00...0x1EEFF).contains(value)
        }
        return false
    }

    func revealLyricsControls() {
        lyricsControlsOpen = true
        lyricsControlActivity += 1
    }

    func hideLyricsControls() {
        lyricsControlsOpen = false
    }
}
