#!/usr/bin/env bash


















set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="$ROOT/mobile"
FW="$ROOT/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-party-ui-check"

if [ ! -d "$FW" ]; then
  echo "NuvxShared.xcframework is missing — run ./scripts/build-shared-framework.sh first." >&2
  exit 1
fi




SOURCES=(
  "$ROOT/../../core/apple/src/UI/PartyStore.swift"
  "$ROOT/../../core/apple/src/UI/ListenTogetherView.swift"
  "$ROOT/../../core/apple/src/UI/ListenTogetherSheets.swift"
  "$ROOT/../../core/apple/src/UI/PartyMembersSheet.swift"
  "$ROOT/../../core/apple/src/UI/Theme.swift"
  "$ROOT/../../core/apple/src/UI/YtMusicLoginView.swift"
  "$ROOT/../../core/apple/src/UI/SignInCapture.swift"
  "$ROOT/../../core/apple/src/UI/SignInNavigation.swift"
  "$ROOT/../../core/apple/src/UI/SignInUserAgent.swift"
  "$ROOT/../../core/apple/src/App/GuardedHTTP.swift"
  "$ROOT/../../core/apple/src/PlaybackSession/PartySync.swift"
  "$ROOT/../../core/apple/src/PlaybackSession/PartySocket.swift"
  "$ROOT/../../core/apple/src/UI/Toast.swift"
  "$ROOT/../../core/apple/src/App/AuthController.swift"
  "$ROOT/../../core/apple/src/App/AuthStore.swift"
  "$ROOT/../../core/apple/src/App/Keychain.swift"
  "$ROOT/../../core/apple/src/PlaybackSession/LyricsTranslator.swift"
  "$ROOT/scripts/CheckPartyUI.swift"
)

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"




cat > "$CHECK_DIR/Stubs.swift" <<'STUBS'
import Foundation
import SwiftUI
import Observation
import NuvxShared

/// The queue entry, as the party screen needs it: an id, some labels, and a
/// duration. The real one lives with the player and carries far more than a party
/// ever sends.
struct QueueEntry: Identifiable, Hashable {
    let id: String
    var title: String
    var artist: String
    var source: String
    var thumbnailUrl: String?
    var durationText: String?
    var albumName: String?
    var artworkData: Data?
    var isLocal: Bool
    var fromAutoplay: Bool = false

    var videoId: String? { source.hasPrefix("yt:") ? String(source.dropFirst(3)) : (isLocal ? nil : id) }
    var durationSeconds: Double { 0 }
}

/// The artwork a queue row draws.
///
/// A grey box of the right size, because what this check is about is the *row*: the
/// real `ArtworkView` is in `Common.swift` alongside the asset-catalog symbols the
/// Xcode build generates, and pulling that file in would make this check depend on a
/// build step it has no business depending on.
struct ArtworkView: View {
    var url: String?
    var data: Data?
    var side: CGFloat?

    var body: some View {
        Rectangle()
            .fill(.quaternary)
            .frame(width: side, height: side)
            .accessibilityHidden(true)
    }
}

/// The app's navigation flags, as far as the party screens reach them.
///
/// The real `AppModel` lives in `NuvxApp.swift` next to the whole app, and the
/// party screens use four of its fields. Declaring the ones they need is better than
/// compiling the entire app to check a screen: a screen cannot read a flag that is not
/// here, so a rename on either side is still a compile error.
@Observable
final class AppModel {
    var scenePhase: ScenePhase = .active
    /// Set by a `nuvx://party/…` link arriving from outside the app.
    var pendingPartyInvite: String?
    /// The Listen Together screen, presented over whatever the listener was doing.
    var listenTogetherPresented = false
    /// Who is listening, from inside the player.
    var partyMembersPresented = false
}

/// The player, as far as the party binding reaches it. Every member here is called
/// by `PartySync`, and none of them does anything: the binding's decisions are
/// portable Kotlin with tests, and this check is about the screens.
@MainActor
final class PlaybackController {
    var isPlaying = false
    var position: Double = 0
    var onLocalIntent: (() -> Void)?
    private var intentSuppression = 0

    func withLocalIntentSuppressed<T>(_ body: () -> T) -> T {
        intentSuppression += 1
        defer { intentSuppression -= 1 }
        return body()
    }

    func seek(to seconds: Double) { position = seconds }
    func togglePlayPause() { isPlaying.toggle() }
    func play(_ entries: [QueueEntry], at index: Int = 0) {}
    func pauseForBackground() {}
}

/// The two Toast hooks that live in ActionMenus.swift, stubbed: pulling that
/// file in would drag every menu in the app with it, and the party screens
/// never open either of these sheets.
struct TrackLogSheet: View {
    let entry: QueueEntry

    var body: some View {
        Text("Track log")
    }
}

struct ConfirmationDialog<ConfirmLabel: View>: View {
    let title: String
    let message: String
    let destructive: Bool
    let confirmLabel: () -> ConfirmLabel
    let onConfirm: () -> Void

    init(title: String, message: String, confirm: String, onConfirm: @escaping () -> Void)
        where ConfirmLabel == Text {
        self.title = title
        self.message = message
        self.destructive = true
        self.confirmLabel = { Text(confirm) }
        self.onConfirm = onConfirm
    }

    var body: some View {
        EmptyView()
    }
}

/// The request-infrastructure AuthController touches, stubbed: PageSession and
/// PageRequestCoordinator are whole request-pipeline files, and LikeStore lives
/// next to the library actions. The party screens only need the symbols to
/// exist; `generation()` still reads the real bridge, because sign-in state is
/// what it measures.
enum PageSession {
    static func reset() {}
    static func generation() -> Int64 { AuthBridge.shared.sessionGeneration() }
}

final class LikeStore {
    static let shared = LikeStore()
    func clear() {}
}

actor PageRequestCoordinator {
    static let shared = PageRequestCoordinator()
    func invalidate(partition: String? = nil) {}
}
STUBS

xcrun swiftc \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -F "$FW" \
  -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$FW/NuvxShared.framework/Versions/A" \
  -o "$CHECK_DIR/check" \
  "${SOURCES[@]}" "$CHECK_DIR/Stubs.swift"

"$CHECK_DIR/check"
