import SwiftUI
import NuvxShared
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif


extension SettingsView {
    func buildAccountRows(_ rows: inout SettingsRows) {
        rows.row("Account & Integrations", "sign in", "login", "google", "account", "profile",
                 "discord", "rich presence", "scrobbling", "last.fm", "lastfm", "listenbrainz",
                 "spotify", "sign out", "integrations", "primary artist", "album artist",
                 "track artist", "scrobble artist") {
            PushLink {
                AccountIntegrationsView(loginPresented: $loginPresented, discordPresented: $discordPresented)
            } label: {
                SettingsLine(
                    glyph: .person,
                    title: "Account & Integrations",
                    subtitle: accountSubtitle
                ) {
                    EmptyView()
                }
            }
        }
    }

    private var accountSubtitle: String {



        if let reason = auth.sessionUnavailableReason { return reason }
        if let email = auth.accountEmail, !email.isEmpty { return email }
        if let name = auth.accountName, !name.isEmpty { return name }
        return auth.signedIn ? "Signed in" : "Not signed in"
    }






    var listenTogetherSubtitle: String {
        let party = PartyStore.shared
        if party.inParty { return "In a party · \(party.code)" }
        return "Share a code and play the same music, in time"
    }





    var partyBadge: String? {
        let party = PartyStore.shared
        let count = party.state.members.count
        return party.inParty && count > 1 ? "\(count)" : nil
    }


}
