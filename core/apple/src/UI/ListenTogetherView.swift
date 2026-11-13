import SwiftUI
import NuvxShared
#if os(iOS)
import UIKit
#else
import AppKit
#endif


























struct ListenTogetherView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast
    @Environment(PartyStore.self) private var party
    @Environment(AppModel.self) private var appModel
    @Environment(AuthController.self) private var auth






    @State private var sheet: PartySheetCase?

    @State private var joining: PartyPreview?

    @State private var joiningServer: String?

    @State private var codeFromLink: String?
    @State private var serverEditorPresented = false




    @State private var signInPresented = false






    private static let inviteDelay: Duration = .seconds(1)

    var body: some View {
        Form {
            if party.inParty {
                codeSection
                nowPlayingSection
                transportSection
                queueSection
                listeningSection
                leaveSection
                let entries = party.state.activities
                if !entries.isEmpty {
                    activitySection(entries)
                }
            } else {
                if !auth.signedIn {




                    signInSection
                }
                landingSection
            }
            if sheet == nil, let message = party.failure ?? party.state.error?.message {




                Section {
                    Text(message)
                        .font(.callout)
                        .foregroundStyle(.red)
                }
            }
            serverSection
        }
        .formStyle(.grouped)
        .navigationTitle("Listen together")
        .sheet(isPresented: $signInPresented) {



            NavigationStack {
                YtMusicLoginView(
                    onCaptured: { session, done in
                        auth.accept(session) { accepted in
                            if accepted { signInPresented = false }
                            done(accepted)
                        }
                    },
                    onDismiss: { signInPresented = false }
                )
            }
            #if os(macOS)
            .frame(minWidth: 720, minHeight: 640)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 520, idealWidth: 600)
        #endif
        .task {
            party.syncFromCoordinator()




            if let invite = appModel.pendingPartyInvite {
                appModel.pendingPartyInvite = nil
                arrive(withInvite: invite)
            } else if let codeFromLink, !codeFromLink.isEmpty {
                self.codeFromLink = nil
                arrive(code: codeFromLink)
            }
        }
        .sheet(item: $sheet) { item in
            switch item {
            case .create:
                CreatePartySheet { created() }
            case .joinCode:
                JoinPartySheet(initialCode: joining?.code ?? "")
            case .confirm:
                JoinConfirmSheet(preview: joining, server: joiningServer)
            case .invite:
                InviteSheet()
            }
        }
        .sheet(isPresented: $serverEditorPresented) { PartyServerEditor() }
        .onChange(of: party.pendingPreview?.code) { _, code in



            guard let code, let preview = party.pendingPreview else { return }
            joining = preview
            joiningServer = nil
            sheet = .confirm
            _ = code
        }
    }










    private func created() {
        Task {
            try? await Task.sleep(for: Self.inviteDelay)



            guard sheet == nil else { return }
            sheet = .invite
        }
    }






    private var signInSection: some View {
        Section {
            Button {
                signInPresented = true
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Sign in")
                            .foregroundStyle(.primary)
                        Text("Not signed in")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
            }
        } footer: {
            Text("Listening together needs an account, so everyone in the party can see who else is in it.")
        }
    }

    private var landingSection: some View {
        Section {
            PartyLanding(
                avatarUrl: party.avatarUrl,
                enabled: auth.signedIn && party.hasServer && !party.busy,
                serverMissing: !party.hasServer,
                busy: party.busy,
                onCreate: { sheet = .create },
                onJoin: { sheet = .joinCode }
            )
        }
    }



    private var codeSection: some View {
        Section {
            VStack(spacing: 10) {
                Text(party.code)
                    .scaledFont(size: 32, weight: .semibold, design: .monospaced)
                    .tracking(6)
                    .textSelection(.enabled)
                    .padding(.vertical, 8)
                    .accessibilityLabel("Party code")
                    .accessibilityValue(party.code)
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)

            Button {
                copyCode()
            } label: {
                Label("Copy code", systemImage: "doc.on.doc")
            }

            ShareLink(
                item: shareText,
                subject: Text("Listen together"),
                message: Text("Listen with me on nuvx — party code \(party.code)")
            ) {
                Label("Share", systemImage: "square.and.arrow.up")
            }
        } header: {
            Text("Party code")
        } footer: {
            Text(PartyCopy.codeFooter)
        }
    }






    private var shareText: String {
        guard let link = party.inviteLink else { return party.code }
        return "\(link)\n\nListen with me on nuvx — party code \(party.code)"
    }

    private func copyCode() {
        #if os(iOS)
        UIPasteboard.general.string = party.code
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(party.code, forType: .string)
        #endif
        toast.show("Copied \(party.code)")
    }



    private var nowPlayingSection: some View {
        Section("Now playing") {
            PartyNowPlayingRow(
                title: PartyCopy.nowPlayingTitle(party.state.playback.track),
                detail: PartyCopy.nowPlayingDetail(
                    track: party.state.playback.track,
                    positionMs: party.partyPositionMs,
                    connection: party.state.connection,
                    clockSynced: party.state.clockSynced,
                    roundTripMs: party.state.roundTripMs
                )
            )
        }
    }

















    private var transportSection: some View {
        Section {
            HStack(spacing: 0) {
                PartyTransportButton(
                    systemImage: "backward.end.fill",
                    label: "Previous",
                    enabled: party.state.canControl,
                    action: { party.previous() }
                )
                PartyTransportButton(
                    systemImage: party.state.playback.isPlaying ? "pause.fill" : "play.fill",
                    label: PartyCopy.transportTitle(isPlaying: party.state.playback.isPlaying),
                    enabled: party.state.canControl,
                    prominent: true,
                    action: {
                        if party.state.playback.isPlaying { party.pause() } else { party.play() }
                    }
                )
                PartyTransportButton(
                    systemImage: "forward.end.fill",
                    label: "Next",
                    enabled: party.state.canControl,
                    action: { party.next() }
                )
            }
            .padding(.vertical, 4)
        } footer: {
            if party.state.controlsLocked {
                Text(PartyCopy.transportLockedFooter)
            }
        }
    }











    private var queueSection: some View {
        Section {
            ForEach(Array(party.state.queue.items.enumerated()), id: \.element.videoId) { index, track in
                PartyQueueRow(
                    track: track,
                    position: Int(party.state.playback.queueIndex),
                    index: index,
                    isCurrent: party.state.playback.track?.videoId == track.videoId,
                    canControl: party.state.canControl,
                    onPlay: { playNow(track) },
                    onRemove: {
                        party.removeFromPartyQueue(track.videoId)
                        toast.show("Removed from the queue", kind: .info)
                    }
                )
            }
            if party.state.queue.items.isEmpty {
                Text(PartyCopy.queueEmpty)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        } header: {
            let ahead = party.state.queue.items.count - max(0, Int(party.state.playback.queueIndex) + 1)
            Text(PartyCopy.queueHeader(ahead: ahead))
        }
    }


    private func playNow(_ track: PartyTrack) {
        guard party.state.canControl else {
            toast.show("Only the host can change the music", kind: .failure)
            return
        }
        guard party.playInParty(QueueEntry.asPartyTrack(track)) else {
            toast.show("Only the host can change the music", kind: .failure)
            return
        }
    }



    private var listeningSection: some View {
        Section {
            if party.isHost {
                partySizeRow
                Toggle(isOn: hostOnlyBinding) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(PartyCopy.hostOnlyTitle)
                        Text(PartyCopy.hostOnlySubtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            ForEach(party.state.members, id: \.memberId) { member in
                MemberRow(
                    name: member.name,
                    avatarUrl: member.avatarUrl,
                    isYou: party.state.isMe(member: member),
                    isHost: member.isHost,
                    connected: member.connected,
                    canRemove: party.isHost && !member.isHost,
                    onRemove: {
                        party.kick(member.memberId)
                        toast.show("Removed \(member.name)", kind: .info)
                    }
                )
            }
        } header: {
            Text(PartyCopy.listeningHeader(members: party.state.members.count, maxMembers: Int(party.state.maxMembers)))
        } footer: {
            Text(PartyCopy.listeningFooter(maxMembers: Int(party.state.maxMembers)))
        }
    }






    private var partySizeRow: some View {
        let floor = max(2, Int(party.state.members.count))
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Party size")
                Text(PartyCopy.partySizeFooter)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            StepperStep(
                symbol: "minus",
                enabled: Int(party.state.maxMembers) > floor,
                action: { party.setMaxMembers(party.state.maxMembers - 1) }
            )
            Text("\(party.state.maxMembers)")
                .font(.title3.monospacedDigit())
                .frame(minWidth: 28)
                .multilineTextAlignment(.center)
            StepperStep(
                symbol: "plus",
                enabled: party.state.maxMembers < 10,
                action: { party.setMaxMembers(party.state.maxMembers + 1) }
            )
        }
        .padding(.vertical, 3)
    }

    private var hostOnlyBinding: Binding<Bool> {
        Binding(
            get: { party.state.hostOnlyControl },
            set: { party.setHostOnlyControl($0) }
        )
    }



    private var leaveSection: some View {
        Section {
            Button(role: .destructive) {
                Task {
                    await party.leave()
                    toast.show("Left the party", kind: .info)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Leave the party")
                    Text(PartyCopy.leaveSubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }








    private func activitySection(_ entries: [PartyActivity]) -> some View {
        Section {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 3) {
                    ForEach(entries, id: \.atMs) { entry in
                        Text(PartyFormat.activityLine(entry))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.vertical, 4)
            }
            .frame(maxHeight: 156)
            .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
        } header: {
            Text("Recent activity")
        } footer: {
            Text(PartyCopy.activityFooter)
        }
    }



    private var serverSection: some View {
        Section {
            Button {
                serverEditorPresented = true
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text("Use your own server")
                        if party.busy {
                            ProgressView().controlSize(.mini)
                        }
                    }
                    Text(party.configuredServer.nonEmpty ?? PartyCopy.serverUsingDefault)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if !party.configuredServer.isEmpty, let server = party.server {
                        ServerConnectionLine(
                            connection: server.connection,
                            isChecking: party.busy
                        )
                    }
                }
            }
            .disabled(party.inParty || party.busy)
        } header: {
            Text("Party server")
        } footer: {



            Text(party.inParty ? PartyCopy.serverLockedFooter : PartyCopy.serverFooter)
        }
    }







    func arrive(withInvite invite: String) {
        guard let parsed = JamInviteLink.shared.parseInvite(value: invite) else { return }
        arrive(code: parsed.code, server: parsed.serverUrl)
    }

    func arrive(code: String, server: String? = nil) {
        joiningServer = server
        Task { await lookUp(code: code) }
    }


    private func lookUp(code: String) async {
        do {
            let preview = try await party.preview(code: code)
            joining = preview
            sheet = .confirm
        } catch {


        }
    }
}









private struct PartyNowPlayingRow: View {

    let title: String





    let detail: String

    @State private var tick = Date()

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "music.note")
                .font(.title3)
                .frame(width: 26)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .lineLimit(1)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .task {





            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                tick = Date()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(detail)
    }
}







private struct PartyQueueRow: View {
    let track: PartyTrack
    let position: Int
    let index: Int
    let isCurrent: Bool
    let canControl: Bool
    let onPlay: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(url: track.thumbnailUrl, data: nil, side: 40)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            Button(action: onPlay) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(track.title.nonEmpty ?? "Unknown")
                            .font(.body)
                            .foregroundStyle(isCurrent ? Color.accentColor : Color.primary)
                            .lineLimit(1)
                        if isCurrent {
                            Image(systemName: "speaker.wave.2.fill")
                                .font(.caption2)
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    if let artist = track.artist.nonEmpty {
                        Text(artist)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!canControl)
            if canControl {
                Button(role: .destructive, action: onRemove) {
                    Image(systemName: "minus.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(track.title.nonEmpty ?? "track") from the queue")
            }
        }
        .padding(.vertical, 2)
    }
}


private struct PartyTransportButton: View {
    let systemImage: String
    let label: String
    let enabled: Bool
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .scaledFont(size: prominent ? 22 : 18, weight: .semibold)
                Text(label)
                    .font(.caption2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(enabled ? AnyShapeStyle(.primary) : AnyShapeStyle(.tertiary))
        .accessibilityLabel(label)
    }
}






struct MemberRow: View {
    let name: String
    let avatarUrl: String?
    let isYou: Bool
    let isHost: Bool
    let connected: Bool
    let canRemove: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            PartyAvatar(url: avatarUrl, name: name, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(name).lineLimit(1)
                    if isYou { PartyBadge(text: "You") }
                }
                if !connected {



                    Text(PartyCopy.awaySubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if isHost {
                PartyBadge(text: "Host")
            } else if canRemove {
                Button("Remove", action: onRemove)
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        var parts = [name]
        if isYou { parts.append("you") }
        if isHost { parts.append("host") }
        if !connected { parts.append("away, their slot is held") }
        return parts.joined(separator: ", ")
    }
}








struct ServerConnectionLine: View {
    let connection: ServerConnection
    let isChecking: Bool

    var body: some View {


        if let label {
            HStack(spacing: 6) {
                if isChecking {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: symbol)
                        .font(.caption)
                }
                Text(label)
                    .font(.caption)
                    .lineLimit(1)
            }
            .foregroundStyle(tint)
        }
    }





    private var label: String? {
        if connection is ServerConnectionDefaultOnline { return nil }
        if connection is ServerConnectionUnconfigured { return nil }
        if let online = connection as? ServerConnectionCustomOnline {
            return "Connected to your server · \(online.latencyMs) ms"
        }
        if let fallback = connection as? ServerConnectionCustomFallback {
            return "Your server is unreachable · Using the default server (\(fallback.latencyMs) ms)"
        }
        if connection is ServerConnectionOffline {
            return "The party server could not be reached"
        }
        if connection is ServerConnectionChecking { return "Checking…" }
        return nil
    }

    private var symbol: String {
        if connection is ServerConnectionCustomOnline { return "checkmark.icloud.fill" }
        if connection is ServerConnectionCustomFallback { return "icloud.slash.fill" }
        if connection is ServerConnectionOffline { return "icloud.slash.fill" }
        return "icloud"
    }

    private var tint: Color {
        if connection is ServerConnectionCustomOnline { return .accentColor }
        if connection is ServerConnectionCustomFallback { return .red }
        if connection is ServerConnectionOffline { return .red }
        return .secondary
    }
}




enum PartySheetCase: String, Identifiable {
    case create
    case joinCode
    case confirm
    case invite

    var id: String { rawValue }
}


struct PartyAvatar: View {
    let url: String?
    let name: String
    var size: CGFloat = 32

    var body: some View {
        ZStack {
            Circle().fill(.quaternary)
            if let url, !url.isEmpty {
                AsyncImage(url: URL(string: url)) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    initial
                }
            } else {
                initial
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay { Circle().strokeBorder(.separator.opacity(0.4), lineWidth: 0.5) }
        .accessibilityHidden(true)
    }

    private var initial: some View {
        Text(name.trimmingCharacters(in: .whitespaces).first.map(String.init)?.uppercased() ?? "?")
            .scaledFont(size: size * 0.42, weight: .medium)
            .foregroundStyle(.secondary)
    }
}

struct PartyBadge: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .foregroundStyle(Color.accentColor)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(0.16), in: RoundedRectangle(cornerRadius: 5))
    }
}

struct StepperStep: View {
    let symbol: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .foregroundStyle(enabled ? AnyShapeStyle(.primary) : AnyShapeStyle(.tertiary))
    }
}

















enum PartyCopy {




    static let codeFooter: String = "Read it out or send it. O and I aren’t used, so an “oh” is always a zero."


    static func nowPlayingTitle(_ track: PartyTrack?) -> String {
        guard let title = track?.title.nonEmpty else { return "Nothing playing yet" }
        return title
    }


    static func nowPlayingDetail(
        track: PartyTrack?,
        positionMs: Int64?,
        connection: PartyConnection,
        clockSynced: Bool,
        roundTripMs: Int64
    ) -> String {
        let position = PartyFormat.elapsed(ms: positionMs ?? 0)


        let duration = PartyFormat.elapsed(ms: track?.durationMs?.int64Value ?? 0)
        var parts: [String] = []
        if let artist = track?.artist.nonEmpty {
            parts.append(artist)
        }
        parts.append("\(position) / \(duration)")
        parts.append(connectionLine(connection: connection, clockSynced: clockSynced, roundTripMs: roundTripMs))
        return parts.joined(separator: " · ")
    }










    static func connectionLine(
        connection: PartyConnection,
        clockSynced: Bool,
        roundTripMs: Int64
    ) -> String {
        if connection != PartyConnection.live { return "Reconnecting…" }
        return clockSynced ? "In sync · \(roundTripMs) ms round trip" : "Measuring the clock…"
    }


    static func listeningHeader(members: Int, maxMembers: Int) -> String {
        "Listening · \(members) of \(maxMembers)"
    }

    static func listeningFooter(maxMembers: Int) -> String {
        "A party holds \(maxMembers) devices. Anyone in it can control the music."
    }






    static let awaySubtitle: String = "Away — their slot is held"


    static let partySizeFooter: String = "Only you can change capacity or remove listeners"
    static let hostOnlyTitle: String = "Only I control the music"
    static let hostOnlySubtitle: String = "Everyone else listens along and can pause on their own device"


    static let transportLockedFooter = "The host has taken control of the music. You can still pause on your own device."
    static func transportTitle(isPlaying: Bool) -> String {
        isPlaying ? "Pause for everyone" : "Play for everyone"
    }


    static func queueHeader(ahead: Int) -> String {
        ahead > 0 ? "Up next · \(ahead)" : "Up next"
    }
    static let queueEmpty: String = "Nothing queued. Anything you play goes here for everyone."


    static let activityFooter: String = "This session only · latest first"


    static let leaveSubtitle: String = "The others carry on listening"



    static let landingTitle: String = "Start a party"
    static let landingSubtitle: String = "Because music sounds better together. Invite friends to listen and add songs in real time, wherever they are."
    static let landingNoServer: String = "Set a party server first — there isn’t one built in."



    static let serverUsingDefault: String = "Using default server"
    static let serverFooter = "Leave this empty to use the default server. If you run your own copy, everyone in the party has to be pointed at the same one."


    static let serverLockedFooter = "Locked while you are in a party. Leaving a party you had already given the address to would leave this device holding a token for a server it no longer talks to."



    static let createSubtitle = "Pick how you’ll show up"
    static let createNicknamePlaceholder = "Nickname for this party"
    static let createSizeSubtitle = "Choose 2–10 listeners"
    static let joinCodeHint = "Six letters or digits"
    static let joinPreviewFooter = "People in the party will see your name and profile picture."
    static let inviteFooter = "Anyone with nuvx can open this link. The party server travels with it, so they do not need to have set one up."





    static func joinQuestion(hostName: String) -> String {
        let host = hostName.trimmingCharacters(in: .whitespacesAndNewlines)
        return host.isEmpty ? "Join this party?" : "Join \(host)’s party?"
    }
}

enum PartyFormat {

    static func elapsed(ms: Int64) -> String {
        let total = max(0, ms / 1000)
        return String(format: "%d:%02d", total / 60, total % 60)
    }


    static func clock(_ atMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(atMs) / 1000)
        return date.formatted(date: .omitted, time: .standard)
    }







    static func activityLine(_ entry: PartyActivity) -> String {
        let what = entry.detail.trimmingCharacters(in: .whitespacesAndNewlines)
        return "[\(clock(entry.atMs))] \(entry.by): \(what.isEmpty ? entry.action : what)"
    }
}

extension String {


    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
