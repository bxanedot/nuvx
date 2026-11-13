#if os(macOS)
import Observation
import Sparkle
import SwiftUI
import AppKit




@MainActor
@Observable
final class MacUpdateManager {
    static let shared = MacUpdateManager()

    let controller: SPUStandardUpdaterController

    static var isConfigured: Bool {
        guard let feed = Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") as? String,
              let url = URL(string: feed), url.scheme == "https", url.host != nil,
              let key = Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String,
              !key.contains("$("), let bytes = Data(base64Encoded: key), bytes.count == 32 else { return false }
        return true
    }

    private init() {
        controller = SPUStandardUpdaterController(
            startingUpdater: Self.isConfigured,
            updaterDelegate: nil,
            userDriverDelegate: nil
        )
    }

    var updater: SPUUpdater { controller.updater }

    func checkNow() {
        if Self.isConfigured {
            controller.checkForUpdates(nil)
        } else {
            NSWorkspace.shared.open(UpdateChecker.latestReleaseURL)
        }
    }
}


struct UpdateCommands: Commands {
    var body: some Commands {
        CommandGroup(after: .appInfo) {
            Button("Check for Updates…") { MacUpdateManager.shared.checkNow() }
        }
    }
}

struct MacUpdateSettings: View {
    @State private var manager = MacUpdateManager.shared
    @State private var checksAutomatically = true
    @State private var installsAutomatically = false

    var body: some View {
        Group {
            if !MacUpdateManager.isConfigured {
                Text("Automatic updates are not configured for this local build. Check the nuvx releases page for a compatible Apple installer.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Toggle("Check for updates automatically", isOn: $checksAutomatically)
            .onChange(of: checksAutomatically) { _, value in
                manager.updater.automaticallyChecksForUpdates = value
            }

            Toggle("Download and install updates automatically", isOn: $installsAutomatically)
            .onChange(of: installsAutomatically) { _, value in
                manager.updater.automaticallyDownloadsUpdates = value
            }
        }
        .disabled(!MacUpdateManager.isConfigured)
        .task {
            checksAutomatically = manager.updater.automaticallyChecksForUpdates
            installsAutomatically = manager.updater.automaticallyDownloadsUpdates
        }
    }
}
#endif
