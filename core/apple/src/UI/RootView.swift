import SwiftUI
import NuvxShared
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif





struct RootView: View {
    @SceneStorage("nuvx.selectedTab") private var selection: Int = AppModel.Tab.home.rawValue
    @Environment(PlaybackController.self) private var controller
    @Environment(AppModel.self) private var appModel
    @Environment(AuthController.self) private var auth
    @Environment(ToastCenter.self) private var toast
    @State private var updateChecker = UpdateChecker.shared
    @State private var whatsNew = WhatsNewCenter.shared
    #if os(macOS)

    @State private var macInspector: MacInspectorKind?


    @State private var navigator = MacNavigator()
    @State private var macColumns: NavigationSplitViewVisibility = .all
    @State private var columnsBeforePlayer: NavigationSplitViewVisibility = .all
    private var macSide: MacSide {
        get { navigator.side }
        nonmutating set { navigator.side = newValue }
    }



    @State private var homeScroll = ScrollPosition()

    @State private var macPlaylists: [UserPlaylistDTO] = []
    #endif
    @State private var homeFeed = FeedLoader(.home)
    #if os(iOS)
    @Namespace private var nowPlayingZoom


    @Environment(\.horizontalSizeClass) private var sizeClass


    @State private var sidebarPlaylists: [UserPlaylistDTO] = []

    @State private var passingPlaylist: String?



    @State private var pageHistories = PageHistories()


    @State private var tabCustomization = TabViewCustomization()
    @State private var visitedPhoneTabs: Set<AppModel.Tab> = [.home]


    @State private var sidebarArtwork: [String: UIImage] = [:]


    @SceneStorage("nuvx.iPadLibrary") private var iPadLibraryTab: Int = AppModel.Tab.libraryYouTube.rawValue
    #endif
    @AppStorage(OnboardingView.doneKey) private var onboardingDone = false
    @State private var onboardingPresented = false

    @State private var rowSelection = RowSelection()
    @AppStorage("hide_radio_tab") private var hideRadioTab = false
    @AppStorage("hide_podcasts_tab") private var hidePodcastsTab = false

    @State private var updateDetails: AppReleaseUpdate?

    var body: some View {
        rootAppearance
    }

    private var rootInitialRouting: some View {
        shell
        .environment(\.rowSelection, rowSelection)
        #if os(iOS)
        .overlay {
            AppPrompts(
                selectedTab: selection,
                held: !onboardingDone || onboardingPresented || updateDetails != nil
            )
        }
        #endif
        .task {


            guard !onboardingDone else { return }
            try? await Task.sleep(for: .milliseconds(700))
            if auth.signedIn || !controller.queue.isEmpty {
                onboardingDone = true
            } else {
                onboardingPresented = true
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $onboardingPresented) { OnboardingView() }
        #else
        .sheet(isPresented: $onboardingPresented) { OnboardingView() }
        #endif
        .onChange(of: appModel.requestedTab) { _, requested in
            guard let requested else { return }
            selection = resolvedTab(requested).rawValue
            #if os(macOS)
            macSide = MacSide(tab: requested)
            #endif
            appModel.requestedTab = nil
        }
        .onChange(of: hideRadioTab) { _, hidden in
            if hidden && AppModel.Tab(rawValue: selection) == .radio {
                selection = AppModel.Tab.home.rawValue
                #if os(macOS)
                macSide = .home
                #endif
            }
        }
        .onChange(of: hidePodcastsTab) { _, hidden in
            if hidden && AppModel.Tab(rawValue: selection) == .libraryPodcasts {
                selection = AppModel.Tab.home.rawValue
                #if os(macOS)
                macSide = .home
                #endif
            }
        }
    }

    private var rootPrimarySheets: some View {
        rootInitialRouting
        .sheet(isPresented: Binding(
            get: { auth.loginPresented },
            set: { auth.loginPresented = $0 }
        )) {
            NavigationStack {
                YtMusicLoginView(
                    onCaptured: { session, done in
                        auth.accept(session) { accepted in
                            if accepted { auth.loginPresented = false }
                            done(accepted)
                        }
                    },
                    onDismiss: { auth.loginPresented = false }
                )
            }
            #if os(macOS)
            .frame(minWidth: 720, minHeight: 640)
            #endif
        }
        .sheet(item: Binding(
            get: { appModel.playlistPicker },
            set: { appModel.playlistPicker = $0 }
        )) { req in
            PlaylistPickerView(request: req)
        }
        #if os(iOS)
        .sheet(isPresented: Binding(
            get: { appModel.downloadManagerPresented },
            set: { appModel.downloadManagerPresented = $0 }
        )) {
            DownloadManagerView()
        }
        #endif
        .sheet(isPresented: Binding(
            get: { appModel.replayPresented },
            set: { appModel.replayPresented = $0 }
        )) {
            ReplayView()
        }



    }

    private var rootSecondarySheets: some View {
        rootPrimarySheets
        .sheet(isPresented: Binding(
            get: { appModel.listenTogetherPresented },
            set: { appModel.listenTogetherPresented = $0 }
        )) {
            NavigationStack { ListenTogetherView() }
        }
        .sheet(isPresented: Binding(
            get: { appModel.partyMembersPresented },
            set: { appModel.partyMembersPresented = $0 }
        )) {
            PartyMembersSheet()
                .environment(PartyStore.shared)
        }



        .sheet(isPresented: Binding(
            get: { appModel.automixModelsPresented },
            set: { appModel.automixModelsPresented = $0 }
        )) {
            AutomixModelsSheet(onDecline: { appModel.declineAutomixModels() })
        }
        .sheet(item: Binding(
            get: { appModel.pendingDetail },
            set: { appModel.pendingDetail = $0 }
        )) { dest in
            NavigationStack {
                if case .detail(let id, let title) = dest {
                    DetailView(browseId: id, initialTitle: title)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Close") { appModel.pendingDetail = nil }
                            }
                        }
                }
            }
            #if os(macOS)
            .frame(minWidth: 720, minHeight: 640)
            #endif
        }
    }

    private var rootAlerts: some View {
        rootSecondarySheets
        .alert("Only 5 playlists can be pinned", isPresented: Binding(
            get: { appModel.pinLimitAlert },
            set: { appModel.pinLimitAlert = $0 }
        )) {
            Button("OK", role: .cancel) {}
        }
        .alert("Rename Playlist", isPresented: Binding(
            get: { appModel.playlistRename != nil },
            set: { if !$0 { appModel.playlistRename = nil } }
        )) {
            RenamePlaylistAlert()
        }
        #if os(iOS)
        .alert("A nuvx update is available", isPresented: Binding(
            get: { updateChecker.available != nil },
            set: { if !$0 { updateChecker.dismiss() } }
        )) {
            Button("View Update") {
                updateDetails = updateChecker.available
                updateChecker.dismiss()
            }
            Button("Later", role: .cancel) { updateChecker.dismiss() }
        } message: {
            Text("nuvx \(updateChecker.available?.identity.label ?? "") is available. Update using the same method you used to install Nuvx. View Update shows what changed since your version.")
        }
        .sheet(item: $updateDetails) { release in UpdateSheet(release: release) }
        #endif
        .sheet(isPresented: Binding(
            get: { !whatsNew.entries.isEmpty },
            set: { if !$0 { whatsNew.entries = [] } }
        )) {
            WhatsNewSheet(entries: whatsNew.entries)
        }
    }

    private var rootPlatformRouting: some View {
        rootAlerts
        .onChange(of: tabBinding.wrappedValue) { old, new in
            #if os(iOS)



            if new == .tab(.search), sizeClass != .regular { appModel.focusSearch = true }
            #else
            if new == .search { appModel.focusSearch = true }
            #endif
            _ = old
        }
        #if os(macOS)
        .frame(minWidth: 860, minHeight: 600)



        .onChange(of: appModel.queueRevealRequested) { _, requested in
            guard requested, !appModel.nowPlayingPresented else { return }
            macInspector = .queue
            appModel.queueRevealRequested = false
        }
        .onChange(of: appModel.lyricsRevealRequested) { _, requested in
            guard requested, !appModel.nowPlayingPresented else { return }
            macInspector = .lyrics
            appModel.lyricsRevealRequested = false
        }





        .onChange(of: appModel.pendingArtistPage) { _, destination in
            guard case .detail(let browseId, let title) = destination else { return }
            appModel.pendingArtistPage = nil
            let route = PushRoute(id: UUID()) {
                AnyView(DetailView(browseId: browseId, initialTitle: title))
            }
            if appModel.nowPlayingPresented {
                navigator.pushFromPlayer(route)
                appModel.nowPlayingPresented = false
            } else {
                navigator.path.append(route)
            }
        }
        .onAppear {
            navigator.onReturnToPlayer = { [appModel] in appModel.nowPlayingPresented = true }
        }
        #endif
    }

    private var rootAppearance: some View {
        rootPlatformRouting
        .preferredColorScheme(appModel.preferredScheme)
        .background(Theme.Colors.background)
        .tint(Theme.accent)
        .environment(\.locale, appModel.appLanguage.isEmpty ? .autoupdatingCurrent : Locale(identifier: appModel.appLanguage))
        .overlay {



            ToastHost()
        }






        .onChange(of: controller.lastError) { _, message in
            guard let message, !message.isEmpty else { return }
            toast.show(message, kind: .failure)
        }
    }

    @ViewBuilder
    private var shell: some View {
        #if os(macOS)
        macShell
        #else
        contentStack
        .task(id: "\(auth.sessionEpoch)-\(sizeClass == .regular)") { await loadSidebarPlaylists() }
        .onReceive(NotificationCenter.default.publisher(for: .libraryPlaylistsChanged)) { _ in
            Task { await loadSidebarPlaylists() }
        }
        .onChange(of: selection) { _, raw in
            if let tab = AppModel.Tab(rawValue: raw), Self.isLibrarySection(tab) {
                iPadLibraryTab = raw
            }
        }
        .environment(\.nowPlayingZoomNamespace, nowPlayingZoom)
        .modifier(NowPlayingTakeover(
            isPresented: Binding(
                get: { appModel.nowPlayingPresented },
                set: { appModel.nowPlayingPresented = $0 }
            ),
            zoomNamespace: nowPlayingZoom
        ))
        #endif
    }

    #if os(iOS)
    private var contentStack: some View {


        ZStack {
            tabShell
                .opacity(appModel.settingsPresented ? 0 : 1)
                .allowsHitTesting(!appModel.settingsPresented)
                .accessibilityHidden(appModel.settingsPresented)
            if appModel.settingsPresented {
                NavigationStack {
                    SettingsView(embedded: true)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button { appModel.settingsPresented = false } label: {
                                    Label("Back", systemImage: "chevron.left")
                                }
                            }
                        }
                }
            }
        }
    }

    private func loadSidebarPlaylists() async {
        guard auth.signedIn, sizeClass == .regular else {
            sidebarPlaylists = []
            return
        }
        var seen = Set<String>()
        let owned = await LibraryActions.userPlaylists().filter { seen.insert($0.playlistId).inserted }


        sidebarPlaylists = (seen.contains(SidebarArtwork.likedMusicId) ? [] : [
            UserPlaylistDTO(playlistId: SidebarArtwork.likedMusicId, title: "Liked Music", subtitle: "", thumbnailUrl: nil)
        ]) + owned
        sidebarArtwork[SidebarArtwork.likedMusicId] = SidebarArtwork.likedMusic
        for playlist in sidebarPlaylists where sidebarArtwork[playlist.playlistId] == nil {
            guard let url = playlist.thumbnailUrl,
                  let art = await SidebarArtwork.load(url) else { continue }
            sidebarArtwork[playlist.playlistId] = art
        }
    }


    private static func isLibrarySection(_ tab: AppModel.Tab) -> Bool {
        tab.rawValue >= AppModel.Tab.libraryYouTube.rawValue &&
            tab != .libraryPodcasts && tab != .radio
    }


    @ViewBuilder
    private func librarySection(_ section: AppModel.Tab) -> some View {
        Group {
            switch section {
            case .libraryPlaylists: LibraryView(lockedSection: .playlists)
            case .librarySongs: LibraryView(lockedSection: .songs)
            case .libraryAlbums: LibraryView(lockedSection: .albums)
            case .libraryArtists: LibraryView(lockedSection: .artists)
            case .librarySubscriptions: LibraryView(lockedSection: .subscriptions)
            case .libraryPodcasts: PodcastsFeedView()
            case .libraryOnDevice, .libraryDownloads: LibraryView(lockedSection: .ondevice)
            case .libraryHistory: LibraryView(lockedSection: .history)
            case .libraryWebDav: LibraryView(lockedSection: .webdav)
            default: LibraryView(lockedSection: .youtube)
            }
        }
        .historyStack(pageHistories.navigator(RootPage.tab(section)))
        .modifier(iPadPlaybackChrome())
    }


    private static let librarySections: [(tab: AppModel.Tab, title: LocalizedStringKey, symbol: String)] = [
        (.libraryYouTube, "Recent", "clock.arrow.circlepath"),
        (.librarySongs, "Songs", "music.note"),
        (.libraryAlbums, "Albums", "square.stack"),
        (.libraryArtists, "Artists", "music.mic"),
        (.libraryPlaylists, "Playlists", "music.note.list"),
        (.librarySubscriptions, "Subscriptions", "bell"),
        (.libraryPodcasts, "Podcasts", "radio"),
        (.libraryOnDevice, "On Device", "externaldrive"),
        (.libraryHistory, "History", "clock"),
    ]
    #endif

    #if os(macOS)







    private var macShell: some View {






        macSplit
            .onChange(of: appModel.nowPlayingPresented) { _, presented in
                if presented {
                    columnsBeforePlayer = macColumns
                    macColumns = .detailOnly
                } else {
                    macColumns = columnsBeforePlayer
                }
            }
        .task(id: auth.sessionEpoch) { await loadMacPlaylists() }
        .onReceive(NotificationCenter.default.publisher(for: .libraryPlaylistsChanged)) { _ in
            Task { await loadMacPlaylists() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playlistDeleted)) { note in



            guard let deleted = note.userInfo?["browseId"] as? String else { return }
            if case .playlist(let shown, _) = macSide, shown == deleted {
                navigator.replaceSide(.playlists)
                selection = MacSide.playlists.tab.rawValue
            }
        }
        .onAppear {

            navigator.replaceSide(MacSide(tab: resolvedTab(AppModel.Tab(rawValue: selection) ?? .home)))
        }
    }

    private var macSplit: some View {
        NavigationSplitView(columnVisibility: $macColumns) {
            macSidebar
                .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 320)
        } detail: {
            ZStack {
            HStack(spacing: 0) {







                NavigationStack(path: Binding(
                    get: { appModel.nowPlayingPresented ? [] : navigator.path },
                    set: { if !appModel.nowPlayingPresented { navigator.path = $0 } }
                )) {
                    macDetail
                        .modifier(MacPillInset())
                        .navigationDestination(for: PushRoute.self) { route in
                            route.make()
                                .modifier(MacPillInset())

                                .navigationBarBackButtonHidden(true)
                        }
                }
                .environment(\.inBrowseStack, true)




                if let kind = macInspector {
                    Divider()
                    MacInspectorPane(kind: kind)
                        .frame(width: 340)
                        .transition(.move(edge: .trailing))
                }
            }
            .animation(.easeInOut(duration: 0.2), value: macInspector)
            .environment(\.macInspectorSelection, $macInspector)


            .opacity(appModel.nowPlayingPresented ? 0 : 1)
            .allowsHitTesting(!appModel.nowPlayingPresented)
            .accessibilityHidden(appModel.nowPlayingPresented)
            if appModel.nowPlayingPresented {
                NowPlayingView()
            }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .overlay(alignment: .topLeading) {
            MacWindowToolbar(isPlayerPresented: appModel.nowPlayingPresented)
                .frame(width: 0, height: 0)
        }
        .toolbar {
            if !appModel.nowPlayingPresented {
                ToolbarItem(placement: .navigation) {
                    MacHistoryButtons(navigator: navigator)
                }



                if !macSide.hostsOwnToolbarControls || !navigator.path.isEmpty {
                    MacWindowControls()
                }
            }
        }
    }

    private var macSidebar: some View {
        List(selection: macSideBinding) {
            Section {
                sideRow(.home, "Home", "house")
                if !hideRadioTab { sideRow(.radio, "Radio", "dot.radiowaves.left.and.right") }
                if !hidePodcastsTab { sideRow(.podcasts, "Podcasts", "microphone") }
                sideRow(.explore, "Explore", "square.grid.2x2")

                sideRow(.search, "Search", "magnifyingglass")
            }
            Section("Library") {
                sideRow(.recent, "Recent", "clock.arrow.circlepath")
                sideRow(.songs, "Songs", "music.note")
                sideRow(.albums, "Albums", "square.stack")
                sideRow(.artists, "Artists", "music.mic")
                sideRow(.playlists, "Playlists", "music.note.list")
                sideRow(.subscriptions, "Subscriptions", "bell")
                sideRow(.onDevice, "On Device", "externaldrive")
                sideRow(.history, "History", "clock")
                if WebDavStore.shared.isConfigured {
                    sideRow(.webdav, "WebDAV", "cloud")
                }
            }


            if !macPlaylists.isEmpty {
                Section("Playlists") {
                    ForEach(macPlaylists) { playlist in


                        Label {
                            Text(playlist.title)
                        } icon: {
                            ArtworkView(url: playlist.thumbnailUrl, data: nil, side: 20)
                                .frame(width: 20, height: 20)
                                .clipShape(.rect(cornerRadius: 4, style: .continuous))
                        }
                        .tag(MacSide.playlist(id: playlist.browseId, title: playlist.title))
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("nuvx")
    }

    private func sideRow(_ side: MacSide, _ title: LocalizedStringKey, _ icon: String) -> some View {
        Label {
            Text(title)
        } icon: {


            Image(systemName: icon)
                .foregroundStyle(macSide == side ? Color.white : Color.primary)
        }
        .tag(side)
    }

    @ViewBuilder
    private var macDetail: some View {
        switch macSide {
        case .home:
            HomeView(feed: homeFeed, scrollPosition: $homeScroll)
        case .explore:
            ExploreView()
        case .radio:
            RadioView()
        case .recent:
            LibraryView(lockedSection: .youtube)
        case .songs:
            LibraryView(lockedSection: .songs, hostsWindowControls: navigator.path.isEmpty)
        case .albums:
            LibraryView(lockedSection: .albums, hostsWindowControls: navigator.path.isEmpty)
        case .artists:
            LibraryView(lockedSection: .artists, hostsWindowControls: navigator.path.isEmpty)
        case .playlists:
            LibraryView(lockedSection: .playlists, hostsWindowControls: navigator.path.isEmpty)
        case .subscriptions:
            LibraryView(lockedSection: .subscriptions)
        case .podcasts:
            PodcastsFeedView()
        case .onDevice:
            LibraryView(lockedSection: .ondevice)
        case .history:
            LibraryView(lockedSection: .history)
        case .webdav:
            LibraryView(lockedSection: .webdav)
        case .search:
            SearchView(
                showsMacToolbar: navigator.path.isEmpty && !appModel.nowPlayingPresented,
                hostsWindowControls: navigator.path.isEmpty && !appModel.nowPlayingPresented
            )
        case .playlist(let id, let title):
            DetailView(browseId: id, initialTitle: title)
        }
    }

    private var macSideBinding: Binding<MacSide> {
        Binding(
            get: { macSide },
            set: { side in


                if side == .search { appModel.focusSearch = true }
                macSide = side
                selection = side.tab.rawValue
            }
        )
    }

    private func loadMacPlaylists() async {
        guard auth.signedIn else {
            macPlaylists = []
            return
        }


        var seen = Set<String>()
        macPlaylists = await LibraryActions.userPlaylists().filter { seen.insert($0.playlistId).inserted }
    }
    #endif

    #if os(iOS)


    enum RootPage: Hashable {
        case tab(AppModel.Tab)
        case playlist(String)
    }

    @ViewBuilder
    private var tabShell: some View {
        if sizeClass != .regular {
            phoneShell
        } else {
            tabletShell
        }
    }

    private var phoneShell: some View {
        ZStack {
            ForEach(NuvxNavigationBar.destinations, id: \.tab) { destination in
                if visitedPhoneTabs.contains(destination.tab) &&
                    !(hideRadioTab && destination.tab == .radio) &&
                    !(hidePodcastsTab && destination.tab == .libraryPodcasts) {
                    phonePage(destination.tab)
                        .historyStack(pageHistories.navigator(RootPage.tab(destination.tab)))
                        .opacity(selection == destination.tab.rawValue ? 1 : 0)
                        .allowsHitTesting(selection == destination.tab.rawValue)
                        .accessibilityHidden(selection != destination.tab.rawValue)
                }
            }
        }
        .background(Theme.Colors.background)
        .safeAreaInset(edge: .bottom, spacing: 8) {
            NuvxNavigationBar(
                selection: $selection,
                onSelect: { tab in
                    Self.noteSearchRetap(tab, current: AppModel.Tab(rawValue: selection) ?? .home, appModel: appModel)
                    visitedPhoneTabs.insert(tab)
                    selection = tab.rawValue
                },
                hideRadio: hideRadioTab,
                hidePodcasts: hidePodcastsTab
            )
            .padding(.horizontal, 19)
            .padding(.bottom, 6)
        }
        .onAppear {
            let selected = resolvedTab(AppModel.Tab(rawValue: selection) ?? .home)
            selection = selected.rawValue
            visitedPhoneTabs.insert(selected)
        }
        .onChange(of: selection) { _, raw in
            if let tab = AppModel.Tab(rawValue: raw) { visitedPhoneTabs.insert(resolvedTab(tab)) }
        }
    }

    @ViewBuilder
    private func phonePage(_ tab: AppModel.Tab) -> some View {
        switch tab {
        case .home: HomeView(feed: homeFeed)
        case .library: LibraryView()
        case .radio: RadioView()
        case .libraryPodcasts: PodcastsFeedView()
        case .explore: ExploreView()
        case .search: SearchView()
        default: LibraryView()
        }
    }

    private var tabletShell: some View {
        TabView(selection: tabBinding) {
            if sizeClass == .regular {




                Tab("Home", systemImage: "house.fill", value: RootPage.tab(.home)) {
                    HomeView(feed: homeFeed)
                        .historyStack(pageHistories.navigator(RootPage.tab(.home)))
                        .modifier(iPadPlaybackChrome())
                }
                Tab("Explore", systemImage: "square.grid.2x2.fill", value: RootPage.tab(.explore)) {
                    ExploreView()
                        .historyStack(pageHistories.navigator(RootPage.tab(.explore)))
                        .modifier(iPadPlaybackChrome())
                }
                if !hideRadioTab {
                    Tab("Radio", systemImage: "dot.radiowaves.left.and.right", value: RootPage.tab(.radio)) {
                        RadioView()
                            .historyStack(pageHistories.navigator(RootPage.tab(.radio)))
                            .modifier(iPadPlaybackChrome())
                    }
                }
                TabSection("Library") {
                    ForEach(Self.librarySections.filter {
                        $0.tab != .libraryPodcasts || !hidePodcastsTab
                    }, id: \.tab) { section in
                        Tab(section.title, systemImage: section.symbol, value: RootPage.tab(section.tab)) {
                            librarySection(section.tab)
                        }
                    }
                    if WebDavStore.shared.isConfigured {
                        Tab("WebDAV", systemImage: "cloud", value: RootPage.tab(.libraryWebDav)) {
                            librarySection(.libraryWebDav)
                        }
                    }
                }
                Tab("Search", systemImage: "magnifyingglass", value: RootPage.tab(.search), role: .search) {
                    SearchView()
                        .historyStack(pageHistories.navigator(RootPage.tab(.search)))
                        .modifier(iPadPlaybackChrome())
                }



                TabSection("Playlists") {
                    ForEach(sidebarPlaylists) { playlist in
                        Tab(value: RootPage.playlist(playlist.playlistId)) {


                            Color.clear
                        } label: {
                            Label {
                                Text(playlist.title)
                            } icon: {
                                if let art = sidebarArtwork[playlist.playlistId] {
                                    Image(uiImage: art).renderingMode(.original)
                                } else {
                                    Image(systemName: "music.note.list")
                                }
                            }
                        }
                        .customizationID("nuvx.sidebar.playlist.\(playlist.playlistId)")
                        .tabPlacement(.sidebarOnly)
                    }
                }
                .customizationID("nuvx.sidebar.playlists")
                .defaultVisibility(.hidden, for: .tabBar)
            } else {
                Tab("Home", systemImage: "house.fill", value: RootPage.tab(.home)) {
                    HomeView(feed: homeFeed)
                }
                Tab("Explore", systemImage: "square.grid.2x2.fill", value: RootPage.tab(.explore)) {
                    ExploreView()
                }
                Tab("Library", systemImage: "square.stack.fill", value: RootPage.tab(.library)) {
                    LibraryView()
                }
                Tab("Search", systemImage: "magnifyingglass", value: RootPage.tab(.search), role: .search) {
                    SearchView()
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewCustomization($tabCustomization)
        .onAppear {
            SidebarSelectionGuard.shared.onPlaylist = { index in
                if sidebarPlaylists.indices.contains(index) { openSidebarPlaylist(sidebarPlaylists[index].playlistId) }
            }
        }
        .tabViewSidebarBottomBar { IPadSidebarFooter() }
        .modifier(PlaybackPillMount())
    }
    #endif

    #if os(iOS)
    private var tabBinding: Binding<RootPage> {
        Binding(
            get: {
                if let passing = passingPlaylist { return .playlist(passing) }
                return .tab(resolvedTab(AppModel.Tab(rawValue: selection) ?? .home))
            },
            set: { page in
                guard case .tab(let newTab) = page else {




                    if case .playlist(let id) = page {
                        passingPlaylist = id
                        Task { @MainActor in
                            passingPlaylist = nil
                            openSidebarPlaylist(id)
                        }
                    }
                    return
                }
                if sizeClass == .regular, newTab == .library {
                    selection = iPadLibraryTab
                    return
                }
                Self.noteSearchRetap(newTab, current: resolvedTab(AppModel.Tab(rawValue: selection) ?? .home), appModel: appModel)
                selection = newTab.rawValue
            }
        )
    }



    private func openSidebarPlaylist(_ id: String) {
        guard let playlist = sidebarPlaylists.first(where: { $0.playlistId == id }) else { return }
        pageHistories.navigator(RootPage.tab(.libraryPlaylists)).path = [PushRoute(id: UUID()) {
            AnyView(DetailView(browseId: playlist.browseId, initialTitle: playlist.title))
        }]
        selection = AppModel.Tab.libraryPlaylists.rawValue
    }
    #else
    private var tabBinding: Binding<AppModel.Tab> {
        Binding(
            get: { resolvedTab(AppModel.Tab(rawValue: selection) ?? .home) },
            set: { newTab in
                Self.noteSearchRetap(newTab, current: resolvedTab(AppModel.Tab(rawValue: selection) ?? .home), appModel: appModel)
                selection = newTab.rawValue
            }
        )
    }
    #endif











    private static func noteSearchRetap(_ tab: AppModel.Tab, current: AppModel.Tab, appModel: AppModel) {
        if tab == .search, tab == current { appModel.searchFocusTrigger &+= 1 }
    }

    private func resolvedTab(_ tab: AppModel.Tab) -> AppModel.Tab {
        if hideRadioTab && tab == .radio { return .home }
        if hidePodcastsTab && tab == .libraryPodcasts { return .home }
        #if os(macOS)
        if tab == .library { return .libraryYouTube }
        return tab
        #else
        if sizeClass == .regular {
            if tab == .library { return AppModel.Tab(rawValue: iPadLibraryTab) ?? .libraryYouTube }

            if tab == .sidebarPlaylist { return .libraryPlaylists }
            return tab
        }


        return Self.isLibrarySection(tab) ? .library : tab
        #endif
    }
}

#if os(macOS)


enum MacSide: Hashable {
    case home, explore, radio, recent, songs, albums, artists, playlists
    case subscriptions, podcasts, onDevice, history, webdav, search
    case playlist(id: String, title: String)



    init(tab: AppModel.Tab) {
        switch tab {
        case .home: self = .home
        case .explore: self = .explore
        case .radio: self = .radio
        case .library, .libraryYouTube: self = .recent
        case .librarySongs: self = .songs
        case .libraryAlbums: self = .albums
        case .libraryArtists: self = .artists
        case .libraryPlaylists, .sidebarPlaylist: self = .playlists
        case .librarySubscriptions: self = .subscriptions
        case .libraryPodcasts: self = .podcasts


        case .libraryOnDevice, .libraryDownloads: self = .onDevice
        case .libraryHistory: self = .history
        case .libraryWebDav: self = .webdav
        case .search: self = .search
        }
    }



    var tab: AppModel.Tab {
        switch self {
        case .home: .home
        case .explore: .explore
        case .radio: .radio
        case .recent: .libraryYouTube
        case .songs: .librarySongs
        case .albums: .libraryAlbums
        case .artists: .libraryArtists
        case .playlists, .playlist: .libraryPlaylists
        case .subscriptions: .librarySubscriptions
        case .podcasts: .libraryPodcasts
        case .onDevice: .libraryOnDevice
        case .history: .libraryHistory
        case .webdav: .libraryWebDav
        case .search: .search
        }
    }

    var hostsOwnToolbarControls: Bool {
        switch self {
        case .search, .songs, .albums, .artists, .playlists:
            true
        default:
            false
        }
    }
}




private struct MacWindowToolbar: NSViewRepresentable {
    let isPlayerPresented: Bool

    func makeNSView(context: Context) -> MacWindowToolbarView { MacWindowToolbarView() }
    func updateNSView(_ nsView: MacWindowToolbarView, context: Context) {
        nsView.setPlayerPresented(isPlayerPresented)
    }
}

private final class MacWindowToolbarView: NSView {
    private var isPlayerPresented = false
    private var wasPlayerPresented = false
    private var normalItems: [NSToolbarItem.Identifier: Bool] = [:]
    private var observers: [NSObjectProtocol] = []
    private var updateScheduled = false
    private var isConfiguring = false

    func setPlayerPresented(_ presented: Bool) {
        guard isPlayerPresented != presented else { return }
        isPlayerPresented = presented
        scheduleUpdate()
        for delay in [0.05, 0.15, 0.3] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                self?.configureToolbar()
            }
        }
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        guard let window else { return }
        for name in [NSToolbar.willAddItemNotification, NSToolbar.didRemoveItemNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                guard let self, !self.isConfiguring, (note.object as? NSToolbar) === self.window?.toolbar else { return }
                self.scheduleUpdate()
            })
        }
        observers.append(NotificationCenter.default.addObserver(forName: NSWindow.didResizeNotification, object: window, queue: .main) { [weak self] _ in
            self?.scheduleUpdate()
        })
        scheduleUpdate()
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func scheduleUpdate() {
        guard window != nil, !updateScheduled else { return }
        updateScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.updateScheduled = false
            self.configureToolbar()
        }
    }

    private func configureToolbar() {
        guard let toolbar = window?.toolbar, !isConfiguring else { return }
        isConfiguring = true
        defer { isConfiguring = false }
        if isPlayerPresented {


            for item in toolbar.items where (normalItems[item.itemIdentifier] != nil || Self.isSidebarToggle(item))
                && !Self.isSpacing(item)
                && !Self.isWindowControl(item) {
                item.isHidden = true
            }
        } else {
            if wasPlayerPresented {
                for item in toolbar.items {
                    if let hidden = normalItems[item.itemIdentifier] { item.isHidden = hidden }
                }
            }





            if let toggleIndex = toolbar.items.firstIndex(where: Self.isSidebarToggle),
               let dividerIndex = toolbar.items.firstIndex(where: {
                   $0 is NSTrackingSeparatorToolbarItem
               }), toggleIndex > dividerIndex {
                let identifier = toolbar.items[toggleIndex].itemIdentifier
                toolbar.removeItem(at: toggleIndex)
                toolbar.insertItem(withItemIdentifier: identifier, at: dividerIndex)
            }
            if let toggle = toolbar.items.first(where: Self.isSidebarToggle) {
                toggle.visibilityPriority = .user
            }
            normalItems = Dictionary(toolbar.items.map { ($0.itemIdentifier, $0.isHidden) }, uniquingKeysWith: { first, _ in first })
        }
        wasPlayerPresented = isPlayerPresented
    }

    private static func isSpacing(_ item: NSToolbarItem) -> Bool {
        item.itemIdentifier == .flexibleSpace || item.itemIdentifier == .space
            || item.itemIdentifier.rawValue.localizedCaseInsensitiveContains("spacer")
            || item.itemIdentifier.rawValue.localizedCaseInsensitiveContains("space")
    }

    private static func isWindowControl(_ item: NSToolbarItem) -> Bool {
        item.itemIdentifier.rawValue == MacToolbarItemID.account
            || item.itemIdentifier.rawValue == MacToolbarItemID.downloads
    }

    private static func isSidebarToggle(_ item: NSToolbarItem) -> Bool {
        item.itemIdentifier == .toggleSidebar
            || item.itemIdentifier.rawValue.hasSuffix(".toggleSidebar")
    }
}
#endif







struct PlaybackPillMount: ViewModifier {
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass



    @State private var keyboardUp = false
    #endif

    func body(content: Content) -> some View {
        #if os(iOS)
        if sizeClass == .regular {







            content
        } else if #available(iOS 26.0, *) {



            accessory(content.tabBarMinimizeBehavior(.onScrollDown))
                .overlay(alignment: .bottom) {
                    if keyboardUp {
                        PlaybackPill()


                            .chromeGlass(in: Capsule())
                            .padding(.horizontal, 10)
                            .padding(.bottom, 8)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
                    withAnimation(.smooth(duration: 0.25)) { keyboardUp = true }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                    withAnimation(.smooth(duration: 0.25)) { keyboardUp = false }
                }
        } else {
            fallbackMount(content)
        }
        #else


        content
        #endif
    }

    #if os(iOS)



    @available(iOS 26.0, *)
    @ViewBuilder
    private func accessory(_ content: some View) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: !keyboardUp) {
                PlaybackAccessory()
            }
        } else {
            content.tabViewBottomAccessory {
                PlaybackAccessory()
            }
        }
    }

    private func fallbackMount(_ content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            PlaybackPill()


                .frame(maxWidth: 720)
                .padding(.horizontal, 10)
                .padding(.bottom, 8)
                .frame(maxWidth: .infinity)
        }
    }
    #endif
}

#if os(iOS)


@available(iOS 26.0, *)
private struct PlaybackAccessory: View {
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        PlaybackPill(inline: placement == .inline)
    }
}





struct iPadPlaybackChrome: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(AppModel.self) private var appModel

    func body(content: Content) -> some View {
        pill(content)
            .background { if sizeClass == .regular { SidebarSelectionGuardInstaller() } }

            .toolbar(appModel.iPadSearchExpanded ? .hidden : .automatic, for: .tabBar)
    }

    @ViewBuilder
    private func pill(_ content: Content) -> some View {


        if sizeClass == .regular {
            content.safeAreaInset(edge: .bottom) {
                PlaybackPill()
                    .frame(maxWidth: 620)
                    .padding(.horizontal, 10)
                    .padding(.bottom, 8)
                    .frame(maxWidth: .infinity)
            }
        } else {
            content
        }
    }
}
#endif

#if os(iOS)


struct NowPlayingTakeover: ViewModifier {
    @Binding var isPresented: Bool
    var zoomNamespace: Namespace.ID
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        Group {
            if sizeClass == .regular {



                content.fullScreenCover(isPresented: $isPresented) {
                    PlayerNavigationStack()
                        .navigationTransition(.zoom(sourceID: NowPlayingZoom.sourceID, in: zoomNamespace))
                }
            } else {
                content.sheet(isPresented: $isPresented) {
                    PlayerNavigationStack()
                        .modifier(NowPlayingSheetSizing())
                        .navigationTransition(.zoom(sourceID: NowPlayingZoom.sourceID, in: zoomNamespace))
                }
            }
        }
    }
}



private struct PlayerNavigationStack: View {
    @Environment(AppModel.self) private var appModel

    var body: some View {
        @Bindable var appModel = appModel
        NavigationStack(path: $appModel.playerPath) {
            NowPlayingView()
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: BrowseDestination.self) { destination in
                    switch destination {
                    case .detail(let browseId, let title):
                        DetailView(browseId: browseId, initialTitle: title)
                    }
                }
        }
    }
}

private struct NowPlayingSheetSizing: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass

    @ViewBuilder
    func body(content: Content) -> some View {
        if sizeClass == .regular {
            content
                .presentationDetents([.large])
                .presentationSizing(.page)
                .presentationDragIndicator(.visible)
        } else {
            content
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(24)
        }
    }
}

enum NowPlayingZoom {
    static let sourceID = "now-playing"
}

private struct NowPlayingZoomNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    var nowPlayingZoomNamespace: Namespace.ID? {
        get { self[NowPlayingZoomNamespaceKey.self] }
        set { self[NowPlayingZoomNamespaceKey.self] = newValue }
    }
}
#endif

#if os(iOS)
private struct AppPrompts: View {
    @Environment(\.openURL) private var openURL
    @AppStorage("radio_intro_shown") private var radioIntroShown = false
    @AppStorage("podcasts_intro_shown") private var podcastsIntroShown = false
    @AppStorage("hide_radio_tab") private var hideRadioTab = false
    @AppStorage("hide_podcasts_tab") private var hidePodcastsTab = false
    @AppStorage("support_prompts_off") private var supportPromptsOff = false
    @AppStorage("discord_invite_joined") private var discordInviteJoined = false

    let selectedTab: Int
    let held: Bool

    @State private var launchPromptLoaded = false
    @State private var supportPrompt = 0
    @State private var activePrompt: Notice?
    @State private var deferredPrompt: Notice?
    @State private var skipIntroFollowup = false
    @State private var discordPromptDismissed = false

    private enum Notice {
        case radioIntro
        case radioFollowup
        case podcastsIntro
        case podcastsFollowup
        case supportTip
        case supportStar
        case discordInvite

        var title: String {
            switch self {
            case .radioIntro, .radioFollowup: "Live Radio"
            case .podcastsIntro, .podcastsFollowup: "Podcasts"
            case .supportTip: "Enjoying nuvx?"
            case .supportStar: "Like nuvx?"
            case .discordInvite: "Join Discord Server"
            }
        }

        var message: String {
            switch self {
            case .radioIntro:
                "Live stations from around the world. Not your thing? You can hide this tab."
            case .radioFollowup:
                "You can hide the Radio tab anytime in Settings > Miscellaneous."
            case .podcastsIntro:
                "Shows and episodes from YouTube Music. Not your thing? You can hide this tab."
            case .podcastsFollowup:
                "You can hide the Podcasts tab anytime in Settings > Miscellaneous."
            case .supportTip:
                "nuvx is free and made by one person. A small tip keeps it going."
            case .supportStar:
                "A star on GitHub helps more people find it."
            case .discordInvite:
                "Meet other Nuvx listeners, share feedback, and get app updates."
            }
        }

        var isHeldSensitive: Bool {
            switch self {
            case .supportTip, .supportStar, .discordInvite: true
            case .radioIntro, .radioFollowup, .podcastsIntro, .podcastsFollowup: false
            }
        }
    }

    var body: some View {
        Color.clear
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
            .alert(activePrompt?.title ?? "", isPresented: promptIsPresented) {
                if let activePrompt {
                    switch activePrompt {
                    case .radioIntro:
                        Button("Keep Radio") {}
                        Button("Hide Radio tab", role: .destructive) {
                            hideRadioTab = true
                            radioIntroShown = true
                            skipIntroFollowup = true
                        }
                    case .radioFollowup:
                        Button("OK", role: .cancel) {}
                    case .podcastsIntro:
                        Button("Keep Podcasts") {}
                        Button("Hide Podcasts tab", role: .destructive) {
                            hidePodcastsTab = true
                            podcastsIntroShown = true
                            skipIntroFollowup = true
                        }
                    case .podcastsFollowup:
                        Button("OK", role: .cancel) {}
                    case .supportTip:
                        Button("Tip on Ko-fi") {
                            open("https://ko-fi.com/bxane")
                        }
                        Button("Not now", role: .cancel) {}
                        Button("Don’t show again", role: .destructive) {
                            supportPromptsOff = true
                        }
                    case .supportStar:
                        Button("Star on GitHub") {
                            open("https://github.com/bxanedot/nuvx")
                        }
                        Button("Not now", role: .cancel) {}
                        Button("Don’t show again", role: .destructive) {
                            supportPromptsOff = true
                        }
                    case .discordInvite:
                        Button("Join Server") {
                            discordInviteJoined = true
                            open("https://discord.gg/h4Wsu824Uf")
                        }
                        Button("Not Now", role: .cancel) {}
                    }
                }
            } message: {
                if let activePrompt {
                    Text(activePrompt.message)
                }
            }
            .task {
                guard !launchPromptLoaded else { return }
                let launchCount = PlatformSettings.shared.getInt(key: "launch_count", default: 0) &+ 1
                PlatformSettings.shared.putInt(key: "launch_count", value: launchCount)
                if !supportPromptsOff, launchCount % 12 == 8 {
                    supportPrompt = (launchCount / 12) % 2 == 0 ? 1 : 2
                }
                launchPromptLoaded = true
                presentNextPrompt()
            }
            .onChange(of: selectedTab) { _, _ in presentNextPrompt() }
            .onChange(of: held) { _, isHeld in
                if isHeld {
                    if let activePrompt, activePrompt.isHeldSensitive {
                        deferredPrompt = activePrompt
                        self.activePrompt = nil
                    }
                } else {
                    presentNextPrompt()
                }
            }
    }

    private var promptIsPresented: Binding<Bool> {
        Binding(
            get: { activePrompt != nil },
            set: { isPresented in
                if !isPresented { dismissActivePrompt() }
            }
        )
    }

    private func presentNextPrompt() {
        guard launchPromptLoaded, activePrompt == nil else { return }

        if selectedTab == AppModel.Tab.radio.rawValue, !radioIntroShown {
            activePrompt = .radioIntro
            return
        }
        if selectedTab == AppModel.Tab.libraryPodcasts.rawValue, !podcastsIntroShown {
            activePrompt = .podcastsIntro
            return
        }
        guard !held else { return }

        if let deferredPrompt {
            activePrompt = deferredPrompt
            self.deferredPrompt = nil
        } else if supportPrompt != 0 {
            activePrompt = supportPrompt == 1 ? .supportTip : .supportStar
        } else if !discordInviteJoined, !discordPromptDismissed {
            activePrompt = .discordInvite
        }
    }

    private func dismissActivePrompt() {
        guard let dismissed = activePrompt else { return }
        activePrompt = nil

        var followup: Notice?
        switch dismissed {
        case .radioIntro:
            if skipIntroFollowup {
                radioIntroShown = true
                skipIntroFollowup = false
            } else {
                followup = .radioFollowup
            }
        case .radioFollowup:
            radioIntroShown = true
        case .podcastsIntro:
            if skipIntroFollowup {
                podcastsIntroShown = true
                skipIntroFollowup = false
            } else {
                followup = .podcastsFollowup
            }
        case .podcastsFollowup:
            podcastsIntroShown = true
        case .supportTip, .supportStar:
            supportPrompt = 0
        case .discordInvite:
            discordPromptDismissed = true
        }

        Task { @MainActor in
            await Task.yield()
            if let followup {
                activePrompt = followup
            } else {
                presentNextPrompt()
            }
        }
    }

    private func open(_ address: String) {
        guard let url = URL(string: address) else { return }
        openURL(url)
    }
}
#endif
