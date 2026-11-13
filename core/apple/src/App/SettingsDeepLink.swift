import SwiftUI
import Combine
import NuvxShared



enum SettingsDeepLink {
    static let requestKey = "settings_deep_link_request"
    static let requested = Notification.Name("SettingsDeepLinkRequested")



    @discardableResult
    static func handle(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "nuvx",
              url.host?.lowercased() == "settings" else { return false }
        let page = url.pathComponents.dropFirst().first
        let section = page.flatMap(SettingsSectionName.canonical) ?? SettingsView.SettingsSection.account.rawValue

        PlatformSettings.shared.putString(key: requestKey, value: section)
        NotificationCenter.default.post(name: requested, object: section)
        return true
    }
}




#if os(macOS)
struct SettingsDeepLinkOpener: View {
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .onReceive(NotificationCenter.default.publisher(for: SettingsDeepLink.requested)) { _ in
                openSettings()
            }
            .accessibilityHidden(true)
    }
}
#endif



enum SettingsSectionName {
    static func canonical(_ component: String) -> String? {
        let key = component.lowercased().filter { $0.isLetter || $0.isNumber }
        let aliases = [
            "localmusic": "downloads",
            "storage": "downloads",
            "performance": "advanced",
            "miscellaneous": "miscellaneous",
            "content": "contentPreferences",
            "audio": "audioQuality",
        ]
        if let section = aliases[key] { return section }
        return SettingsView.SettingsSection.allCases.first {
            $0.rawValue.lowercased().filter { $0.isLetter || $0.isNumber } == key
        }?.rawValue
    }
}
