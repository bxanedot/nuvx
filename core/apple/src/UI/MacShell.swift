#if os(macOS)
import SwiftUI
import AppKit
import NuvxShared



enum MacToolbarItemID {
    static let searchField = "nuvx.searchField"
    static let searchSource = "nuvx.searchSource"
    static let account = "nuvx.account"
    static let downloads = "nuvx.downloads"
}







enum MacInspectorKind: String, Identifiable {
    case lyrics
    case queue

    var id: String { rawValue }

    var label: String {
        self == .lyrics ? "Lyrics" : "Queue"
    }
}

extension EnvironmentValues {

    @Entry var macInspectorSelection: Binding<MacInspectorKind?> = .constant(nil)

    @Entry var macAccountSheet: Binding<Bool> = .constant(false)
}


struct MacWindowControls: ToolbarContent {
    var body: some ToolbarContent {
        ToolbarItem(id: MacToolbarItemID.account, placement: .primaryAction) {
            MacAccountToolbarButton()
        }
        ToolbarItem(id: MacToolbarItemID.downloads, placement: .primaryAction) {
            MacDownloadsButton()
        }
    }
}




struct MacPillInset: ViewModifier {
    @State private var windowWidth: CGFloat = 0

    func body(content: Content) -> some View {
        GeometryReader { proxy in






            let compact = windowWidth > 0 && windowWidth < 900
            content.safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: compact ? 8 : 0) {
                    if compact {
                        MacCompactNavigationBar()
                    }
                    PlaybackPill()
                        .frame(maxWidth: compact ? 620 : 940)
                        .padding(.horizontal, 18)
                        .padding(.bottom, 12)
                        .frame(maxWidth: .infinity)
                }
                .frame(height: compact ? 112 : 88, alignment: .bottom)
            }
            .background(WindowWidthReader { windowWidth = $0 }.frame(width: 0, height: 0))
        }
    }
}



private struct MacCompactNavigationBar: View {
    @Environment(AppModel.self) private var appModel
    @SceneStorage("nuvx.selectedTab") private var selectedTab = AppModel.Tab.home.rawValue

    private let tabs: [(AppModel.Tab, String, String)] = [
        (.home, "Home", "house"),
        (.library, "Library", "square.stack.fill"),
        (.radio, "Radio", "dot.radiowaves.left.and.right"),
        (.libraryPodcasts, "Podcasts", "microphone"),
        (.explore, "Explore", "square.grid.2x2.fill"),
        (.search, "Search", "magnifyingglass")
    ]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(tabs.indices, id: \.self) { index in
                let (tab, title, symbol) = tabs[index]
                Button {
                    appModel.requestedTab = tab
                } label: {
                    Label(title, systemImage: symbol)
                        .labelStyle(.iconOnly)
                        .frame(maxWidth: .infinity)
                        .frame(height: 34)
                        .contentShape(Capsule())
                        .foregroundStyle(selectedTab == tab.rawValue ? Color.accentColor : Color.primary)
                        .background {
                            if selectedTab == tab.rawValue { Capsule().fill(Color.accentColor.opacity(0.12)) }
                        }
                }
                .buttonStyle(.plain)
                .help(title)
                .accessibilityLabel(title)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .frame(maxWidth: 360)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.primary.opacity(0.08), lineWidth: 1))
    }
}

private struct WindowWidthReader: NSViewRepresentable {
    var onChange: (CGFloat) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = ReaderView()
        view.onChange = onChange
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView as? ReaderView)?.onChange = onChange
        (nsView as? ReaderView)?.reportWidth()
    }

    private final class ReaderView: NSView {
        var onChange: ((CGFloat) -> Void)?
        private var resizeObserver: NSObjectProtocol?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let resizeObserver { NotificationCenter.default.removeObserver(resizeObserver) }
            resizeObserver = window.map { observedWindow in
                NotificationCenter.default.addObserver(forName: NSWindow.didResizeNotification, object: observedWindow, queue: .main) { [weak self] _ in
                    self?.reportWidth()
                }
            }
            reportWidth()
        }

        func reportWidth() { onChange?(window?.frame.width ?? 0) }
    }
}



private struct MacAccountToolbarButton: View {
    @Environment(AuthController.self) private var auth
    @Environment(\.macAccountSheet) private var isPresented

    var body: some View {
        Menu {
            Button(auth.signedIn ? "Account and Profiles" : "Sign In", systemImage: "person.crop.circle") {
                isPresented.wrappedValue = true
            }
        } label: {
            Group {
                if auth.signedIn, let photo = auth.accountPhotoUrl {
                    Avatar(url: photo, name: auth.accountName ?? "Account", side: 24)
                        .clipShape(Circle())
                } else if auth.signedIn, let name = auth.accountName, !name.isEmpty {
                    Avatar(url: nil, name: name, side: 24)
                        .clipShape(Circle())
                } else {
                    Image(systemName: "person.crop.circle")
                        .font(.title3)
                }
            }
            .frame(width: 28, height: 28)
            .contentShape(Circle())
        }
        .help(auth.signedIn ? "Account and profiles" : "Sign in")
        .accessibilityLabel(auth.signedIn ? "Account and profiles" : "Sign in")
    }
}

struct MacWindowAccountToolbar: ViewModifier {
    @State private var presentsAccount = false

    func body(content: Content) -> some View {

        content
            .environment(\.macAccountSheet, $presentsAccount)
            .sheet(isPresented: $presentsAccount) {
                AccountProfileSheet().frame(minWidth: 420, minHeight: 520)
            }
    }
}







struct WindowLeadingReader: NSViewRepresentable {
    var onChange: (CGFloat) -> Void

    func makeNSView(context: Context) -> ReaderView {
        let view = ReaderView()
        view.onChange = onChange
        return view
    }

    func updateNSView(_ nsView: ReaderView, context: Context) {
        nsView.onChange = onChange
    }

    final class ReaderView: NSView {
        var onChange: ((CGFloat) -> Void)?
        private var observers: [NSObjectProtocol] = []

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            observers.forEach { NotificationCenter.default.removeObserver($0) }
            observers = []
            guard let window else { return }
            for name in [NSWindow.didEnterFullScreenNotification, NSWindow.didExitFullScreenNotification] {
                observers.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                    self?.report()
                })
            }
            report()
        }

        deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }


        private func report() {
            DispatchQueue.main.async { [weak self] in
                guard let self, self.window != nil else { return }
                self.onChange?(self.convert(self.bounds, to: nil).minX)
            }
        }
    }
}







struct UnifiedTitlebar: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { WindowView() }
    func updateNSView(_ nsView: NSView, context: Context) {}

    private final class WindowView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            window.toolbarStyle = .unified
            window.titleVisibility = .visible
        }
    }
}





struct MacFullScreenButton: View {
    @State private var isFullScreen = false

    var body: some View {
        Button(
            isFullScreen ? "Exit Full Screen" : "Enter Full Screen",
            systemImage: isFullScreen
                ? "arrow.down.right.and.arrow.up.left"
                : "arrow.up.left.and.arrow.down.right"
        ) {
            Self.playerWindow?.toggleFullScreen(nil)
        }
        .keyboardShortcut("f", modifiers: [.command, .control])
        .labelStyle(.iconOnly)
        .help(isFullScreen ? "Exit Full Screen" : "Enter Full Screen")
        .accessibilityLabel(isFullScreen ? "Exit Full Screen" : "Enter Full Screen")
        .onAppear {
            isFullScreen = Self.playerWindow?.styleMask.contains(.fullScreen) ?? false
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification)) { _ in
            isFullScreen = true
        }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { _ in
            isFullScreen = false
        }
    }
}

extension MacFullScreenButton {


    static var playerWindow: NSWindow? {
        NSApp.keyWindow ?? NSApp.mainWindow
            ?? NSApp.windows.first { $0.isVisible && $0.canBecomeMain }
    }
}






struct MacDownloadsButton: View {
    @Environment(AppModel.self) private var appModel

    var body: some View {
        @Bindable var appModel = appModel
        Button {
            appModel.downloadManagerPresented.toggle()
        } label: {
            Image(systemName: "arrow.down.circle")
        }
        .help("Downloads")
        .accessibilityLabel("Downloads")
        .popover(isPresented: $appModel.downloadManagerPresented, arrowEdge: .bottom) {
            DownloadManagerView()
                .frame(width: 420, height: 520)
        }
    }
}









struct MacTransportKeys: ViewModifier {
    let controller: PlaybackController

    func body(content: Content) -> some View {
        content
            .onKeyPress(.space, phases: .down) { press in
                guard press.modifiers.isEmpty, !MacKeyboardInput.isEditingText else { return .ignored }
                controller.togglePlayPause()
                return .handled
            }
            .onKeyPress(.leftArrow, phases: .down) { press in
                guard press.modifiers.isEmpty, !MacKeyboardInput.isEditingText else { return .ignored }
                controller.previous()
                return .handled
            }
            .onKeyPress(.rightArrow, phases: .down) { press in
                guard press.modifiers.isEmpty, !MacKeyboardInput.isEditingText else { return .ignored }
                controller.next()
                return .handled
            }
    }
}





struct MacInspectorPane: View {
    let kind: MacInspectorKind
    @Environment(PlaybackController.self) private var controller

    var body: some View {
        Group {
            switch kind {
            case .lyrics:
                MacLyricsInspector()
            case .queue:
                UpNextPane(showsCurrentTrackHeader: true)
            }
        }
        .background {



            MeshBackdrop(
                seed: controller.current?.id.hashValue ?? 0,
                artwork: controller.current?.artworkData
            )
            .ignoresSafeArea()
        }
        .environment(\.colorScheme, .dark)
    }
}





private struct MacLyricsInspector: View {
    @Environment(PlaybackController.self) private var controller
    @State private var offsetMs = LyricsOffsetBridge.offsetMs()
    @State private var showProviderPicker = false

    var body: some View {
        LyricsPane(
                lines: controller.displayedLyrics,
                originalLines: controller.lyrics,
                loading: controller.lyricsLoading,
                clock: LyricsClock(playing: controller.isPlaying) { [controller] in
                    controller.livePosition(at: $0)
                },
                fontSize: 24,
                hasTrack: controller.current != nil,
                sourceLabel: controller.lyricsSourceLabel,
                alignmentInProgress: controller.lyricsAligning,
                alignedLocally: controller.lyricsAlignedLocally,
                onChooseSource: { showProviderPicker = true },
                onSeek: { controller.seek(to: $0) },
                translator: controller.lyricsTranslator,
                trackId: controller.current?.id ?? "",
                offsetMs: offsetMs,
                trackTitle: controller.current?.title ?? "",
                trackArtist: controller.current?.artist ?? "",
                artworkData: controller.current?.artworkData,
                artworkUrl: controller.current?.thumbnailUrl
            )
        .onReceive(NotificationCenter.default.publisher(for: .lyricsOffsetChanged)) { _ in
            offsetMs = LyricsOffsetBridge.offsetMs()
        }
        .sheet(isPresented: $showProviderPicker) {
            LyricsProviderSheet()
        }
    }
}

#endif
