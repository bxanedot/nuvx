import SwiftUI
import Observation
import NuvxShared

@main
struct NuvxApp: App {






    @State private var controller = AppServices.shared.controller
    @State private var appModel = AppServices.shared.appModel
    @State private var auth = AppServices.shared.auth
    @State private var toast = ToastCenter()
    @State private var party = PartyStore.shared











    @State private var partySync: PartySync?
    @Environment(\.scenePhase) private var scenePhase
    #if os(iOS)
    @UIApplicationDelegateAdaptor(DownloadAppDelegate.self) private var downloadDelegate
    #endif
    #if os(macOS)
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @AppStorage("menu_bar_player") private var menuBarPlayer = true
    #endif

    init() {



        UserDefaults.standard.set(false, forKey: "show_music_videos")
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(Theme.accent)
                #if os(macOS)


                .background(SettingsDeepLinkOpener())
                .modifier(MacWindowAccountToolbar())
                .allowsWindowActivationEvents(true)
                #endif
                .environment(controller)
                .environment(appModel)
                .environment(auth)
                .environment(toast)
                .environment(party)
                .task {
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("--verify-queue") {
                        await controller.verifyQueueBehavior()
                        return
                    }
                    #if os(iOS)
                    if ProcessInfo.processInfo.arguments.contains("--verify-restored-resume") {
                        CipherUnlockWiring.install()
                        ApplePoTokenWiring.install()
                    PlaybackCodecCapabilities.shared.setAppleDolbyAvailable(value: AppleDolbyRenderer.available)
                        let mixing = ProcessInfo.processInfo.arguments.contains("--mixing-on")
                        await controller.verifyRestoredResumeBehavior(mixing: mixing)
                        return
                    }
                    if ProcessInfo.processInfo.arguments.contains("--verify-navigation") {
                        CipherUnlockWiring.install()
                        ApplePoTokenWiring.install()
                    PlaybackCodecCapabilities.shared.setAppleDolbyAvailable(value: AppleDolbyRenderer.available)
                        await controller.verifyRapidNavigation()
                        return
                    }
                    if ProcessInfo.processInfo.arguments.contains("--verify-dolby") {
                        PlaybackCodecCapabilities.shared.setAppleDolbyAvailable(value: AppleDolbyRenderer.available)
                        await controller.verifyDolbyQueue()
                        return
                    }
                    if ProcessInfo.processInfo.arguments.contains("--verify-natural-end") {
                        await controller.verifyNaturalEndFallback()
                        return
                    }
                    if ProcessInfo.processInfo.arguments.contains("--verify-native-resume") {
                        await controller.verifyNativeResumeBehavior()
                        return
                    }
                    #endif
                    if ProcessInfo.processInfo.arguments.contains("--verify-sleep") {
                        await controller.verifySleepBehavior()
                        return
                    }
                    #endif
                    #if os(macOS)
                    MacAppDelegate.controller = controller
                    #endif
                    CipherUnlockWiring.install()
                    ApplePoTokenWiring.install()
                    PlaybackCodecCapabilities.shared.setAppleDolbyAvailable(value: AppleDolbyRenderer.available)
                    SecretStoreWiring.install()
                    ModuleEngineWiring.install()
                    PartySocket.register()




                    Task { await WhatsNewCenter.shared.checkAfterLaunch() }
                    #if os(iOS)
                    Task { await UpdateChecker.shared.pollOnce() }
                    #else
                    _ = MacUpdateManager.shared
                    #endif



                    PartyStore.install()
                    let binding = PartySync(controller: controller)
                    partySync = binding
                    party.attach(player: binding)




                    controller.onLocalIntent = { [weak binding] in
                        binding?.onLocalIntent()
                    }





                    PlaybackHandoff.shared.refresh(controller: controller)
                    AppShortcutRefresh.schedule()



                    Task {
                        await LaunchReadiness.shared.waitForContent()
                        Task { await PageRequestCoordinator.shared.removeLegacyCache() }
                        await AutomixModelStore.shared.refresh()
                        await AutomixModelStore.shared.installBundledDefaults()
                        AutomixModelStore.shared.onChanged = {
                            Task {
                                await reloadAutomixModels(playbackStarted: controller.started)
                                if controller.automixEnabled { controller.syncEngineQueueNext() }
                            }
                        }


                        AutomixModelStore.shared.updateOutdated()








                        LocalLibrary.shared.restore()
                        DownloadStore.shared.refresh()
                        Task.detached(priority: .utility) {
                            let cache = StreamFileCache.shared
                            await cache.trim()
                            let pending = await cache.entries().filter { $0.entry == nil || $0.artworkReference == nil }
                            guard !pending.isEmpty else { return }
                            let names = pending.contains(where: { $0.entry == nil }) ? ListeningStore.savedTrackMetadata() : [:]
                            for item in pending {
                                guard !Task.isCancelled else { return }
                                let entry: QueueEntry?
                                if let saved = item.entry { entry = saved }
                                else if let track = names[item.videoId] {
                                    let source = item.videoId.hasPrefix("src:") || item.videoId.hasPrefix("saavn:") ? item.videoId : "yt:" + item.videoId
                                    entry = QueueEntry(id: item.videoId, title: track.title, artist: track.artist,
                                        source: source, thumbnailUrl: track.art, albumName: track.album, isLocal: false)
                                } else { entry = nil }
                                if let entry { await cache.remember(entry, allowNetwork: false) }
                            }
                        }
                        let token = PlatformSettings.shared.getSecret(key: "discord_token") ?? ""
                        if !token.isEmpty { DiscordGateway.shared.connect(token: token) }
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    appModel.scenePhase = phase
                    LoadingMonitor.shared.setActive(phase == .active)
                    if phase == .background {





                        MusicUnderstandingAnalyzer.noteBackground()
                        controller.persistSession()



                        PlaybackHandoff.shared.refresh(controller: controller)
                        if PlatformSettings.shared.getBoolean(key: "stop_when_backgrounded", default: false) {
                            #if os(macOS)




                            let hasVisibleWindow = NSApp.windows.contains { $0.isVisible }
                            guard NSApp.isHidden || (!NSApp.isActive && hasVisibleWindow) else { return }
                            #endif
                            controller.pauseForBackground()
                        }
                    } else if phase == .active {
                        MusicUnderstandingAnalyzer.noteForeground()
                        controller.reactivateAudioSessionAfterForeground()
                    }
                }
                #if os(macOS)


                .modifier(MacTransportKeys(controller: controller))
                #endif
                .onOpenURL { url in
                    if SettingsDeepLink.handle(url) {
                        #if os(iOS)
                        appModel.settingsPresented = true
                        #endif
                        return
                    }






                    if url.absoluteString.contains("open-player") {
                        let requestedScreen = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                            .queryItems?.first(where: { $0.name == "screen" })?.value
                        if requestedScreen?.caseInsensitiveCompare("lyrics") == .orderedSame {
                            PlatformSettings.shared.putString(key: "last_player_screen", value: "LYRICS")
                        }
                        appModel.nowPlayingPresented = true
                    }






                    if JamInviteLink.shared.looksLikeInvite(value: url.absoluteString) {
                        appModel.pendingPartyInvite = url.absoluteString
                        appModel.listenTogetherPresented = true
                    }
                    _ = MusicLink.shared.submitUrl(url: url.absoluteString)
                    consumeMusicLink(appModel: appModel, controller: controller)
                }




                .onChange(of: controller.current?.id) { _, _ in
                    PlaybackHandoff.shared.refresh(controller: controller)


                    AppShortcutRefresh.schedule()
                }
                .onChange(of: controller.isPlaying) { _, _ in
                    PlaybackHandoff.shared.refresh(controller: controller)
                }
                .onContinueUserActivity(PlaybackHandoffPayload.activityType) { activity in
                    guard let payload = PlaybackHandoffPayload(userInfo: activity.userInfo) else { return }
                    PlaybackHandoff.shared.continuePlayback(payload: payload, controller: controller)
                }
        }
#if os(macOS)
        .defaultSize(width: 1280, height: 820)
        .windowResizability(.contentMinSize)
        .windowStyle(.automatic)
        .commands {
            SidebarCommands()
            PlaybackCommands(controller: controller, appModel: appModel, auth: auth, toast: toast)
            MiniPlayerCommands()
            UpdateCommands()
        }
#endif
#if os(macOS)
        WindowGroup(id: MacBrowseWindow.sceneID, for: MacBrowseWindowRoute.self) { $route in
            MacBrowseWindowView(route: route)
                .allowsWindowActivationEvents(true)
                .environment(controller)
                .environment(appModel)
                .environment(auth)
                .environment(toast)
                .environment(party)
                .preferredColorScheme(appModel.preferredScheme)
        }
        .defaultSize(width: 1000, height: 760)
        Window("Mini Player", id: MiniPlayerWindow.id) {
            MiniPlayerView()
                .allowsWindowActivationEvents(true)
                .environment(controller)
                .environment(appModel)
                .environment(auth)
                .environment(toast)
                .environment(party)
                .preferredColorScheme(appModel.preferredScheme)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultPosition(.bottomTrailing)
        .windowLevel(.floating)

        MenuBarExtra(isInserted: $menuBarPlayer) {
            MiniPlayerView(compact: true)
                .allowsWindowActivationEvents(true)
                .environment(controller)
                .environment(appModel)
                .environment(auth)
                .environment(toast)
                .environment(party)
        } label: {
            Image(systemName: controller.isPlaying ? "waveform" : "music.note")



        }
        .menuBarExtraStyle(.window)
#endif
#if os(macOS)
        Settings {
            SettingsView()
                .allowsWindowActivationEvents(true)
                .environment(controller)
                .environment(appModel)
                .environment(auth)
                .environment(toast)
                .environment(party)
                .preferredColorScheme(appModel.preferredScheme)
                .environment(\.locale, appModel.appLanguage.isEmpty ? .autoupdatingCurrent : Locale(identifier: appModel.appLanguage))
        }



        .windowToolbarStyle(.unified(showsTitle: true))
        #endif
    }
}












@MainActor
func reloadAutomixModels(playbackStarted: Bool) async {
    let paths = AutomixModelStore.shared.analyzerPaths
    let accelerator = AutomixModelStore.shared.beatCoreML
    let enabled = PlatformSettings.shared.getBoolean(key: "smart_fade_enabled", default: true)
    let efficient = PlatformSettings.shared.getString(key: "automix_performance", default: "BALANCED") == "EFFICIENT"
    let selected = AutomixModelPolicy.paths(beat: paths.beat, vocal: paths.vocal,
        accelerator: accelerator, playbackStarted: playbackStarted, enabled: enabled, efficient: efficient)
    let ready = await AutomixModelLoader.shared.configure(
        beat: selected.beat,
        vocal: selected.vocal,
        accelerator: selected.accelerator
    )
    DebugLog.shared.d(
        message: "automix models: beat=\(selected.beat) vocal=\(selected.vocal) "
            + "accelerated=\(selected.accelerator != nil) loaded=\(ready)"
    )
}



@Observable
final class AppModel {
    var scenePhase: ScenePhase = .active
    var nowPlayingPresented = false {


        didSet { if !nowPlayingPresented, !playerPath.isEmpty { playerPath = [] } }
    }

    var settingsPresented = false

    var requestedTab: Tab?







    var searchFocusTrigger = 0
    var pendingDetail: BrowseDestination?


    var pendingArtistPage: BrowseDestination?

    var playerPath: [BrowseDestination] = []
    var playlistPicker: PlaylistPickerRequest?
    var downloadManagerPresented = false
    var replayPresented = false


    var spotifyImportPresented = false
    var playlistFileImportPresented = false




    var replayInitialPage: ReplayStoryPage?

    func openReplay(at page: ReplayStoryPage? = nil) {
        replayInitialPage = page
        replayPresented = true
    }


    var automixModelsPresented = false






    var listenTogetherPresented = false





    var partyMembersPresented = false

    var pendingPartyInvite: String?
    var focusSearch = false
    var pendingSearchQuery: String?



    var iPadSearchExpanded = false



    var queueRevealRequested = false
    var lyricsRevealRequested = false
    var themeMode = PlatformSettings.shared.getString(key: "theme_mode", default: "dark")
    var appLanguage = PlatformSettings.shared.getString(key: "app_language", default: "")
    var pinLimitAlert = false
    var playlistRename: PlaylistRenameRequest?

    var preferredScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }







    @ObservationIgnored private var automixModelsAskedThisLaunch = false









    @MainActor
    func offerAutomixModelsIfNeeded() {
        guard !AutomixModelStore.shared.allInstalled else { return }
        guard !automixModelsPresented else { return }
        guard !automixModelsAskedThisLaunch else { return }



        guard !PlatformSettings.shared.getBoolean(key: "automix_models_prompt_dismissed_v2", default: false) else { return }
        automixModelsAskedThisLaunch = true
        automixModelsPresented = true
    }







    @MainActor
    func mayAskForAutomixModels() -> Bool {
        guard !AutomixModelStore.shared.allInstalled else { return false }
        guard !automixModelsAskedThisLaunch else { return false }
        automixModelsAskedThisLaunch = true
        return true
    }


    func declineAutomixModels() {
        PlatformSettings.shared.putBoolean(key: "automix_models_prompt_dismissed_v2", value: true)
    }












    @MainActor
    func openArtist(named name: String, knownId: String?) {
        Task { @MainActor in
            var browseId = knownId
            if browseId == nil {
                let hits = (try? await InnertubeSearch.shared.search(name, scope: "artists")) ?? []
                browseId = Self.artistMatch(name, in: hits)?.browseId
            }
            guard let browseId else {
                nowPlayingPresented = false
                pendingSearchQuery = name
                requestedTab = .search
                focusSearch = true
                return
            }
            showPageFromPlayer(.detail(browseId: browseId, title: name))
        }
    }


    @MainActor
    func openPage(browseId: String, title: String) {
        showPageFromPlayer(.detail(browseId: browseId, title: title))
    }









    @MainActor
    private func showPageFromPlayer(_ destination: BrowseDestination) {
        #if os(macOS)
        pendingArtistPage = destination
        #else
        if nowPlayingPresented {
            playerPath.append(destination)
        } else {
            pendingDetail = destination
        }
        #endif
    }

    static func artistMatch(_ name: String, in hits: [SearchHitDTO]) -> SearchHitDTO? {
        let wanted = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespaces)
        return hits.first { hit in
            guard hit.isBrowse, let id = hit.browseId, id.hasPrefix("UC") else { return false }
            return hit.title.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
                .trimmingCharacters(in: .whitespaces) == wanted
        }
    }



    enum Tab: Int, Hashable {
        case home = 0
        case explore = 1
        case library = 2
        case search = 3
        case radio = 4
        case libraryYouTube = 5
        case librarySongs = 6
        case libraryAlbums = 7
        case libraryArtists = 8
        case libraryDownloads = 9
        case libraryHistory = 10
        case libraryWebDav = 11
        case libraryPlaylists = 12
        case librarySubscriptions = 13
        case libraryPodcasts = 14
        case libraryOnDevice = 15


        case sidebarPlaylist = 16
    }
}

@MainActor
func consumeMusicLink(appModel: AppModel, controller: PlaybackController) {
    guard let json = MusicLink.shared.takePendingJson(),
          let data = json.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let kind = obj["kind"] as? String else { return }
    switch kind {
    case "track":
        if let id = obj["id"] as? String {
            controller.play([
                QueueEntry.youtube(videoId: id, title: "Loading…", artist: "", thumbnailUrl: nil)
            ], at: 0)
        }
    case "page":
        if let id = obj["id"] as? String {
            appModel.pendingDetail = .detail(browseId: id, title: "")
        }
    case "search":
        if let query = obj["query"] as? String {
            appModel.pendingSearchQuery = query
            appModel.requestedTab = .search
            appModel.focusSearch = true
            if obj["play"] as? Bool == true {
                Task {
                    if let hit = try? await InnertubeSearch.shared.search(query, scope: "songs").first(where: { !$0.isBrowse }) {
                        controller.play([hit.asEntry()], at: 0)
                    }
                }
            }
        }
    case "resume":
        if !controller.isPlaying { controller.togglePlayPause() }
    default:
        break
    }
}
