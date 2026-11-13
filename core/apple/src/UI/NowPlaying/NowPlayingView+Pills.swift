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

    var creditsRow: some View {




        let current = controller.current
        let scrolls = pane == .main
        let artistColor = Color.white.opacity(0.55)
        return HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if current?.isExplicit == true {
                        ExplicitMark(color: artistColor)
                    }
                    Marquee(enabled: scrolls, onOverflowChange: { titleCrawls = $0 }) {
                        Text(current?.title ?? "Nothing playing")
                            .font(.system(size: 20, weight: .bold))
                            .tracking(-0.2)
                            .foregroundStyle(.white)
                    }

                    .contentShape(Rectangle())
                    .onTapGesture {
                        guard let albumId = current?.albumId else { return }
                        appModel.openPage(browseId: albumId, title: current?.albumName ?? "Album")
                    }
                    .accessibilityAddTraits(current?.albumId != nil ? .isButton : [])
                }
                ArtistCreditText(
                    credit: current?.artist ?? "",
                    artistId: current?.artistId,
                    font: .system(size: 20, weight: .medium),
                    color: artistColor,
                    marquee: (scrolls, titleCrawls ? 3 : 0)
                )
            }
            .id(current?.id)
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.3), value: current?.id)
            Spacer(minLength: 10)
            if auth.signedIn, current?.videoId != nil {
                GlassCircleButton(
                    system: controller.isLiked ? "star.fill" : "star",
                    selected: controller.isLiked,
                    label: controller.isLiked ? "Remove from Liked" : "Like"
                ) {
                    Haptics.play(.tap)
                    controller.toggleLike()
                }
                .help(controller.isLiked ? "Remove from Liked Music" : "Like")
                .padding(.trailing, 8)
            }
            moreMenu
        }
        .padding(.horizontal, 24)
    }








    var actionRowPill: some View {
        Group {
            if pane == .queue {
                queueModesPill
            } else {
                outputPartyPill
            }
        }
        .transition(.opacity.combined(with: .scale(scale: 0.94)))
    }









    var playerActionRow: some View {
        VStack(spacing: 10) {
            GeometryReader { geometry in
                let edgeInset = actionRowEdgeInset(width: geometry.size.width)
                HStack {
                    actionGlyph(.nuvxLyrics, selected: pane == .lyrics, label: "Lyrics") {
                        Haptics.play(.expand)
                        pane = pane == .lyrics ? .main : .lyrics
                    }
                    Spacer(minLength: 0)





                    actionRowPill
                    Spacer(minLength: 0)
                    actionGlyph(.nuvxQueue, selected: pane == .queue, label: "Up Next") {
                        Haptics.play(.expand)
                        pane = pane == .queue ? .main : .queue
                    }
                }
                .padding(.horizontal, edgeInset)
            }
            .frame(height: 44)

            if !outputCaption.isEmpty {
                Text(outputCaption)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)
                    .accessibilityLabel(outputCaption)
            }
        }
        .frame(maxWidth: .infinity)
    }


    func actionRowEdgeInset(width: CGFloat) -> CGFloat {
        max(0, (width - (44 * 2 + 158)) / 4)
    }




    func actionGlyphInkInset(width: CGFloat) -> CGFloat {
        actionRowEdgeInset(width: width) + (44 - 26) / 2 + 26 * 2 / 24
    }

    func actionGlyph(
        _ image: ImageResource,
        selected: Bool,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(image)
                .resizable()
                .scaledToFit()
                .frame(width: 26, height: 26)
                .foregroundStyle(.white.opacity(selected ? 1 : 0.75))
                .frame(width: 44, height: 44)
                .background(.white.opacity(selected ? 0.2 : 0), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private var outputPartyPill: some View {
        HStack(spacing: 0) {
            Button {
                Haptics.play(.tap)
                showOutputDevice = true
            } label: {
                Image(systemName: "headphones")
                    .scaledFont(size: 23, weight: .regular)
                    .foregroundStyle(.white.opacity(0.88))
                    .frame(width: outputPartyButtonWidth, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Audio output")

            pillDivider

#if os(iOS)
            if pane == .main {
                Button {
                    let enabled = !controller.mixWithOtherAudio
                    Haptics.play(enabled ? .toggleOn : .toggleOff)
                    controller.setMixWithOtherAudio(enabled)
                } label: {
                    Image(systemName: "waveform")
                        .scaledFont(size: 23, weight: .regular)
                        .foregroundStyle(.white.opacity(controller.mixWithOtherAudio ? 1 : 0.75))
                        .frame(width: outputPartyButtonWidth, height: 44)
                        .background(.white.opacity(controller.mixWithOtherAudio ? 0.14 : 0))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("player.audioMixing")
                .accessibilityLabel("Mix with Other Apps")
                .accessibilityValue(controller.mixWithOtherAudio ? "On" : "Off")
                .accessibilityHint(controller.mixWithOtherAudio
                    ? "Turn off to give nuvx audio priority. Other apps may pause."
                    : "Turn on to keep other apps audible.")
                .accessibilityAddTraits(controller.mixWithOtherAudio ? .isSelected : [])
                .help(controller.mixWithOtherAudio ? "Mixing on" : "nuvx audio priority")

                pillDivider
            }
#endif

            if pane == .main {
                Button {
                    let enabled = !showMusicVideos
                    Haptics.play(enabled ? .toggleOn : .toggleOff)
                    showMusicVideos = enabled
                } label: {
                    Image(systemName: showMusicVideos ? "play.rectangle.fill" : "play.rectangle")
                        .scaledFont(size: 22, weight: .regular)
                        .foregroundStyle(.white.opacity(showMusicVideos ? 1 : 0.75))
                        .frame(width: outputPartyButtonWidth, height: 44)
                        .background(.white.opacity(showMusicVideos ? 0.14 : 0))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("player.musicVideos")
                .accessibilityLabel("Music Videos")
                .accessibilityValue(showMusicVideos ? "On" : "Off")
                .accessibilityAddTraits(showMusicVideos ? .isSelected : [])
                .help(showMusicVideos ? "Music videos on" : "Music videos off")

                pillDivider
            }

            Button {
                Haptics.play(.tap)
                if party.inParty {
                    appModel.partyMembersPresented = true
                } else {
                    appModel.listenTogetherPresented = true
                }
            } label: {
                Image(systemName: "person.fill")
                    .scaledFont(size: 22, weight: .regular)
                    .foregroundStyle(.white.opacity(party.inParty ? 1 : 0.75))
                    .frame(width: outputPartyButtonWidth, height: 44)
                    .background(.white.opacity(party.inParty ? 0.14 : 0))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(party.inParty ? "People listening" : "Listen Together")
        }
        .background(.white.opacity(0.12))
        .clipShape(Capsule())
        .accessibilityElement(children: .contain)
    }

    private var outputPartyButtonWidth: CGFloat {
#if os(iOS)
        pane == .main ? 52 : 64
#else
        64
#endif
    }



    var queueModesPill: some View {
        HStack(spacing: 0) {
            Button {
                Haptics.play(controller.shuffleEnabled ? .toggleOff : .toggleOn)
                controller.toggleShuffle()
            } label: {
                Image(.nuvxShuffle)
                    .resizable().scaledToFit()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(.white.opacity(controller.shuffleEnabled ? 1 : 0.75))
                    .frame(width: 52, height: 44)
                    .background(.white.opacity(controller.shuffleEnabled ? 0.14 : 0))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(controller.shuffleEnabled ? "Shuffle on" : "Shuffle off")

            pillDivider

            Button {
                Haptics.play(.select)
                controller.cycleRepeat()
            } label: {
                Group {
                    if controller.repeatMode == .one {
                        Text("1").scaledFont(size: 19, weight: .bold)
                    } else {
                        Image(.nuvxRepeat).resizable().scaledToFit().frame(width: 22, height: 22)
                    }
                }
                .foregroundStyle(.white.opacity(controller.repeatMode == .off ? 0.75 : 1))
                .frame(width: 52, height: 44)
                .background(.white.opacity(controller.repeatMode == .off ? 0 : 0.14))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(repeatLabel)

            pillDivider

            Button {
                Haptics.play(controller.autoplayEnabled ? .toggleOff : .toggleOn)
                controller.toggleAutoplay()
            } label: {
                Image(.nuvxInfinity)
                    .resizable().scaledToFit()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(.white.opacity(controller.autoplayEnabled ? 1 : 0.75))
                    .frame(width: 52, height: 44)
                    .background(.white.opacity(controller.autoplayEnabled ? 0.14 : 0))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(controller.autoplayEnabled ? "AutoPlay on" : "AutoPlay off")

            pillDivider


            Button {
                Haptics.play(controller.automixEnabled ? .toggleOff : .toggleOn)
                controller.toggleAutomix()
            } label: {
                Image(.nuvxVinylRecord)
                    .resizable().scaledToFit()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(.white.opacity(controller.automixEnabled ? 1 : 0.75))
                    .frame(width: 52, height: 44)
                    .background(.white.opacity(controller.automixEnabled ? 0.14 : 0))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(controller.automixEnabled ? "AutoMix on" : "AutoMix off")
            .help(controller.automixEnabled
                ? "AutoMix blends the end of one track into the next"
                : "AutoMix off — tracks change at the end")
        }
        .background(.white.opacity(0.12))
        .clipShape(Capsule())
        .accessibilityElement(children: .contain)
    }

    private var pillDivider: some View {
        Rectangle().fill(.white.opacity(0.20)).frame(width: 1, height: 44)
    }

    var outputCaption: String {





        #if os(iOS)
        let port = AVAudioSession.sharedInstance().currentRoute.outputs.first?.portName ?? ""
        let routeName = port.trimmingCharacters(in: .whitespacesAndNewlines)
        #else
        let routeName = controller.outputDevice.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
        #endif
        let lowered = routeName.lowercased()
        if controller.outputDevice.started, !routeName.isEmpty,
           !lowered.contains("default output") {
            return routeName
        }
        if party.inParty { return party.state.you?.name ?? "Playing together" }
        return "Default Device"
    }
}
