import SwiftUI
import NuvxShared
#if canImport(UIKit)
import UIKit
#endif









struct PlaybackPill: View {


    var inline = false

    @Environment(PlaybackController.self) private var controller
    @Environment(AppModel.self) private var appModel
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    #if os(macOS)
    @Environment(\.macInspectorSelection) private var inspector
    #endif

    var body: some View {
        #if os(macOS)
        macOSPill
        #else
        iOSPill
        #endif
    }


    #if os(iOS)
    private enum PillDragAxis: Equatable {
        case horizontal
        case vertical
    }

    private struct PillDragState: Equatable {
        var axis: PillDragAxis? = nil
        var translation = CGSize.zero

        mutating func track(_ translation: CGSize) {
            if axis == nil, max(abs(translation.width), abs(translation.height)) >= 10 {
                axis = abs(translation.height) > abs(translation.width) ? .vertical : .horizontal
            }
            self.translation = translation
        }
    }

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @GestureState private var pillDrag = PillDragState()
    @State private var lockedPillDrag: (start: CGPoint, axis: PillDragAxis)?
    private var isRegularWidth: Bool { horizontalSizeClass == .regular }
    private var pillPullLift: CGFloat {
        guard pillDrag.axis == .vertical, pillDrag.translation.height < 0 else { return 0 }
        return min(max(-pillDrag.translation.height * 0.5, 0), 72)
    }

    private var iOSPill: some View {
        HStack(spacing: 12) {
            if isRegularWidth {
                padLeadingTransport
            }

            Button {
                appModel.nowPlayingPresented = true
            } label: {
                HStack(spacing: inline ? 8 : 10) {
                    artworkOrPlaceholder
                    info
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(controller.current?.title ?? "Not Playing"), \(controller.current?.artist ?? "")")
            .accessibilityHint("Opens Now Playing")

            if isRegularWidth {
                padTrailingControls
            } else {
                buttons
            }
        }
        .padding(.horizontal, inline ? 8 : 12)
        .padding(.vertical, inline ? 4 : 8)
        .modifier(MiniPlayerChrome(fill: chromeFill, reduceTransparency: reduceTransparency || reduceBlur))
        .overlay(alignment: .bottom) { progressHairline.clipShape(Capsule()) }
        .contentShape(.rect)




        .contextMenu { quickQueueMenu }
        .offset(y: -pillPullLift)


        .animation(
            pillDrag == PillDragState()
                ? .spring(response: 0.38, dampingFraction: 0.86)
                : nil,
            value: pillDrag
        )
        .modifier(NowPlayingZoomSource())


        .simultaneousGesture(
            DragGesture(minimumDistance: 10)
                .updating($pillDrag) { value, state, _ in
                    state.track(value.translation)
                }
                .onChanged { value in
                    if let lockedPillDrag, lockedPillDrag.start != value.startLocation {
                        self.lockedPillDrag = nil
                    }
                    if lockedPillDrag == nil,
                       max(abs(value.translation.width), abs(value.translation.height)) >= 10 {
                        lockedPillDrag = (
                            start: value.startLocation,
                            axis: abs(value.translation.height) > abs(value.translation.width)
                                ? .vertical
                                : .horizontal
                        )
                    }
                }
                .onEnded { value in
                    let dx = value.translation.width
                    let dy = value.translation.height
                    let axis = lockedPillDrag?.axis
                        ?? (abs(dy) > abs(dx) ? PillDragAxis.vertical : .horizontal)
                    lockedPillDrag = nil
                    if axis == .vertical {
                        guard dy < -10 else { return }
                        let heldPull = min(max(-dy * 0.5, 0), 72)
                        let flickedUp = value.predictedEndTranslation.height < dy - 72
                        guard flickedUp || heldPull > 36 else { return }
                        Haptics.play(.tap)
                        appModel.nowPlayingPresented = true
                        return
                    }
                    guard abs(dx) >= 72 else { return }
                    guard !PartyStore.shared.state.controlsLocked else {
                        Haptics.play(.tap)
                        return
                    }
                    if dx < 0 {
                        guard controller.canPlayNext else { return }
                        Haptics.play(.skipNext)
                        controller.next()
                    } else {
                        guard controller.canPlayPrevious else { return }
                        Haptics.play(.skipPrevious)
                        controller.previous()
                    }
                }
        )
    }
    #endif


    #if os(macOS)
    private var macOSPill: some View {
        HStack(spacing: 16) {
            HStack(spacing: 4) {
                pillButton("Shuffle", icon: .nuvxShuffle) {
                    controller.toggleShuffle()
                }
                .foregroundStyle(controller.shuffleEnabled ? Color.accentColor : .primary)

                pillButton("Previous", system: "backward.fill") {
                    controller.previous()
                }
                .disabled(!controller.canPlayPrevious)

                if controller.isBuffering {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 32, height: 32)
                        .help("Loading")
                } else {
                    pillButton(
                        controller.isPlaying ? "Pause" : "Play",
                        system: controller.isPlaying ? "pause.fill" : "play.fill",
                        glyph: 17
                    ) {
                        controller.togglePlayPause()
                    }
                    .disabled(controller.current == nil)
                }

                pillButton("Next", system: "forward.fill") {
                    controller.next()
                }
                .disabled(!controller.canPlayNext)

                Button {
                    controller.cycleRepeat()
                } label: {
                    RepeatGlyph(mode: controller.repeatMode, size: 15)
                        .frame(width: 32, height: 32)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .foregroundStyle(controller.repeatMode == .off ? .primary : Color.accentColor)
                .help(controller.repeatMode == .one ? "Repeat one" : controller.repeatMode == .all ? "Repeat all" : "Repeat off")

                partyButton
            }
            .foregroundStyle(.primary)

            MacNowPlayingSlot(
                artwork: { artworkOrPlaceholder },
                info: { info }
            )
            .frame(minWidth: 180, maxWidth: 360)

            HStack(spacing: 2) {


                inspectorToggle(.lyrics, icon: .nuvxLyrics)
                inspectorToggle(.queue, icon: .nuvxQueue)
                playerActionsButton
            }

            HStack(spacing: 6) {
                if !controller.hideVolumeBar {
                    PillSlider(volume: Binding(
                        get: { controller.volume },
                        set: { controller.volume = $0 }
                    ))
                    .frame(width: 112)
                    .help("Volume")
                    .accessibilityLabel("Volume")
                }
                AirPlayRouteButton()
                    .frame(width: 20, height: 20)
                    .frame(width: 28, height: 32)
                    .help("AirPlay")
                    .accessibilityLabel("AirPlay")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background {



            let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
            if reduceTransparency || reduceBlur {
                shape.fill(Color(nsColor: .windowBackgroundColor))
            } else {
                shape.fill(Color(nsColor: .windowBackgroundColor).opacity(0.55))
                    .background(.regularMaterial, in: shape)
            }
        }
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(.primary.opacity(0.1), lineWidth: 0.5)
        }
        .clipShape(.rect(cornerRadius: 18, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 5)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func inspectorToggle(_ kind: MacInspectorKind, icon: ImageResource) -> some View {
        let active = inspector.wrappedValue == kind
        return Button {
            inspector.wrappedValue = active ? nil : kind
        } label: {
            Image(icon)
                .resizable()
                .scaledToFit()
                .frame(width: 15, height: 15)
                .frame(width: 32, height: 32)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(active ? Color.accentColor : .secondary)
        .help(active ? "Hide \(kind.label)" : "Show \(kind.label)")
        .accessibilityLabel(kind.label)
        .accessibilityAddTraits(active ? .isSelected : [])
    }



    private func pillButton(
        _ label: String,
        icon: ImageResource? = nil,
        system: String? = nil,
        glyph: CGFloat = 15,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Group {
                if let icon {
                    Image(icon).resizable().scaledToFit().frame(width: glyph, height: glyph)
                } else if let system {
                    Image(systemName: system)
                        .font(.system(size: glyph, weight: .semibold))
                }
            }
            .frame(width: 32, height: 32)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .help(label)
        .accessibilityLabel(label)
    }
    #endif



    @ViewBuilder
    private var artworkOrPlaceholder: some View {
        let side: CGFloat = inline ? 30 : 40
        if controller.current != nil {
            VideoSurface(cornerRadius: 8) {
                ArtworkView(entry: controller.current, side: side)
            }
            .frame(width: side, height: side)
            .clipShape(.rect(cornerRadius: 8, style: .continuous))
            .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(.white.opacity(0.10), lineWidth: 0.5)
                }
                .id(controller.current?.id)
        } else {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(.quaternary.opacity(0.6))
                .frame(width: side, height: side)
                .overlay {
                        Image(.nuvxMusicNote)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 17)
                            .foregroundStyle(.secondary)
                }
        }
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(controller.current?.title ?? "Not Playing")
                .font(.callout.weight(.semibold))
                .foregroundStyle(controller.current == nil ? .secondary : .primary)
                .lineLimit(1)

            if !inline {
                Text(controller.current?.artist ?? "Music you start appears here")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }















    private var partyButton: some View {
        let party = PartyStore.shared
        return Button {
            if party.inParty {




                appModel.partyMembersPresented = true
            } else {
                appModel.listenTogetherPresented = true
            }
        } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "person.2.fill")
                    .scaledFont(size: 15, weight: .semibold)
                    .frame(width: 32, height: 32)
                    .contentShape(.rect)
                if party.inParty, party.state.members.count > 1 {
                    Text("\(party.state.members.count)")
                        .scaledFont(size: 9, weight: .bold)
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .padding(.horizontal, 3)
                        .background(Color.accentColor, in: Capsule())
                        .offset(x: 4, y: -2)
                }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(party.inParty ? Color.accentColor : .secondary)
        .help(partyHelp(party))
        .accessibilityLabel(partyHelp(party))
    }



    private func partyHelp(_ party: PartyStore) -> String {
        guard party.inParty else { return "Listen together" }
        let count = party.state.members.count
        return count > 1 ? "Listening together · \(count)" : "Listening together"
    }



    @ViewBuilder
    private var playerActionsButton: some View {
        #if os(macOS)
        Menu { playerActionItems } label: { playerActionLabel }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .disabled(controller.current == nil)
        #else
        Menu { playerActionItems } label: { playerActionLabel }
            .disabled(controller.current == nil)
        #endif
    }

    @ViewBuilder
    private var playerActionItems: some View {
        if let current = controller.current {
            SongActionButtons(entry: current, showSleepTimer: false)
        }
    }

    private var playerActionLabel: some View {
        Image(systemName: "ellipsis")
            .scaledFont(size: 15, weight: .semibold)
            .frame(width: 32, height: 32)
            .contentShape(Circle())
            .foregroundStyle(.secondary)
    }

    private var queueButton: some View {
        Button {
            PlatformSettings.shared.putString(key: "last_player_screen", value: "QUEUE")
            appModel.nowPlayingPresented = true
        } label: {
            Image(.nuvxQueue)
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
                .frame(width: 32, height: 32)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .help("Queue")
        .accessibilityLabel("Queue")
    }

    private var reduceBlur: Bool {
        PlatformSettings.shared.getBoolean(key: "reduce_dynamic_blur", default: false)
    }










    private var chromeFill: AnyShapeStyle {
        if reduceTransparency || reduceBlur {
            return AnyShapeStyle(Color.primary.opacity(0.12))
        }
        return AnyShapeStyle(Theme.Colors.surface)
    }


    private var progressHairline: some View {
        GeometryReader { geo in
            let progress = controller.duration > 0
                ? controller.position / controller.duration
                : 0
            Capsule()
                .fill(.tertiary)
                .frame(width: geo.size.width * progress, height: 2)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .allowsHitTesting(false)
    }

    #if os(iOS)






    private struct MiniPlayerChrome: ViewModifier {
        var fill: AnyShapeStyle
        var reduceTransparency: Bool
        @Environment(\.horizontalSizeClass) private var sizeClass

        func body(content: Content) -> some View {
            if sizeClass == .regular {





                content
                    .background {
                        if reduceTransparency {
                            Capsule().fill(Color(uiColor: .secondarySystemBackground))
                        } else {
                            Capsule().fill(Color(uiColor: .systemBackground).opacity(0.55))
                                .background(.regularMaterial, in: Capsule())
                        }
                    }
                    .overlay { Capsule().stroke(.white.opacity(0.12), lineWidth: 0.5) }
                    .clipShape(Capsule())
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 5)
            } else {
                content
                    .background { Capsule().fill(fill) }
                    .overlay { Capsule().stroke(.white.opacity(0.10), lineWidth: 0.5) }
                    .clipShape(Capsule())
            }
        }
    }
    #endif

    #if os(iOS)







    @ViewBuilder
    private var quickQueueMenu: some View {
        let start = controller.playingIndex + 1
        let upcoming: [(offset: Int, entry: QueueEntry)] = controller.queue.indices.contains(start)
            ? Array(controller.queue[start...].prefix(4).enumerated()).map { (offset: $0.offset, entry: $0.element) }
            : []

        if upcoming.isEmpty {
            Button("Nothing Queued") {}.disabled(true)
        } else {


            ForEach(upcoming, id: \.offset) { item in
                Button {
                    Haptics.play(.tap)
                    controller.playQueueItem(at: start + item.offset)
                } label: {
                    Text(item.entry.artist.isEmpty
                         ? item.entry.title
                         : "\(item.entry.title) — \(item.entry.artist)")
                }
            }
            Button("Clear Up Next", role: .destructive) {
                Haptics.play(.tap)
                controller.clearUpcoming()
            }
        }

        Divider()

        Button {
            PlatformSettings.shared.putString(key: "last_player_screen", value: "QUEUE")
            appModel.nowPlayingPresented = true
        } label: {
            Label("Queue", systemImage: "list.bullet")
        }

        Button {
            PlatformSettings.shared.putString(key: "last_player_screen", value: "LYRICS")
            appModel.nowPlayingPresented = true
        } label: {
            Label("Lyrics", systemImage: "quote.bubble")
        }

        Button {
            appModel.nowPlayingPresented = true
        } label: {
            Label("Open Player", systemImage: "arrow.up.left.and.arrow.down.right")
        }
    }

    private var buttons: some View {
        HStack(spacing: inline ? 2 : 8) {
            Button {
                Haptics.play(controller.isPlaying ? .pause : .resume)
                controller.togglePlayPause()
            } label: {
                if controller.isBuffering {
                    ProgressView().controlSize(.small).frame(width: 22, height: 22)
                } else {
                    Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                        .scaledFont(size: 20, weight: .semibold)
                }
            }
            .buttonStyle(.plain)
            .frame(width: inline ? 34 : 40, height: inline ? 34 : 40)
            .disabled(controller.current == nil && !controller.isBuffering)
            .accessibilityLabel(controller.isPlaying ? "Pause" : "Play")

            Button {
                Haptics.play(.skipNext)
                controller.next()
            } label: {
                Image(systemName: "forward.fill")
                    .scaledFont(size: 19, weight: .semibold)
            }
            .buttonStyle(.plain)
            .frame(width: inline ? 34 : 40, height: inline ? 34 : 40)
            .foregroundStyle(
                PartyStore.shared.state.controlsLocked ? Color.primary.opacity(0.3) : Color.primary
            )
            .disabled(!controller.canPlayNext || PartyStore.shared.state.controlsLocked)
            .accessibilityLabel("Next")

            if !inline {
                partyButton.frame(width: 40, height: 40)
            }
        }
        .foregroundStyle(.primary)
    }

    private var padLeadingTransport: some View {
        HStack(spacing: 4) {
            Button {
                guard controller.canPlayPrevious else { return }
                Haptics.play(.skipPrevious)
                controller.previous()
            } label: {
                Image(systemName: "backward.fill")
                    .scaledFont(size: 15, weight: .semibold)
                    .frame(width: 34, height: 34)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(!controller.canPlayPrevious)
            .accessibilityLabel("Previous")

            if controller.isBuffering {
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 34, height: 34)
            } else {
                Button {
                    Haptics.play(controller.isPlaying ? .pause : .resume)
                    controller.togglePlayPause()
                } label: {
                    Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                        .scaledFont(size: 18, weight: .semibold)
                        .frame(width: 34, height: 34)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .disabled(controller.current == nil && !controller.isBuffering)
                .accessibilityLabel(controller.isPlaying ? "Pause" : "Play")
            }

            Button {
                guard controller.canPlayNext else { return }
                Haptics.play(.skipNext)
                controller.next()
            } label: {
                Image(systemName: "forward.fill")
                    .scaledFont(size: 15, weight: .semibold)
                    .frame(width: 34, height: 34)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .foregroundStyle(
                PartyStore.shared.state.controlsLocked ? Color.primary.opacity(0.3) : Color.primary
            )
            .disabled(!controller.canPlayNext || PartyStore.shared.state.controlsLocked)
            .accessibilityLabel("Next")
        }
        .foregroundStyle(.primary)
    }

    private var padTrailingControls: some View {
        HStack(spacing: 6) {
            playerActionsButton

            Button {
                appModel.nowPlayingPresented = true
                PlatformSettings.shared.putString(key: "last_player_screen", value: "LYRICS")
            } label: {
                Image(.nuvxLyrics)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 15, height: 15)
                    .frame(width: 32, height: 32)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .accessibilityLabel("Lyrics")

            queueButton

            AirPlayRouteButton()
                .frame(width: 22, height: 22)
                .frame(width: 32, height: 32)
                .accessibilityLabel("AirPlay")

            partyButton.frame(width: 32, height: 32)
        }
    }
    #endif
}

#if os(macOS)



private struct MacNowPlayingSlot<Artwork: View, Info: View>: View {
    @Environment(PlaybackController.self) private var controller
    @Environment(AppModel.self) private var appModel
    @ViewBuilder var artwork: Artwork
    @ViewBuilder var info: Info

    @State private var hovering = false
    @State private var dragging = false

    init(@ViewBuilder artwork: () -> Artwork, @ViewBuilder info: () -> Info) {
        self.artwork = artwork()
        self.info = info()
    }

    private var scrubbing: Bool { (hovering || dragging) && controller.duration > 0 }

    var body: some View {
        ZStack(alignment: .bottom) {
            HStack(spacing: 10) {
                artwork
                info
                Spacer(minLength: 0)
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .opacity(scrubbing ? 0.35 : 1)
            .blur(radius: scrubbing ? 1.5 : 0)
            .allowsHitTesting(!scrubbing)
            .contentShape(.rect)
            .onTapGesture { appModel.nowPlayingPresented = true }

            if controller.duration > 0 {
                VStack(spacing: 3) {
                    if scrubbing {
                        HStack {
                            Text(clock(controller.position))
                            Spacer(minLength: 0)
                            Text(clock(max(controller.duration - controller.position, 0), remaining: true))
                        }
                        .font(.caption2.monospacedDigit().weight(.medium))
                        .foregroundStyle(.white.opacity(0.9))
                    }
                    SlotSeekLine(
                        progress: min(max(controller.position / controller.duration, 0), 1),
                        thick: scrubbing,
                        onSeek: { fraction in
                            controller.seek(to: fraction * controller.duration)
                        },
                        onDragging: { dragging = $0 }
                    )
                }
                .contentShape(.rect)
                .onHover { hovering = $0 }
            }
        }
        .frame(height: 44)
        .clipped()
        .animation(.easeInOut(duration: 0.16), value: scrubbing)
    }

    private func clock(_ seconds: Double, remaining: Bool = false) -> String {
        guard seconds.isFinite, seconds >= 0 else { return remaining ? "-0:00" : "0:00" }
        let total = Int(seconds.rounded(.down))
        let body = "\(total / 60):\(String(format: "%02d", total % 60))"
        return remaining ? "-\(body)" : body
    }
}

private struct SlotSeekLine: View {
    var progress: Double
    var thick: Bool
    var onSeek: (Double) -> Void
    var onDragging: (Bool) -> Void

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h: CGFloat = thick ? 8 : 2
            ZStack(alignment: .leading) {
                Capsule().fill(.primary.opacity(thick ? 0.28 : 0.18))
                Capsule()
                    .fill(.primary.opacity(thick ? 0.95 : 0.55))
                    .frame(width: max(0, w * min(max(progress, 0), 1)))
            }
            .frame(height: h)
            .frame(maxHeight: .infinity, alignment: .bottom)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        onDragging(true)
                        onSeek(min(max(g.location.x / max(w, 1), 0), 1))
                    }
                    .onEnded { _ in onDragging(false) }
            )
        }
        .frame(height: 8)
    }
}
#endif




struct PillSlider: View {
    @Binding var volume: Double

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: volume == 0 ? "speaker.slash.fill" : "speaker.fill")
                .scaledFont(size: 12, weight: .semibold)
                .foregroundStyle(.secondary)
                .frame(width: 16, height: 16)
            GeometryReader { geo in
                let w = geo.size.width
                let x = w * volume
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(.quaternary)
                        .frame(height: 4)
                    Capsule()
                        .fill(.primary.opacity(0.55))
                        .frame(width: max(4, x), height: 4)
                    Circle()
                        .fill(.primary)
                        .frame(width: 10, height: 10)
                        .offset(x: min(max(0, x - 5), w - 10))
                        .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                }
                .frame(height: geo.size.height)
                .contentShape(.rect)
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { g in
                            volume = min(max(0, g.location.x / w), 1)
                        }
                )
            }
            .frame(height: 14)
        }
        .accessibilityElement()
        .accessibilityLabel("Volume")
        .accessibilityValue("\(Int(volume * 100)) percent")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: volume = min(1, volume + 0.1)
            case .decrement: volume = max(0, volume - 0.1)
            default: break
            }
        }
    }
}

#if os(iOS)

private struct NowPlayingZoomSource: ViewModifier {
    @Environment(\.nowPlayingZoomNamespace) private var zoomNamespace

    func body(content: Content) -> some View {
        if let zoomNamespace {
            content.matchedTransitionSource(id: NowPlayingZoom.sourceID, in: zoomNamespace)
        } else {
            content
        }
    }
}
#endif
