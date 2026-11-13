import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import NuvxShared





struct RecentSearchEntity: Codable, Identifiable, Hashable {

    var id: String
    var title: String
    var subtitle: String
    var artworkUrl: String?

    var entityType: String
    var timestamp: Int64

    init(id: String, title: String, subtitle: String = "", artworkUrl: String? = nil, entityType: String = "QUERY") {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.artworkUrl = artworkUrl
        self.entityType = entityType
        self.timestamp = Int64(Date().timeIntervalSince1970 * 1000)
    }

    var typeLabel: String {
        switch entityType.uppercased() {
        case "TRACK": return "Song"
        case "ALBUM": return "Album"
        case "ARTIST": return "Artist"
        case "PLAYLIST": return "Playlist"
        default: return subtitle.isEmpty ? "Search" : subtitle
        }
    }
}




enum RecentSearchStore {
    private static let key = "search_history"
    private static let maxEntries = 20

    static func load() -> [RecentSearchEntity] {
        let raw = PlatformSettings.shared.getString(key: key, default: "[]")
        guard let data = raw.data(using: .utf8) else { return [] }
        if let entities = try? JSONDecoder().decode([RecentSearchEntity].self, from: data) {


            return entities
        }
        let legacy = (try? JSONDecoder().decode([String].self, from: data)) ?? []
        return legacy.map { RecentSearchEntity(id: "q:\($0.lowercased())", title: $0) }
    }

    static func record(_ entity: RecentSearchEntity) {
        var list = load().filter { !$0.id.equalsIgnoringCase(entity.id) }
        var fresh = entity
        fresh.timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        list.insert(fresh, at: 0)
        save(Array(list.prefix(maxEntries)))
    }

    static func remove(id: String) {
        save(load().filter { !$0.id.equalsIgnoringCase(id) })
    }

    static func clear() { save([]) }

    private static func save(_ list: [RecentSearchEntity]) {
        guard let data = try? JSONEncoder().encode(list),
              let json = String(data: data, encoding: .utf8) else { return }
        PlatformSettings.shared.putString(key: key, value: json)
    }
}

private extension String {
    func equalsIgnoringCase(_ other: String) -> Bool {
        compare(other, options: .caseInsensitive) == .orderedSame
    }
}




enum AddonSearchHistory {
    private static let key = "addon_search_history"
    private static let maxEntries = 20

    static func load() -> [String] {
        guard let data = PlatformSettings.shared.getString(key: key, default: "[]").data(using: .utf8),
              let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return list
    }

    static func record(_ query: String) {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        var list = load().filter { $0.compare(term, options: .caseInsensitive) != .orderedSame }
        list.insert(term, at: 0)
        save(Array(list.prefix(maxEntries)))
    }

    static func remove(_ query: String) {
        save(load().filter { $0.compare(query, options: .caseInsensitive) != .orderedSame })
    }

    static func clear() { save([]) }

    private static func save(_ list: [String]) {
        guard let data = try? JSONEncoder().encode(list),
              let json = String(data: data, encoding: .utf8) else { return }
        PlatformSettings.shared.putString(key: key, value: json)
    }
}









struct ToolbarSearchSourceSwitcher: View {
    @Binding var selection: SearchView.SearchSource

    var body: some View {
        Picker("Search source", selection: $selection) {
            ForEach(SearchView.SearchSource.allCases) { source in
                Text(source.label).tag(source)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .fixedSize()
        #if os(macOS)
        .help("Search YouTube Music, your library or your addons")
        #endif
    }
}

#if os(macOS)









private struct MacToolbarSearchField: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    var onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.secondary)
            TextField("Search", text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .focused(focused)
                .onSubmit(onSubmit)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 10)
        .frame(width: 240, height: 28)
        .contentShape(.rect)
        .onTapGesture {
            focused.wrappedValue = true
        }
    }
}
#else

















private struct IPadToolbarSearchPill: View {
    static let openWidth: CGFloat = 300

    @Binding var text: String
    let side: CGFloat
    let roomy: Bool
    let open: Bool
    var focused: FocusState<Bool>.Binding
    var onExpand: () -> Void
    var onSubmit: () -> Void

    var body: some View {
        HStack(spacing: open ? 8 : 0) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(open ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))


                .accessibilityLabel("Search")
                .accessibilityAddTraits(.isButton)
                .accessibilityHidden(open)
            if roomy {
                TextField("Artists, Songs, Lyrics, and More", text: $text)
                    .textFieldStyle(.plain)
                    .font(.subheadline)
                    .focused(focused)
                    .submitLabel(.search)
                    .onSubmit(onSubmit)
                    .frame(maxWidth: open ? .infinity : 0)
                    .opacity(open ? 1 : 0)
                    .allowsHitTesting(open)
                    .accessibilityHidden(!open)
            }
            if open {
                if !text.isEmpty {
                    Button {
                        text = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear search")
                    .transition(.opacity)
                }
            }
        }
        .padding(.horizontal, open ? 14 : 0)
        .frame(width: open ? Self.openWidth : side, height: side)
        .chromeGlass(in: Capsule())
        .contentShape(Capsule())
        .onTapGesture {
            if open {
                focused.wrappedValue = true
            } else {
                onExpand()
            }
        }
        .frame(width: roomy ? Self.openWidth : side, alignment: .trailing)
    }
}




private struct TopRow<Row: View>: ViewModifier {
    let enabled: Bool
    @ViewBuilder var row: Row

    func body(content: Content) -> some View {
        if !enabled {
            content
        } else if #available(iOS 26.0, *) {
            content.safeAreaBar(edge: .top, spacing: 0) { row }
        } else {
            content.safeAreaInset(edge: .top, spacing: 0) { row.background(.bar) }
        }
    }
}

private struct CompactPhoneSearchDrawer: ViewModifier {
    let isCompact: Bool
    @Binding var query: String
    @Binding var isPresented: Bool
    var focused: FocusState<Bool>.Binding
    var onSubmit: () -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if isCompact {
            content
                .searchable(
                    text: $query,
                    isPresented: $isPresented,
                    placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Artists, Songs, Lyrics, and More"
                )
                .searchFocused(focused)
                .onSubmit(of: .search, onSubmit)
        } else {
            content
        }
    }
}
#endif

struct SearchView: View {

    private static let typeaheadMaxResults = 15
    @Environment(PlaybackController.self) private var controller
    @Environment(AppModel.self) private var appModel
    @Environment(AuthController.self) private var auth
    #if os(macOS)

    @Environment(\.openSettings) private var openSettings
    @Environment(\.openWindow) private var openWindow
    #endif
    @State private var query = ""
    private let initialQuery: String?
    @State private var categories = MoodGenreLoader()
    @State private var historyRevision = 0



    @State private var scope: Scope = .all
    @State private var searching = false
    @State private var hits: [SearchHitDTO] = []
    @State private var suggestions: [String] = []
    @State private var searchError: String?
    @State private var attempted = false
    @State private var isSearchPresented = false

    @FocusState private var searchFieldFocused: Bool
    @State private var suggestTask: Task<Void, Never>?



    @State private var typeaheadHits: [SearchHitDTO] = []
    @State private var typeaheadTask: Task<Void, Never>?



    @State private var searchTask: Task<Void, Never>?


    @State private var requestToken = 0








    @State private var searchedTerm = ""


    @State private var searchContinuationToken: String?


    @State private var loadingMore = false


    @State private var searchSource: SearchSource = .youtube
    @State private var addonHits: [SearchHitDTO] = []
    @State private var addonError: String?
    @State private var searchingAddons = false
    @State private var addonHistory: [String] = AddonSearchHistory.load()


    @State private var addonCount = 0



    @State private var librarySongs: [YouTubeSong] = []
    @State private var libraryShelves: [FeedShelf] = []
    @State private var libraryLoading = false
    @State private var libraryError: String?
    @State private var local = LocalLibrary.shared

    @State private var pillRoomy = false
    @State private var pillOpen = false
    @State private var pillGeneration = 0

    @State private var switcherHeight: CGFloat = 47



    @State private var pageWidth: CGFloat = 0
    private var switcherInBar: Bool { pageWidth == 0 || pageWidth >= 1040 }
    @Environment(\.scenePhase) private var scenePhase
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif
    private let showsMacToolbar: Bool
    private let hostsWindowControls: Bool

    init(
        initialQuery: String? = nil,
        initialScope: Scope = .all,
        initialSource: SearchSource = .youtube,
        showsMacToolbar: Bool = true,
        hostsWindowControls: Bool = false
    ) {
        self.initialQuery = initialQuery
        self.showsMacToolbar = showsMacToolbar
        self.hostsWindowControls = hostsWindowControls
        _query = State(initialValue: initialQuery ?? "")
        _scope = State(initialValue: initialScope)
        _searchSource = State(initialValue: initialSource)
    }





    enum SearchSource: String, CaseIterable, Identifiable, Codable {
        case youtube, library, addons
        var id: String { rawValue }
        var label: String {
            switch self {
            case .youtube: return "YouTube"
            case .library: return "Library"
            case .addons: return "Addons"
            }
        }
    }

    enum Scope: String, CaseIterable, Identifiable, Codable {


        case all, songs, videos, albums, artists, playlists

        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: return "All"
            case .videos: return "Videos"
            default: return rawValue.capitalized
            }
        }





        var showsTopResult: Bool { self == .all }
    }

    var body: some View {
        PageStack {
            resultsColumn
                .navigationTitle("Search")
                .toolbar {
                    #if os(iOS)
                    TopBarLeadingItems()
                    if horizontalSizeClass == .regular {





                        if #available(iOS 26.0, *) {
                            ToolbarItem(placement: .topBarTrailing) { iPadSearchPill }
                                .sharedBackgroundVisibility(.hidden)
                        } else {
                            ToolbarItem(placement: .topBarTrailing) { iPadSearchPill }
                        }
                        if switcherInBar {
                            if #available(iOS 26.0, *) {
                                ToolbarSpacer(.fixed, placement: .topBarTrailing)
                                ToolbarItem(placement: .topBarTrailing) { measuredSwitcher }
                                    .sharedBackgroundVisibility(.hidden)
                            } else {
                                ToolbarItem(placement: .topBarTrailing) { measuredSwitcher }
                            }
                        }
                    } else {
                        ToolbarItem(placement: .topBarTrailing) { TopBarAccountButton() }
                    }
                    #elseif os(macOS)
                    macToolbar
                    #endif
                }
                #if os(iOS)
                .navigationBarTitleDisplayMode(titleRow ? .inline : .automatic)
                .modifier(TopRow(enabled: titleRow) {
                    HStack(spacing: 16) {
                        Text("Search")
                            .font(.largeTitle.bold())
                            .accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 0)
                        measuredSwitcher
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 4)
                    .padding(.bottom, 8)
                })
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { pageWidth = $0 }
                .modifier(CompactPhoneSearchDrawer(
                    isCompact: horizontalSizeClass != .regular,
                    query: $query,
                    isPresented: $isSearchPresented,
                    focused: $searchFieldFocused,
                    onSubmit: {
                        Task {
                            await performSearch()
                            searchFieldFocused = false
                        }
                    }
                ))
                #endif
                .task(id: initialQuery) {
                    guard let initialQuery,
                          !initialQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    await performSearch()
                }




                .onChange(of: appModel.searchFocusTrigger) { _, _ in
                    focusTheField()
                }
                .onChange(of: scope) { _, _ in
                    guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    Task { await performSearch() }
                }
                .onChange(of: query) { _, value in
                    scheduleSuggestions(value)


                    if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        resetToHistory()
                        if !searchFieldFocused { setIPadSearchExpanded(false) }
                    }
                }
                #if os(iOS)



                .onChange(of: searchFieldFocused) { _, focused in
                    if !focused && term.isEmpty { setIPadSearchExpanded(false) }
                }
                .onAppear {


                    if !term.isEmpty { setIPadSearchExpanded(true, focus: false) }
                }
                #endif
                .onChange(of: isSearchPresented) { _, presented in
                    if !presented && query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        resetToHistory()
                    }
                }
                .onChange(of: appModel.focusSearch) { _, focus in
                    guard focus else { return }
                    focusTheField()
                    appModel.focusSearch = false
                }
                .onChange(of: appModel.pendingSearchQuery) { _, pending in
                    guard let pending, !pending.isEmpty else { return }
                    query = pending
                    appModel.pendingSearchQuery = nil
                    Task { await performSearch() }
                }
                .onDisappear {
                    cancelSuggestions()

                    setIPadSearchExpanded(false, animated: false)
                }
        }
    }





    private func resetToHistory() {
        searchTask?.cancel()
        searchTask = nil
        searching = false
        searchingAddons = false
        attempted = false
        hits = []
        addonHits = []
        addonError = nil
        searchError = nil
        suggestions = []
        typeaheadHits = []
        searchContinuationToken = nil
        loadingMore = false


        searchedTerm = ""


        requestToken &+= 1
    }








    private func focusTheField() {
        #if os(iOS)
        if horizontalSizeClass == .regular {

            setIPadSearchExpanded(true)
            return
        }
        #endif
        Task { @MainActor in
            #if os(iOS)
            isSearchPresented = true
            #endif
            searchFieldFocused = true
        }
    }

    #if os(iOS)


    private var titleRow: Bool { horizontalSizeClass == .regular && !switcherInBar }




    private var measuredSwitcher: some View {
        ToolbarSearchSourceSwitcher(selection: $searchSource)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                if height > 0, switcherInBar { switcherHeight = height }
            }
    }

    private var iPadSearchPill: some View {
        IPadToolbarSearchPill(
            text: $query,
            side: switcherHeight,
            roomy: pillRoomy,
            open: pillOpen,
            focused: $searchFieldFocused,
            onExpand: { focusTheField() },
            onSubmit: {
                Task {
                    await performSearch()
                    searchFieldFocused = false
                }
            }
        )
    }
    #endif









    private func setIPadSearchExpanded(_ expanded: Bool, animated: Bool = true, focus: Bool = true) {
        #if os(iOS)
        let wanted = expanded && horizontalSizeClass == .regular
        guard wanted != appModel.iPadSearchExpanded || wanted != pillOpen else { return }
        pillGeneration &+= 1
        let generation = pillGeneration
        guard animated else {
            pillOpen = wanted
            pillRoomy = wanted
            appModel.iPadSearchExpanded = wanted
            return
        }
        Task { @MainActor in
            @MainActor func current() -> Bool { generation == pillGeneration }
            if wanted {
                withAnimation(.smooth(duration: 0.3)) { appModel.iPadSearchExpanded = true }
                try? await Task.sleep(for: .milliseconds(300))
                guard current() else { return }
                pillRoomy = true


                try? await Task.sleep(for: .milliseconds(60))
                guard current() else { return }
                withAnimation(.smooth(duration: 0.35)) { pillOpen = true }
                guard focus else { return }


                try? await Task.sleep(for: .milliseconds(360))
                guard current() else { return }
                searchFieldFocused = true
            } else {
                withAnimation(.smooth(duration: 0.3)) { pillOpen = false }
                try? await Task.sleep(for: .milliseconds(300))
                guard current() else { return }
                pillRoomy = false
                withAnimation(.smooth(duration: 0.3)) { appModel.iPadSearchExpanded = false }
            }
        }
        #endif
    }






    private func cancelSuggestions() {
        suggestTask?.cancel()
        suggestTask = nil
        typeaheadTask?.cancel()
        typeaheadTask = nil
    }

    #if os(macOS)
    @ToolbarContentBuilder
    private var macToolbar: some ToolbarContent {
        if showsMacToolbar && !appModel.nowPlayingPresented {
            if !searchedTerm.isEmpty {


                ToolbarItem(placement: .primaryAction) {
                    Button {
                        openWindow(
                            id: MacBrowseWindow.sceneID,
                            value: MacBrowseWindowRoute.search(
                                query: searchedTerm, scope: scope, source: searchSource
                            )
                        )
                    } label: {
                        Label("Open in New Window", systemImage: "macwindow.badge.plus")
                    }
                    .help("Open these search results in a new window")
                }
            }


            ToolbarItem(id: MacToolbarItemID.searchField, placement: .primaryAction) {
                MacToolbarSearchField(text: $query, focused: $searchFieldFocused) {
                    Task {
                        await performSearch()
                        searchFieldFocused = false
                    }
                }
            }
            ToolbarItem(id: MacToolbarItemID.searchSource, placement: .primaryAction) {
                ToolbarSearchSourceSwitcher(selection: $searchSource)
            }
            if hostsWindowControls {
                MacWindowControls()
            }
        }
    }
    #endif

    private var resultsColumn: some View {
        VStack(spacing: 0) {
            #if os(iOS)






            if horizontalSizeClass != .regular, term.isEmpty {
                sourceSwitcher
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
            }
            #endif
            switch searchSource {
            case .youtube: youtubeResultsColumn
            case .library: libraryResultsColumn
            case .addons: addonResultsColumn
            }
        }
        .task(id: searchSource == .library ? auth.sessionEpoch : -1) {
            await loadLibrary()
        }
        .onChange(of: scenePhase) { _, _ in refreshAddonCount() }
        .onChange(of: searchSource) { _, _ in
            refreshAddonCount()
            guard !term.isEmpty else { return }
            Task { await performSearch() }
        }
        #if os(macOS)


        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)) { _ in
            refreshAddonCount()
        }
        #endif
    }




    private var sourceSwitcher: some View {
        Picker("Search source", selection: $searchSource) {
            ForEach(SearchSource.allCases) { source in
                Text(source.label).tag(source)
            }
        }
        .pickerStyle(.segmented)


        .labelsHidden()
    }





    private func refreshAddonCount() {
        addonCount = Int(SourceResolverBridge.shared.addonCount())
    }

    private var youtubeResultsColumn: some View {
        VStack(spacing: 0) {







            if showsFilters {
                filterChips
                    .padding(.bottom, 6)
            }

            if searching {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(0..<8, id: \.self) { _ in SkeletonBlock(height: 52) }
                    }
                    .padding(.horizontal, 28)
                    .padding(.vertical, 12)
                }
            } else if attempted && !searching && hits.isEmpty && searchError == nil {
                EmptyStateView(icon: Image(.nuvxSearch), title: "No results", subtitle: "Nothing found for “\(query)”.", buttonTitle: nil, action: nil)
            } else if let searchError {
                EmptyStateView(icon: Image(.nuvxSearch), title: "Search unavailable", subtitle: searchError, buttonTitle: nil, action: nil)
            } else if isEditing {
                suggestionList
            } else if !query.isEmpty {
                List {
                    if let top = topResult {




                        Section {
                            TopResultSection(hit: top, scope: scope) { term in
                                query = term
                            }
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        }
                    }
                    ForEach(sections) { sec in
                        if let title = sec.title {
                            Section {
                                ForEach(sec.hits) { hit in
                                    searchHitRow(hit)
                                }
                            } header: {
                                Text(title)
                                    .font(.title3.weight(.bold))
                                    .foregroundStyle(.primary)
                                    .textCase(nil)
                                    .padding(.top, 10)
                                    .padding(.bottom, 4)
                            }
                        } else {
                            ForEach(sec.hits) { hit in
                                searchHitRow(hit)
                            }
                        }
                    }



                    if searchContinuationToken != nil {
                        Section {
                            HStack {
                                Spacer()
                                if loadingMore { ProgressView().controlSize(.small) }
                                Spacer()
                            }
                            .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0))
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .onAppear { loadMore() }
                        }
                    }
                }
                .listStyle(.plain)
                .listSectionSeparator(.hidden)
                .scrollContentBackground(.hidden)






                .id(requestToken)
            } else {
                history
            }
        }
    }




    private struct LibraryMatches {
        var songs: [YouTubeSong] = []
        var device: [LocalTrack] = []
        var artists: [SearchHitDTO] = []
        var albums: [SearchHitDTO] = []
        var playlists: [SearchHitDTO] = []

        var isEmpty: Bool {
            songs.isEmpty && device.isEmpty && artists.isEmpty && albums.isEmpty && playlists.isEmpty
        }
    }



    private static func libraryMatch(_ words: [String], _ fields: String?...) -> Bool {
        let haystack = fields.compactMap { $0 }.joined(separator: " ")
        return words.allSatisfy { haystack.localizedStandardContains($0) }
    }

    private var libraryMatches: LibraryMatches {
        let words = term.split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return LibraryMatches() }
        func cards(_ shelf: String, type: String) -> [SearchHitDTO] {
            (libraryShelf(titled: shelf, in: libraryShelves)?.items ?? [])
                .filter { $0.browseId != nil && Self.libraryMatch(words, $0.title, $0.subtitle) }
                .map {
                    SearchHitDTO(
                        kind: "browse", videoId: nil, title: $0.title, subtitle: $0.subtitle,
                        thumbnailUrl: $0.thumbnailUrl, durationText: nil, albumName: nil,
                        browseId: $0.browseId, browseType: type, artistId: nil, albumId: nil,
                        isVideo: nil, setVideoId: nil
                    )
                }
        }
        return LibraryMatches(
            songs: librarySongs.filter { Self.libraryMatch(words, $0.title, $0.artist, $0.albumName) },
            device: local.tracks.filter { Self.libraryMatch(words, $0.title, $0.artist, $0.album) },
            artists: cards("artists", type: "ARTIST"),
            albums: cards("albums", type: "ALBUM"),
            playlists: cards("playlists", type: "PLAYLIST")
        )
    }




    private func loadLibrary() async {
        guard searchSource == .library else { return }
        guard auth.signedIn else {
            librarySongs = []
            libraryShelves = []
            libraryLoading = false
            return
        }
        libraryLoading = librarySongs.isEmpty && libraryShelves.isEmpty
        libraryError = nil
        async let songs = InnertubeFeed.shared.librarySongs()
        async let shelves = InnertubeFeed.shared.library()
        var failure: String?
        do { librarySongs = try await songs } catch { failure = error.localizedDescription }
        do { libraryShelves = try await shelves } catch { failure = failure ?? error.localizedDescription }
        guard !Task.isCancelled else { return }


        libraryError = librarySongs.isEmpty && libraryShelves.isEmpty ? failure : nil
        libraryLoading = false
    }




    @ViewBuilder
    private var libraryResultsColumn: some View {
        let matches = libraryMatches
        if !auth.signedIn && !local.scanned {
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "Search your library",
                subtitle: "Sign in, or scan a folder on this device, to search the songs, albums, artists and playlists you keep.",
                buttonTitle: "Sign In"
            ) { auth.loginPresented = true }
        } else if term.isEmpty {
            EmptyStateView(
                icon: Image(.nuvxLibrary),
                title: "Search your library",
                subtitle: "Songs, albums, artists and playlists you saved, and the music on this device.",
                buttonTitle: nil,
                action: nil
            )
        } else if matches.isEmpty && libraryLoading {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(0..<8, id: \.self) { _ in SkeletonBlock(height: 52) }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 12)
            }
        } else if matches.isEmpty, let libraryError, !local.scanned {
            EmptyStateView(icon: Image(.nuvxLibrary), title: "Library unavailable", subtitle: libraryError, buttonTitle: "Retry") {
                Task { await loadLibrary() }
            }
        } else if matches.isEmpty {
            EmptyStateView(icon: Image(.nuvxSearch), title: "No results", subtitle: "Nothing in your library for “\(term)”.", buttonTitle: nil, action: nil)
        } else {
            List {
                if !matches.songs.isEmpty {
                    librarySection("Songs") {
                        let entries = matches.songs.map(Self.libraryEntry)
                        ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                            librarySongRow(entry) {
                                controller.play(entries, at: index, context: "Library", contextID: "library:songs")
                            }
                        }
                    }
                }
                if !matches.device.isEmpty {
                    librarySection("On This Device") {
                        let entries = matches.device.map(QueueEntry.from)
                        ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                            librarySongRow(entry) {
                                controller.play(entries, at: index, context: "On Device", contextID: "local:songs")
                            }
                        }
                    }
                }
                if !matches.artists.isEmpty {
                    librarySection("Artists") { ForEach(matches.artists) { searchHitRow($0) } }
                }
                if !matches.albums.isEmpty {
                    librarySection("Albums") { ForEach(matches.albums) { searchHitRow($0) } }
                }
                if !matches.playlists.isEmpty {
                    librarySection("Playlists") { ForEach(matches.playlists) { searchHitRow($0) } }
                }
            }
            .listStyle(.plain)
            .listSectionSeparator(.hidden)
            .scrollContentBackground(.hidden)
        }
    }

    private func librarySection<Rows: View>(_ title: String, @ViewBuilder rows: () -> Rows) -> some View {
        Section {
            rows()
        } header: {
            Text(title)
                .font(.title3.weight(.bold))
                .foregroundStyle(.primary)
                .textCase(nil)
                .padding(.top, 10)
                .padding(.bottom, 4)
        }
    }

    private func librarySongRow(_ entry: QueueEntry, play: @escaping () -> Void) -> some View {
        SongRow(
            entry: entry,
            isCurrent: controller.current?.id == entry.id,
            play: play,
            playNext: { controller.playNext(entry) },
            addToQueue: { controller.addToQueue(entry) }
        )
        .listRowInsets(EdgeInsets(top: 2, leading: 24, bottom: 2, trailing: 24))
        .searchRowChrome()
    }

    private static func libraryEntry(_ song: YouTubeSong) -> QueueEntry {
        QueueEntry(
            id: song.videoId, title: song.title, artist: song.artist,
            source: "yt:\(song.videoId)", thumbnailUrl: song.thumbnailUrl,
            durationText: song.durationText, albumName: song.albumName,
            artworkData: nil, isLocal: false
        )
    }



    @ViewBuilder
    private var addonResultsColumn: some View {
        if searchingAddons {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(0..<8, id: \.self) { _ in SkeletonBlock(height: 52) }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 12)
            }
        } else if isEditing {
            addonHistoryView
        } else if let addonError {
            EmptyStateView(icon: Image(.nuvxSearch), title: "Sources unavailable", subtitle: addonError, buttonTitle: nil, action: nil)
        } else if !addonHits.isEmpty {
            List {
                ForEach(Array(addonHits.enumerated()), id: \.element.id) { index, hit in
                    addonRow(hit, index: index)
                }
            }
            .listStyle(.plain)
            .listSectionSeparator(.hidden)
            .scrollContentBackground(.hidden)
            .id(requestToken)
        } else if attempted && !query.isEmpty {
            EmptyStateView(icon: Image(.nuvxSearch), title: "No results", subtitle: "Nothing in your sources for “\(query)”.", buttonTitle: nil, action: nil)
        } else {
            addonHistoryView
        }
    }

    private var addonHistoryView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if addonCount == 0 {



                    EmptyStateView(
                        icon: Image(.nuvxSearch),
                        title: "No addons yet",
                        subtitle: "Add an addon in Settings ▸ Sources to search it here.",
                        buttonTitle: "Open Sources"
                    ) {
                        #if os(macOS)
                        openSettings()
                        #else
                        appModel.settingsPresented = true
                        #endif
                    }
                } else if addonHistory.isEmpty {
                    Text("Search your addons. Their addresses and order live in Settings ▸ Sources.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                } else {
                    HStack {
                        Text("Recently Searched")
                            .font(.title3.weight(.bold))
                        Spacer()
                        Button("Clear") {
                            AddonSearchHistory.clear()
                            addonHistory = []
                        }
                        .font(.callout)
                    }
                    ForEach(addonHistory, id: \.self) { term in
                        HStack(spacing: 12) {
                            Image(systemName: "clock")
                                .foregroundStyle(.secondary)
                            Text(term)
                                .lineLimit(1)
                                .contentShape(.rect)
                                .onTapGesture {
                                    query = term
                                    Task { await performSearch() }
                                }
                            Spacer(minLength: 8)
                            Button {
                                AddonSearchHistory.remove(term)
                                addonHistory = AddonSearchHistory.load()
                            } label: {
                                Image(systemName: "xmark")
                                    .scaledFont(size: 11, weight: .semibold)
                                    .foregroundStyle(.tertiary)
                                    .frame(width: 28, height: 28)
                                    .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Remove \(term) from addon searches")
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear {
            refreshAddonCount()
        }
    }



    private func addonRow(_ hit: SearchHitDTO, index: Int) -> some View {
        HStack(spacing: 12) {
            ArtworkView(url: hit.thumbnailUrl, data: nil, side: 44)
                .clipShape(.rect(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(hit.title)
                    .font(.body)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    if let badge = qualityBadge(hit.sourceQuality) {
                        Text(badge)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.accentColor.opacity(0.16), in: Capsule())
                            .foregroundStyle(.tint)
                    }
                    if let artist = hit.subtitle, !artist.isEmpty {
                        Text(artist)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 8)
            if let text = hit.durationText, !text.isEmpty {
                Text(text)
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 8)
        .contentShape(.rect)
        .onTapGesture {


            controller.play(addonHits.map { $0.asEntry() }, at: index, context: "Search")
        }
        .accessibilityAddTraits(.isButton)
        .searchRowChrome()
    }



    private func qualityBadge(_ raw: String?) -> String? {
        switch raw?.uppercased() {
        case "DOLBY": return "Dolby Atmos"
        case "HI-RES", "HIRES": return "Hi-Res"
        case "LOSSLESS", "FLAC", "ALAC", "WAV": return "Lossless"
        case "HIGH", "320": return "High"
        default: return nil
        }
    }



    private func isCurrentlyPlaying(_ hit: SearchHitDTO) -> Bool {
        guard let current = controller.current else { return false }
        let entry = hit.asEntry()
        return current.id == entry.id
            || (hit.videoId != nil && current.videoId == hit.videoId)
    }


    private struct SearchSectionModel: Identifiable {
        var id: String { title ?? "all" }
        let title: String?
        let hits: [SearchHitDTO]
    }



    private var sections: [SearchSectionModel] {
        if scope != .all {
            return [SearchSectionModel(title: nil, hits: listHits)]
        }
        let songs = listHits.filter(\.isTrack)
        let artists = listHits.filter { $0.isBrowse && ($0.browseType?.uppercased() == "ARTIST") }
        let albums = listHits.filter { $0.isBrowse && ($0.browseType?.uppercased() == "ALBUM") }
        let playlists = listHits.filter { $0.isBrowse && ($0.browseType?.uppercased() == "PLAYLIST") }
        let more = listHits.filter { $0.isBrowse && !["ARTIST", "ALBUM", "PLAYLIST"].contains($0.browseType?.uppercased() ?? "") }

        var list: [SearchSectionModel] = []
        if !songs.isEmpty { list.append(SearchSectionModel(title: "Songs", hits: songs)) }
        if !artists.isEmpty { list.append(SearchSectionModel(title: "Artists", hits: artists)) }
        if !albums.isEmpty { list.append(SearchSectionModel(title: "Albums", hits: albums)) }
        if !playlists.isEmpty { list.append(SearchSectionModel(title: "Playlists", hits: playlists)) }
        if !more.isEmpty { list.append(SearchSectionModel(title: "More", hits: more)) }
        return list
    }

    @ViewBuilder
    private func searchHitRow(_ hit: SearchHitDTO) -> some View {
        Group {
            if hit.isBrowse, let browseId = hit.browseId {
                PushLink(destination: DetailView(browseId: browseId, initialTitle: hit.title)) {
                    browseRow(hit)
                }
                .buttonStyle(.plain)
                .simultaneousGesture(TapGesture().onEnded {


                    RecentSearchStore.record(RecentSearchEntity(
                        id: browseId, title: hit.title,
                        subtitle: hit.subtitle ?? "",
                        artworkUrl: hit.thumbnailUrl,
                        entityType: browseEntityType(hit)
                    ))
                })
                .shelfCardContextMenu(
                    ShelfCard(
                        title: hit.title, subtitle: hit.subtitle,
                        thumbnailUrl: hit.thumbnailUrl, browseId: browseId
                    ),

                    roundArtwork: hit.browseType?.uppercased() == "ARTIST"
                )
            } else {
                SongRow(
                    entry: hit.asEntry(),
                    isCurrent: isCurrentlyPlaying(hit),
                    play: {
                        recordTrackEntity(hit)
                        let tracks = listHits.filter(\.isTrack).map { $0.asEntry() }
                        let at = tracks.firstIndex(where: { $0.id == hit.videoId }) ?? 0
                        if scope == .songs {
                            controller.playRadio(hit.asEntry())
                        } else {
                            controller.play(tracks, at: at)
                        }
                    },
                    playNext: { controller.playNext(hit.asEntry()) },
                    addToQueue: { controller.addToQueue(hit.asEntry()) }
                )
            }
        }
        .listRowInsets(EdgeInsets(top: 2, leading: 24, bottom: 2, trailing: 24))
        .searchRowChrome()
    }





    @ViewBuilder
    private func typeaheadRow(_ hit: SearchHitDTO) -> some View {
        Group {
            if hit.isBrowse, let browseId = hit.browseId {
                PushLink(destination: DetailView(browseId: browseId, initialTitle: hit.title)) {
                    browseRow(hit)
                }
                .buttonStyle(.plain)
                .simultaneousGesture(TapGesture().onEnded {
                    RecentSearchStore.record(RecentSearchEntity(
                        id: browseId, title: hit.title,
                        subtitle: hit.subtitle ?? "",
                        artworkUrl: hit.thumbnailUrl,
                        entityType: browseEntityType(hit)
                    ))
                })
                .shelfCardContextMenu(
                    ShelfCard(
                        title: hit.title, subtitle: hit.subtitle,
                        thumbnailUrl: hit.thumbnailUrl, browseId: browseId
                    ),
                    roundArtwork: hit.browseType?.uppercased() == "ARTIST"
                )
            } else {
                SongRow(
                    entry: hit.asEntry(),
                    isCurrent: isCurrentlyPlaying(hit),
                    play: {
                        recordTrackEntity(hit)
                        controller.playRadio(hit.asEntry())
                    },
                    playNext: { controller.playNext(hit.asEntry()) },
                    addToQueue: { controller.addToQueue(hit.asEntry()) }
                )
            }
        }
        .listRowInsets(EdgeInsets(top: 2, leading: 24, bottom: 2, trailing: 24))
        .searchRowChrome()
    }



    private var term: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }








    private var isEditing: Bool {
        !term.isEmpty && term != searchedTerm
    }




    private var showsFilters: Bool {
        attempted && !searching && !isEditing
    }

    private var suggestionList: some View {
        List {
            if !suggestions.isEmpty {
                Section("Suggestions") {
                    ForEach(suggestions, id: \.self) { term in
                        suggestionRow(term)
                    }
                }
            }





            let media = Array(typeaheadHits.prefix(Self.typeaheadMaxResults))
            if !media.isEmpty {
                Section {
                    ForEach(media) { hit in
                        typeaheadRow(hit)
                    }
                }
            }




            let recent = recentSuggestions
            if !suggestions.isEmpty || !recent.isEmpty {
                Section("Recent") {
                    ForEach(recent) { entity in
                        suggestionRow(entity.title)
                    }
                }
            }
        }
        .listStyle(.plain)
        .listSectionSeparator(.hidden)
        .scrollContentBackground(.hidden)
    }




    private var recentSuggestions: [RecentSearchEntity] {
        let all = RecentSearchStore.load()
        let term = self.term
        guard !term.isEmpty else { return all }
        let matching = all.filter { $0.title.localizedCaseInsensitiveContains(term) }
        return Array((matching.isEmpty ? all : matching).prefix(3))
    }




    private func suggestionRow(_ term: String) -> some View {
        HStack(spacing: 0) {
            Button {
                query = term
                Task { await performSearch() }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    Text(term)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            Button {


                query = term
            } label: {
                Image(systemName: "arrow.up.left")
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 32)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Fill search with \(term)")
        }
        .padding(.vertical, 4)
        .searchRowChrome()
    }

    private func browseRow(_ hit: SearchHitDTO) -> some View {
        let isArtist = hit.browseType?.uppercased() == "ARTIST"
        return HStack(spacing: 14) {
            if isArtist {
                ArtworkView(url: hit.thumbnailUrl, data: nil, side: 48)
                    .clipShape(Circle())
            } else {
                ArtworkView(url: hit.thumbnailUrl, data: nil, side: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(hit.title)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                Text(hit.subtitle ?? hit.browseType ?? "")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }

    #if os(iOS)
    private var pillSelectedTextColor: Color { Color(uiColor: .systemBackground) }
    #else
    private var pillSelectedTextColor: Color { Color(nsColor: .windowBackgroundColor) }
    #endif



    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Scope.allCases) { entry in
                    let selected = entry == scope
                    Button {
                        if !selected {
                            #if os(iOS)
                            let generator = UISelectionFeedbackGenerator()
                            generator.prepare()
                            generator.selectionChanged()
                            #endif
                            withAnimation(.snappy(duration: 0.25)) {
                                scope = entry
                            }
                        }
                    } label: {
                        Text(entry.label)
                            .font(.subheadline.weight(selected ? .semibold : .regular))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(
                                selected ? Color.primary : Color.secondary.opacity(0.12),
                                in: Capsule()
                            )
                            .foregroundStyle(selected ? pillSelectedTextColor : Color.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }

    private var history: some View {
        let _ = historyRevision
        return ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                let recent = RecentSearchStore.load()
                if !recent.isEmpty {
                    recentSearches(recent)
                }

                browseCategories
            }
            .frame(maxWidth: 1_520, alignment: .leading)
            .padding(.horizontal, landingHorizontalInset)
            .padding(.top, 16)
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .refreshable { await categories.load(force: true) }
        .task { await categories.load(force: false) }
    }



    private func recentSearches(_ recent: [RecentSearchEntity]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text("Recently Searched")
                    .font(.title3.weight(.bold))
                Spacer()
                Button("Clear") {
                    RecentSearchStore.clear()
                    historyRevision &+= 1
                }
                .font(.callout)
                .foregroundStyle(.tint)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(recent) { entity in
                        recentSearchCard(entity)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func recentSearchCard(_ entity: RecentSearchEntity) -> some View {
        let isArtist = entity.entityType.uppercased() == "ARTIST"
        let isQuery = entity.entityType.uppercased() == "QUERY" || entity.id.hasPrefix("q:")
        return HStack(spacing: 12) {
            Button { openHistoryEntity(entity) } label: {
                HStack(spacing: 12) {
                    Group {
                        if isQuery {
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(.quaternary)
                                .overlay {
                                    Image(systemName: "magnifyingglass")
                                        .scaledFont(size: 18, weight: .medium)
                                        .foregroundStyle(.secondary)
                                }
                                .frame(width: 52, height: 52)
                        } else {
                            if isArtist {
                                ArtworkView(url: entity.artworkUrl, data: nil, side: 52)
                                    .clipShape(Circle())
                            } else {
                                ArtworkView(url: entity.artworkUrl, data: nil, side: 52)
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 3) {
                        Text(entity.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(isQuery ? "Search" : (entity.subtitle.isEmpty ? entity.typeLabel : "\(entity.subtitle) · \(entity.typeLabel)"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)

            Button {
                RecentSearchStore.remove(id: entity.id)
                historyRevision &+= 1
            } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .semibold)
                    .foregroundStyle(.tertiary)
                    .frame(width: 28, height: 28)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(entity.title) from recent searches")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(width: recentCardWidth, alignment: .leading)
        .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
    }

    private var browseCategories: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Browse Categories")
                .font(.title3.weight(.bold))

            switch categories.phase {
            case .loading:
                LazyVGrid(columns: categoryColumns, spacing: 12) {
                    ForEach(0..<10, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(.quaternary)
                            .aspectRatio(1.75, contentMode: .fit)
                            .redacted(reason: .placeholder)
                    }
                }
            case .failed(let message):
                HStack(spacing: 12) {
                    Text("Categories could not load. \(message)")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Button("Retry") { Task { await categories.load(force: true) } }
                        .buttonStyle(.bordered)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            case .loaded(let sections):
                let items = sections.flatMap(\.items)
                if items.isEmpty {
                    Text("No categories are available right now.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 8)
                } else {
                    LazyVGrid(columns: categoryColumns, spacing: 12) {
                        ForEach(items) { category in
                            PushLink {
                                MoodGenrePlaylistsView(category: category)
                            } label: {
                                SearchCategoryTile(category: category)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    private var categoryColumns: [GridItem] {
        #if os(macOS)
        let minimum: CGFloat = 210
        #else
        let minimum: CGFloat = horizontalSizeClass == .regular ? 180 : 145
        #endif
        return [GridItem(.adaptive(minimum: minimum, maximum: 280), spacing: 12)]
    }

    private var landingHorizontalInset: CGFloat {
        #if os(macOS)
        28
        #else
        horizontalSizeClass == .regular ? 28 : 16
        #endif
    }

    private var recentCardWidth: CGFloat {
        #if os(macOS)
        292
        #else
        horizontalSizeClass == .regular ? 280 : 252
        #endif
    }



    private func openHistoryEntity(_ entity: RecentSearchEntity) {
        switch entity.entityType.uppercased() {
        case "TRACK":
            if entity.id.hasPrefix("q:") {
                query = entity.title
                Task { await performSearch() }
            } else {
                controller.playRadio(QueueEntry.youtube(
                    videoId: entity.id, title: entity.title,
                    artist: entity.subtitle, thumbnailUrl: entity.artworkUrl
                ))
            }
        case "ALBUM", "ARTIST", "PLAYLIST":
            appModel.pendingDetail = .detail(browseId: entity.id, title: entity.title)
        default:
            query = entity.title
            Task { await performSearch() }
        }
    }

    private func scheduleSuggestions(_ value: String) {
        suggestTask?.cancel()
        typeaheadTask?.cancel()
        let term = value.trimmingCharacters(in: .whitespacesAndNewlines)



        guard searchSource == .youtube else {
            suggestions = []
            typeaheadHits = []
            return
        }



        guard term.count >= 2, term != searchedTerm else {
            suggestions = []
            typeaheadHits = []
            return
        }
        suggestTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled else { return }
            let list = await InnertubeSearch.shared.suggestions(term)
            await MainActor.run {
                guard !Task.isCancelled, self.term == term, term != self.searchedTerm else { return }
                suggestions = list
            }
        }



        typeaheadTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }




            let hits: [SearchHitDTO]
            do {
                hits = try await InnertubeSearch.shared.typeahead(term)
            } catch {
                DebugLog.shared.w(message: "search typeahead failed for \"\(term)\": \(error.localizedDescription)")
                hits = []
            }
            await MainActor.run {
                guard !Task.isCancelled, self.term == term, term != self.searchedTerm else { return }
                typeaheadHits = hits
            }
        }
    }





    private var topResult: SearchHitDTO? {
        guard scope.showsTopResult else { return nil }
        return hits.first { $0.isTopResult }
    }



    private var listHits: [SearchHitDTO] {
        guard scope.showsTopResult else { return hits }
        return hits.filter { !$0.isTopResult }
    }



    private func recordTrackEntity(_ hit: SearchHitDTO) {
        guard let videoId = hit.videoId else { return }
        RecentSearchStore.record(RecentSearchEntity(
            id: videoId, title: hit.title,
            subtitle: hit.subtitle ?? "",
            artworkUrl: hit.thumbnailUrl,
            entityType: "TRACK"
        ))
    }



    private func browseEntityType(_ hit: SearchHitDTO) -> String {
        switch (hit.browseType ?? "").uppercased() {
        case "ALBUM": return "ALBUM"
        case "ARTIST": return "ARTIST"
        case "PLAYLIST": return "PLAYLIST"
        default: return "TRACK"
        }
    }

    private func performSearch() async {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        switch searchSource {
        case .youtube: break


        case .library: return
        case .addons:
            await performAddonSearch(term)
            return
        }




        searchTask?.cancel()
        cancelSuggestions()
        searching = true
        attempted = true
        searchError = nil
        suggestions = []
        typeaheadHits = []
        searchContinuationToken = nil
        loadingMore = false


        requestToken &+= 1
        searchedTerm = term
        let wanted = scope
        let task = Task { @MainActor in
            do {
                let page = try await InnertubeSearch.shared.searchPage(term, scope: wanted.rawValue)
                var found = page.hits
                if PlatformSettings.shared.getBoolean(key: "jiosaavn_enabled", default: true), wanted == .songs {
                    let extra = await JioSaavn.search(term)
                    found.insert(contentsOf: extra, at: 0)
                }



                guard !Task.isCancelled else { return }
                hits = found
                searchContinuationToken = page.continuation
            } catch {
                guard !Task.isCancelled else { return }
                AuthController.reportIfSessionExpired(error)
                searchError = "Couldn't reach the music service. Check your connection and try again."
                hits = []
                searchContinuationToken = nil
            }
            searching = false
            searchTask = nil
        }
        searchTask = task
        _ = await task.value
    }




    private func performAddonSearch(_ term: String) async {
        searchTask?.cancel()
        cancelSuggestions()
        searchingAddons = true
        attempted = true
        addonError = nil
        suggestions = []
        typeaheadHits = []
        searchContinuationToken = nil
        loadingMore = false
        requestToken &+= 1
        searchedTerm = term
        AddonSearchHistory.record(term)
        addonHistory = AddonSearchHistory.load()
        let task = Task { @MainActor in
            do {
                let found = try await SourceSearch.shared.addons(term)
                guard !Task.isCancelled else { return }
                addonHits = found
            } catch {
                guard !Task.isCancelled else { return }
                addonError = "Couldn't reach your sources. \(error.localizedDescription)"
                addonHits = []
            }
            searchingAddons = false
            searchTask = nil
        }
        searchTask = task
        _ = await task.value
    }




    private func loadMore() {
        guard let token = searchContinuationToken, !loadingMore, !searching else { return }
        loadingMore = true
        let term = searchedTerm
        let wanted = scope
        Task { @MainActor in
            defer { loadingMore = false }
            do {
                let page = try await InnertubeSearch.shared.searchContinuation(token, scope: wanted.rawValue)


                guard searchedTerm == term, scope == wanted, !Task.isCancelled else { return }
                let existing = Set(hits.map(\.id))
                let newRows = page.hits.filter { !existing.contains($0.id) }
                hits.append(contentsOf: newRows)




                searchContinuationToken = (page.continuation == token || newRows.isEmpty) ? nil : page.continuation
            } catch {


            }
        }
    }
}



private struct SearchCategoryTile: View {
    let category: MoodGenre

    private var accent: Color {
        let palette: [Color] = [
            Color(red: 0.91, green: 0.18, blue: 0.35),
            Color(red: 0.98, green: 0.47, blue: 0.12),
            Color(red: 0.91, green: 0.67, blue: 0.04),
            Color(red: 0.17, green: 0.59, blue: 0.47),
            Color(red: 0.08, green: 0.55, blue: 0.74),
            Color(red: 0.38, green: 0.40, blue: 0.83),
            Color(red: 0.66, green: 0.31, blue: 0.68),
            Color(red: 0.78, green: 0.23, blue: 0.19),
        ]
        let hash = category.title.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
        return palette[Int((UInt32(bitPattern: hash) & 0x7fff_ffff) % UInt32(palette.count))]
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottomLeading) {
                if let url = category.thumbnailUrl, let imageURL = URL(string: url) {
                    AsyncImage(url: imageURL) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            Rectangle().fill(accent.gradient)
                        }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                } else {
                    Rectangle().fill(accent.gradient)
                }

                accent.opacity(0.24)
                LinearGradient(
                    colors: [.clear, .black.opacity(0.62)],
                    startPoint: .center,
                    endPoint: .bottom
                )

                Text(category.title)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .shadow(color: .black.opacity(0.28), radius: 2, y: 1)
                    .padding(14)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(.white.opacity(0.12), lineWidth: 0.5)
            }
            .contentShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .aspectRatio(1.75, contentMode: .fit)
        .accessibilityLabel(category.title)
    }
}

private extension View {




    func searchRowChrome() -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
