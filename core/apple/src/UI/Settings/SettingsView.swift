import SwiftUI
import NuvxShared
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif



struct SettingsView: View {
    var embedded: Bool = false
    @Environment(\.dismiss) private var dismiss
    @Environment(PlaybackController.self) var controller
    @Environment(AuthController.self) var auth
    @Environment(AppModel.self) var appModel
    @AppStorage("hide_radio_tab") var hideRadioTab = false
    @AppStorage("hide_podcasts_tab") var hidePodcastsTab = false
    @AppStorage("hide_recents") var hideRecents = false
    @AppStorage("hide_home_videos") var hideHomeVideos = false
    @AppStorage("hide_top_picks") var hideTopPicks = false
    @AppStorage("hide_home_moods") var hideHomeMoods = false

    @State var crossfade = Int(PlatformSettings.shared.getInt(key: "crossfade_seconds", default: 0))
    @AppStorage("dolby_atmos") var dolbyAtmos = true
    #if os(macOS)
    @AppStorage("menu_bar_player") var menuBarPlayer = true
    @AppStorage("mac_single_click_play") var singleClickToPlay = true
    #endif
    @State var spatial = PlatformSettings.shared.getBoolean(key: "spatial_audio", default: false)
    @State var automix = PlatformSettings.shared.getBoolean(key: "smart_fade_enabled", default: true)
    @State var automixSequence = PlatformSettings.shared.getBoolean(key: "automix_smart_sequence", default: true)
    @State var trimEdges = PlatformSettings.shared.getBoolean(key: "trim_edge_silence", default: false)
    @State var skipNonMusic = PlatformSettings.shared.getBoolean(key: "skip_non_music", default: false)
    @State var skipSilence = PlatformSettings.shared.getBoolean(key: "skip_silence", default: false)
    @State var wifiQuality = PlatformSettings.shared.getString(key: "audio_quality_wifi", default: "LOSSLESS")
    @State var cellQuality = PlatformSettings.shared.getString(key: "audio_quality_cellular", default: "LOSSLESS")
    @State var downloadQuality = PlatformSettings.shared.getString(key: "download_quality", default: "LOSSLESS")
    @State var wifiOnlyDownloads = PlatformSettings.shared.getBoolean(key: "wifi_only_downloads", default: true)
    @State var nerdStats = PlatformSettings.shared.getBoolean(key: "show_nerd_stats", default: false)
    @State var canvas = PlatformSettings.shared.getBoolean(key: "animated_canvas", default: true)
    @State var canvasCellular = PlatformSettings.shared.getBoolean(key: "canvas_over_cellular", default: false)
    @State var reduceBlur = PlatformSettings.shared.getBoolean(key: "reduce_dynamic_blur", default: false)
    @State var update = UpdateChecker.shared
    @State var reduceAnimation = PlatformSettings.shared.getBoolean(key: "reduce_animation", default: false)
    @State var fullBleed = PlatformSettings.shared.getBoolean(key: "full_bleed_artwork", default: true)
    @AppStorage("lyrics_blur") var lyricsBlur = true
    @AppStorage("player_lyric_line") var playerLyricLine = true
    @AppStorage("show_music_videos") var showMusicVideos = false
    @State var videoOverCellular = PlatformSettings.shared.getBoolean(key: "video_over_cellular", default: false)
    @AppStorage("translation_language") var translationLanguage = ""
    @State var syncedLyrics = PlatformSettings.shared.getBoolean(key: "synced_lyrics", default: true)
    @State var convertVideo = PlatformSettings.shared.getBoolean(key: "convert_video_to_audio", default: true)
    @State var swipeNext = PlatformSettings.shared.getBoolean(key: "swipe_to_play_next", default: false)
    @State var dontRepeat = PlatformSettings.shared.getBoolean(key: "dont_repeat_suggestions", default: false)
    @State var hideVolume = PlatformSettings.shared.getBoolean(key: "hide_volume_bar", default: false)
    @State var hideSongStatus = PlatformSettings.shared.getBoolean(key: "hide_song_status", default: false)
    @State var outputPcm = PlaybackController.migrateOutputPcmMode()
    @State var matchSourceRate = PlatformSettings.shared.getBoolean(key: "match_source_sample_rate", default: true)
    @State var bitPerfect = PlatformSettings.shared.getBoolean(key: "bit_perfect_output", default: false)
    @State var preferUsbDac = PlatformSettings.shared.getBoolean(key: "prefer_usb_dac", default: false)
    @State var loudness = PlaybackController.storedLoudnessMode() != .off
    @State var loudnessModeSelection = PlatformSettings.shared.getString(key: "loudness_mode", default: "TRACK") == "ALBUM" ? "ALBUM" : "TRACK"
    @State var loudnessOffOnSpeaker = PlatformSettings.shared.getBoolean(key: "loudness_off_on_speaker", default: true)
    @State var automixPerf = PlatformSettings.shared.getString(key: "automix_performance", default: "BALANCED")
    @State var highPerf = PlatformSettings.shared.getBoolean(key: "high_performance_mode", default: false)
    @State var refreshRate: Int = Int(PlatformSettings.shared.getInt(key: "performance_refresh_rate", default: 60))
    @State var showPerfWarning = false
    @State var localFolderName: String? = SettingsView.storedLocalFolderName()
    @State var pickingFolder = false
    @State var filterNonMusic = PlatformSettings.shared.getBoolean(key: "filter_non_music_audio", default: true)
    @State var exportDownloads = PlatformSettings.shared.getBoolean(key: "export_downloads", default: false)
    @State var smartAlign = PlatformSettings.shared.getBoolean(key: "smart_version_alignment", default: true)
    @State var legacyMesh = PlatformSettings.shared.getBoolean(key: "legacy_mesh_gradient", default: false)
    @State var preferMusicOnly = PlatformSettings.shared.getBoolean(key: "prefer_music_only", default: false)
    @State var confirmImport = false
    @State var theme = PlatformSettings.shared.getString(key: "theme_mode", default: "dark")
    @State var lyricsSources = LyricsSourceNames.normalizeList(
        PlatformSettings.shared.getString(key: "lyrics_sources", default: LyricsSourceNames.defaultEnabled)
    )
    @State var lyricsSourceOrder = LyricsSourceNames.normalizeList(
        PlatformSettings.shared.getString(key: "lyrics_source_order", default: LyricsSourceNames.defaultEnabled)
    )
    @State var playbackSpeed = Double(PlatformSettings.shared.getFloat(key: "playback_speed", default: 1))
    @State var stopBackground = PlatformSettings.shared.getBoolean(key: "stop_when_backgrounded", default: false)
    @State var stopMusicOnClose = PlatformSettings.shared.getBoolean(key: "stop_music_on_close", default: false)
    @State var hidePlayerStatusBar = PlatformSettings.shared.getBoolean(key: "hide_status_bar_in_player", default: false)
    @State var showQueueHistory = PlatformSettings.shared.getBoolean(key: "show_queue_history", default: false)
    @State var artistSignatures = PlatformSettings.shared.getBoolean(key: "artist_signatures_enabled", default: true)
    @State var artistSignaturesWikimediaUrl = PlatformSettings.shared.getString(key: "artist_signatures_wikimedia_url", default: "")
    @State var syllableSync = PlatformSettings.shared.getBoolean(key: "prioritize_syllable_sync", default: true)
    @State var language = PlatformSettings.shared.getString(key: "app_language", default: "")
    @State var spotifyCookie = PlatformSettings.shared.getString(key: "spotify_spdc_token", default: "")
    @State var replayGenres = PlatformSettings.shared.getBoolean(key: "replay_genres", default: true)
    @State var exportPresented = false
    @State private var importPresented = false
    @State var backupNote: String?
    @State var cacheLimitMB = SettingsView.cacheLimitMegabytes()
    @State var cacheUnlimited = SettingsView.cacheIsUnlimited()
    @AppStorage(SettingsDeepLink.requestKey) var requestedSettingsPage = ""



    @AppStorage("show_cached_songs_in_library") var showCachedSongs = false
    @State var loginPresented = false
    @State var discordPresented = false


    @State private var offerModelsSheet = false
    @State var songCacheNote: String?
    @State var imageCacheNote: String?


    @State private var search = ""
    @State private var deepLinkedSettingsSection: SettingsSection?
    #if os(macOS)
    @State private var settingsHistory = PathNavigator()
    @State private var selectedMacSettingsSection: SettingsSection? = .sources
    #endif

    var metered: Bool { NetworkQuality.shared.metered }

    var body: some View {
        Group {
            if embedded {
                settingsForm
                    .navigationTitle("Settings")
                    .searchable(text: $search, prompt: "Search settings")
            } else {
                #if os(macOS)
                macSettingsSplitView
                    .navigationTitle("Settings")

                    .searchable(text: $search, placement: .toolbar, prompt: "Search")
                    .environment(\.inBrowseStack, true)
                .toolbar {
                    ToolbarItem(placement: .navigation) {
                        MacHistoryButtons(navigator: settingsHistory)
                    }
                }
                .background(UnifiedTitlebar())
                #else
                NavigationStack {
                    settingsForm
                        .navigationTitle("Settings")
                        .searchable(text: $search, prompt: "Search settings")
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { dismiss() }
                            }
                        }
                }
                #endif
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, idealWidth: 620, minHeight: 720)
        #endif
        .alert("Higher refresh rate?", isPresented: $showPerfWarning) {
            Button("Cancel", role: .cancel) {}
            Button("Turn On") { highPerf = true }
        } message: {
            Text("Holding a high refresh rate keeps the display working harder the whole time nuvx is open, and the battery drains faster for it. Turn it on anyway?")
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                LocalLibrary.shared.scanPicked(url)
                localFolderName = SettingsView.storedLocalFolderName()
                    ?? url.lastPathComponent
            }
        }
        .onAppear {




            if !PerformanceTuner.supportedRates.contains(refreshRate) { refreshRate = 60 }
            PerformanceTuner.shared.apply(highPerformance: highPerf, refreshRateHz: refreshRate)
            localFolderName = SettingsView.storedLocalFolderName()
        }
        .preferredColorScheme(appModel.preferredScheme)
        .onChange(of: dolbyAtmos) { _, value in AppSettings.shared.setDolbyAtmos(value: value) }
        .onChange(of: loudnessOffOnSpeaker) { _, value in
            AppSettings.shared.setLoudnessOffOnSpeaker(value: value)
            controller.updateLoudnessMode(controller.loudnessMode)
        }
        .onChange(of: hidePlayerStatusBar) { _, value in AppSettings.shared.setHideStatusBarInPlayer(value: value) }
        .onChange(of: showQueueHistory) { _, value in AppSettings.shared.setShowQueueHistory(value: value) }
        .onChange(of: stopMusicOnClose) { _, value in AppSettings.shared.setStopMusicOnClose(value: value) }
        .onChange(of: videoOverCellular) { _, value in
            PlatformSettings.shared.putBoolean(key: "video_over_cellular", value: value)
            VideoPlayback.shared.refreshNetworkPolicy()
        }
        .onChange(of: lyricsBlur) { _, value in AppSettings.shared.setLyricsBlur(value: value) }
        .onChange(of: translationLanguage) { _, value in AppSettings.shared.setTranslationLanguage(value: value) }
        .sheet(isPresented: $loginPresented) { loginSheet }
        .sheet(isPresented: $discordPresented) { DiscordLoginView() }




        .sheet(isPresented: $offerModelsSheet) { AutomixModelsSheet() }
        .modifier(SettingsPlaybackPersist(
            controller: controller,
            crossfade: $crossfade,
            spatial: $spatial,
            automix: $automix,
            automixSequence: $automixSequence,
            automixPerf: $automixPerf,
            skipSilence: $skipSilence,
            playbackSpeed: $playbackSpeed,
            onAutomixEnabled: {
                if appModel.mayAskForAutomixModels() { offerModelsSheet = true }
            }
        ))
        .modifier(SettingsQualityPersist(
            controller: controller,
            wifiQuality: $wifiQuality,
            cellQuality: $cellQuality,
            downloadQuality: $downloadQuality,
            wifiOnlyDownloads: $wifiOnlyDownloads,
            cacheLimitMB: $cacheLimitMB,
            outputPcm: $outputPcm,
            matchSourceRate: $matchSourceRate,
            bitPerfect: $bitPerfect,
            preferUsbDac: $preferUsbDac,
            loudness: $loudness,
            loudnessModeSelection: $loudnessModeSelection
        ))
        .modifier(SettingsExperiencePersist(
            controller: controller,
            appModel: appModel,
            nerdStats: $nerdStats,
            canvas: $canvas,
            canvasCellular: $canvasCellular,
            reduceBlur: $reduceBlur,
            reduceAnimation: $reduceAnimation,
            fullBleed: $fullBleed,
            syncedLyrics: $syncedLyrics,
            convertVideo: $convertVideo,
            swipeNext: $swipeNext,
            dontRepeat: $dontRepeat,
            hideVolume: $hideVolume,
            legacyMesh: $legacyMesh,
            preferMusicOnly: $preferMusicOnly,
            theme: $theme
        ))
        .modifier(SettingsAdvancedPersist(
            controller: controller,
            smartAlign: $smartAlign,
            hideSongStatus: $hideSongStatus,
            exportDownloads: $exportDownloads,
            filterNonMusic: $filterNonMusic,
            highPerf: $highPerf,
            refreshRate: $refreshRate,
            reduceAnimation: $reduceAnimation,
            reduceBlur: $reduceBlur
        ))
        .modifier(SettingsExtrasPersist(
            lyricsSources: $lyricsSources,
            lyricsSourceOrder: $lyricsSourceOrder,
            stopBackground: $stopBackground,
            syllableSync: $syllableSync,
            language: $language,
            spotifyCookie: $spotifyCookie,
            replayGenres: $replayGenres,
            appModel: appModel
        ))



        .alert("Replace this device's data?", isPresented: $confirmImport) {
            Button("Cancel", role: .cancel) {}
            Button("Choose File") { importPresented = true }
        } message: {
            Text("Importing a backup replaces the settings and listening history on this device. This cannot be undone.")
        }
        .fileExporter(
            isPresented: $exportPresented,
            document: SettingsBackupFile(text: NuvxBackup.exportText()),
            contentType: .json,
            defaultFilename: NuvxBackup.suggestedName()
        ) { result in
            if case .success = result { backupNote = "Settings exported" }
        }
        .fileImporter(isPresented: $importPresented, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) {
                NuvxBackup.importText(text)
                backupNote = "Imported — reopen Settings to see them"
            }
        }
    }







    enum SettingsSection: String, CaseIterable, Identifiable {
        case sources, audioQuality, playback, downloads, contentPreferences, appearance
        case account, advanced, yourData, miscellaneous, about

        var id: String { rawValue }

        var title: String {
            switch self {
            case .sources: return "Sources"
            case .account: return "Account & Integrations"
            case .audioQuality: return "Sound & Quality"
            case .downloads: return "Downloads & Storage"
            case .contentPreferences: return "Content Preferences"
            case .playback: return "Playback"
            case .appearance: return "Appearance"
            case .yourData: return "Your Data & Backup"
            case .advanced: return "Advanced Options"
            case .miscellaneous: return "Miscellaneous"
            case .about: return "About & Updates"
            }
        }

        var systemImage: String {
            switch self {
            case .sources: return "square.stack.3d.up"
            case .account: return "person.crop.circle"
            case .audioQuality: return "waveform"
            case .downloads: return "arrow.down.circle"
            case .contentPreferences: return "slider.horizontal.3"
            case .playback: return "play.circle"
            case .appearance: return "paintbrush"
            case .yourData: return "externaldrive"
            case .advanced: return "gearshape.2"
            case .miscellaneous: return "ellipsis.circle"
            case .about: return "info.circle"
            }
        }
    }




    struct SettingsRowEntry: Identifiable {
        let id: Int
        let title: String
        let content: AnyView
    }











    struct SettingsRows {
        let query: String
        let sectionTitle: String
        private(set) var entries: [SettingsRowEntry] = []








        mutating func row<V: View>(
            _ title: String,
            _ keywords: String...,
            @ViewBuilder content: () -> V
        ) {
            row(title, keywords: keywords, content: content)
        }


        mutating func row<V: View>(
            _ title: String,
            keywords: [String],
            @ViewBuilder content: () -> V
        ) {
            let terms = ([sectionTitle, title] + keywords).joined(separator: Self.separator)


            guard SettingsSearchBridge.shared.matches(
                query: query,
                title: sectionTitle,
                termsCsv: terms
            ) else { return }
            entries.append(SettingsRowEntry(id: entries.count, title: title, content: AnyView(content())))
        }

        mutating func append(_ entry: SettingsRowEntry) {
            entries.append(SettingsRowEntry(id: entries.count, title: entry.title, content: entry.content))
        }



        private static let separator = "\u{1F}"
    }


    private struct BuiltSection: Identifiable {
        let section: SettingsSection
        let rows: [SettingsRowEntry]
        var id: String { section.id }
    }







    private func builtSections() -> [BuiltSection] {
        SettingsSection.allCases.compactMap { section in
            let rows = rows(for: section)
            return rows.isEmpty ? nil : BuiltSection(section: section, rows: rows)
        }
    }

    func rows(for section: SettingsSection) -> [SettingsRowEntry] {
        var rows = SettingsRows(query: search, sectionTitle: section.title)
        switch section {
        case .sources:
            rows.row("Sources", keywords: AudioSettingsItem.sources.keywords + ["addon", "source"]) {
                PushLink { SourcesView() } label: { Label("Manage Sources", systemImage: "square.stack.3d.up") }
            }
        case .account:
            buildAccountRows(&rows)
            appendMiscRows(&rows, titles: ["Listen together", "Spotify Canvas"])
        case .audioQuality: buildAudioQualityRows(&rows)
        case .downloads:
            buildDownloadRows(&rows)
            buildLocalMusicRows(&rows)
            buildStorageRows(&rows)
        case .contentPreferences: buildContentPreferenceRows(&rows)
        case .playback:
            buildPlaybackRows(&rows)
            appendMiscRows(&rows, titles: ["Play Next on Swipe", "Don’t Repeat Songs in Current Session", "Show Queue History", "Stop When Backgrounded", "Stop Music on Close"])
        case .appearance:
            buildAppearanceRows(&rows)
            appendMiscRows(&rows, titles: ["Hide Volume Bar", "Hide Song Status", "Hide Status Bar in Player", "App Language"])
        case .advanced:
            buildAdvancedRows(&rows)
            buildAutomixAdvancedRows(&rows)
            buildPerformanceRows(&rows)
        case .yourData: buildYourDataRows(&rows)
        case .miscellaneous:
            appendMiscRows(&rows, titles: ["Hide Radio Tab", "Hide Podcasts Tab", "App icon"])
        case .about: buildAboutRows(&rows)
        }
        return rows.entries
    }

    private func appendMiscRows(_ rows: inout SettingsRows, titles: Set<String>) {
        var legacy = SettingsRows(query: rows.query, sectionTitle: rows.sectionTitle)
        buildMiscellaneousRows(&legacy)
        for entry in legacy.entries where titles.contains(entry.title) { rows.append(entry) }
    }





    @ViewBuilder
    private func footer(for section: SettingsSection) -> some View {
        switch section {
        case .audioQuality:
            Text("Source Quality is the request. Current Output shows the decoded audio, device format and processing actually in use. Technical Audio & DAC contains output-specific options.")
        case .downloads:
            Text("Exported downloads live in the shared Music folder. Private ones stay inside nuvx's own storage.")
        case .playback:
            Text("Gapless stays on at 0s. Automix picks timing per track; crossfade sets the manual blend length.")
        case .advanced:
            Text("Analysis and display performance options can increase CPU use and battery consumption. Version alignment controls how a source switch resumes the recording.")
        case .about:
            Text("nuvx \(update.currentVersion)")
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
        case .sources, .account, .contentPreferences, .appearance, .yourData, .miscellaneous:
            EmptyView()
        }
    }

    #if os(macOS)
    private var macSettingsSplitView: some View {
        let visibleSections = search.isEmpty ? SettingsSection.allCases : builtSections().map(\.section)
        return SettingsMacSplitView(
            sections: visibleSections,
            selection: $selectedMacSettingsSection,
            sidebarLabel: { section in
                NavigationLink(value: section) {
                    Label(section.title, systemImage: section.systemImage)
                }
            },
            detail: { section in
                NavigationStack(path: Bindable(settingsHistory).path) {
                    settingsSectionPage(section)
                        .navigationTitle("Settings")
                        .navigationDestination(for: PushRoute.self) { route in
                            route.make().navigationBarBackButtonHidden(true)
                        }
                        .onAppear { consumeMacSettingsDeepLinkIfNeeded() }
                        .onChange(of: requestedSettingsPage) { _, _ in
                            consumeMacSettingsDeepLinkIfNeeded()
                        }
                }
            }
        )
        .onChange(of: selectedMacSettingsSection) { _, _ in
            settingsHistory = PathNavigator()
        }
        .onChange(of: search) { _, _ in
            settingsHistory = PathNavigator()
            let visibleSections = search.isEmpty ? SettingsSection.allCases : builtSections().map(\.section)
            if let selectedMacSettingsSection,
               visibleSections.contains(selectedMacSettingsSection) {
                return
            }
            selectedMacSettingsSection = visibleSections.first
        }
    }

    private func consumeMacSettingsDeepLinkIfNeeded() {
        guard !requestedSettingsPage.isEmpty,
              let rawValue = SettingsSectionName.canonical(requestedSettingsPage),
              let section = SettingsSection(rawValue: rawValue)
        else { return }
        requestedSettingsPage = ""
        search = ""
        settingsHistory = PathNavigator()
        selectedMacSettingsSection = section
    }
    #endif

    private func settingsSectionForm(_ section: SettingsSection) -> some View {
        let entries = rows(for: section)
        let cleanupTitles: Set<String> = ["Clear Song Cache", "Clear Image Cache"]
        return Form {
            Section {
                ForEach(entries.filter { !cleanupTitles.contains($0.title) }) { row in row.content }
            } header: { Text(section.title) } footer: { if search.isEmpty { footer(for: section) } }
            if section == .downloads {
                Section {
                    ForEach(entries.filter { cleanupTitles.contains($0.title) }) { row in row.content }
                } header: { Text("Free Storage") } footer: {
                    Text("Cache actions remove temporary files. Data import and backup are on Your Data & Backup.")
                }
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder private func settingsSectionPage(_ section: SettingsSection) -> some View {
        if section == .sources { SourcesView() }
        else { settingsSectionForm(section).navigationTitle(section.title) }
    }

    private func sectionSummary(_ section: SettingsSection) -> String {
        switch section {
        case .sources:
            let state = SourceListState.shared
            guard state.hasLoaded else { return "Addons, source order, renewal and your libraries" }
            let enabled = state.configs.filter { $0.enabled }.count
            let attention = state.loadError != nil || state.configs.contains { config in
                config.enabled && (state.health[config.id]?.name == "rejected" || state.health[config.id]?.name == "unreachable")
            }
            return "\(enabled) enabled" + (attention ? " · needs attention" : "")
        case .audioQuality: return "Requested quality, decoded format, effects and device output"
        case .playback: return "Queue, transitions, speed and background playback"
        case .downloads: return "Offline quality, local folders and temporary storage"
        case .contentPreferences: return "Choose which recommendations and sections appear on Home"
        case .appearance: return "Player, lyrics, language and visual preferences"
        case .account: return "Sign-in, sharing and connected services"
        case .advanced: return "Models, analysis, performance and version alignment"
        case .yourData: return "Listening history, export and importing a backup"
        case .miscellaneous: return "Navigation tabs and app icon"
        case .about: return "Installed build, latest release and installation"
        }
    }

    private var settingsForm: some View {
        let sections = search.isEmpty ? [] : builtSections()
        return ScrollViewReader { proxy in
            Form {
                if search.isEmpty {
                    Section {
                        ForEach(SettingsSection.allCases) { section in
                            PushLink { settingsSectionPage(section) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Label(section.title, systemImage: section.systemImage)
                                    Text(sectionSummary(section)).font(.caption).foregroundStyle(.secondary)
                                }.padding(.vertical, 3)
                            }
                            .id(section.id)
                        }
                    } header: { Text("Settings") }
                } else {
                ForEach(sections) { built in
                    Section {
                        ForEach(built.rows) { row in
                            row.content
                        }
                    } header: {
                        Text(built.section.title)
                    } footer: {





                        if search.isEmpty {
                            footer(for: built.section)
                        }
                    }
                    .id(built.section.id)
                }
                if sections.isEmpty { ContentUnavailableView.search(text: search) }
                }
            }
            .formStyle(.grouped)



            .navigationDestination(item: $deepLinkedSettingsSection) { section in settingsSectionPage(section) }
            .onAppear { consumeSettingsDeepLinkIfNeeded(using: proxy) }
            .onChange(of: requestedSettingsPage) { _, _ in consumeSettingsDeepLinkIfNeeded(using: proxy) }
        }
    }

    private func consumeSettingsDeepLinkIfNeeded(using proxy: ScrollViewProxy) {
        guard !requestedSettingsPage.isEmpty,
              let section = SettingsSectionName.canonical(requestedSettingsPage)
        else { return }
        requestedSettingsPage = ""
        search = ""
        Task { @MainActor in
            await Task.yield()
            withAnimation { proxy.scrollTo(section, anchor: .top) }
            deepLinkedSettingsSection = SettingsSection(rawValue: section)
        }
    }



}
