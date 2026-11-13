import SwiftUI
import NuvxShared
















struct PartyMembersSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(PartyStore.self) private var party
    @Environment(AppModel.self) private var appModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
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
                            }
                        )
                    }
                    if party.state.members.isEmpty {
                        Text("Nobody else is here yet.")
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(PartyCopy.listeningHeader(
                        members: party.state.members.count,
                        maxMembers: Int(party.state.maxMembers)
                    ))
                } footer: {
                    Text(PartyCopy.listeningFooter(maxMembers: Int(party.state.maxMembers)))
                }

                if let track = party.state.playback.track?.nonEmptyTitle {
                    Section("Now playing") {
                        LabeledContent(track) {
                            Text(PartyCopy.connectionLine(
                                connection: party.state.connection,
                                clockSynced: party.state.clockSynced,
                                roundTripMs: party.state.roundTripMs
                            ))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.trailing)
                        }
                    }
                }

                Section {
                    Button {
                        dismiss()
                        appModel.listenTogetherPresented = true
                    } label: {
                        Label("Manage party", systemImage: "slider.horizontal.3")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(PartyMembersSheet.title(for: party))
            #if os(macOS)
            .frame(minWidth: 420, idealWidth: 480)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Leave", role: .destructive) {
                        Task {
                            await party.leave()
                            dismiss()
                        }
                    }
                }
            }
        }
    }








    static func title(for party: PartyStore) -> String {
        let host = party.state.members.first { $0.isHost }?.name ?? ""
        let first = host.split(separator: " ").first.map(String.init) ?? ""
        return first.isEmpty ? "Listening together" : "\(first)’s Jam"
    }
}

private extension PartyTrack {

    var nonEmptyTitle: String? { title.nonEmpty }
}
