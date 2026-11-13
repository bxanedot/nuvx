import AppIntents
import Foundation















@MainActor
final class AppServices {
    static let shared: AppServices = {


        tuneJavaScriptCore()
        return AppServices()
    }()












    nonisolated private static func tuneJavaScriptCore() {
        setenv("JSC_numberOfGCMarkers", "1", 0)
    }

    let controller = PlaybackController()
    let appModel = AppModel()
    let auth = AuthController()

    private init() {}








    func waitUntilReady() async {
        await LaunchReadiness.shared.waitForContent()
    }
}









enum NuvxIntentError: LocalizedError {
    case emptyQuery
    case nothingPlaying
    case signedOut
    case noResults(String)
    case refused(String)

    var errorDescription: String? {
        switch self {
        case .emptyQuery:
            "What should nuvx look for?"
        case .nothingPlaying:
            "Nothing is playing in Nuvx."
        case .signedOut:
            "Sign in to nuvx to like songs."
        case .noResults(let query):
            "nuvx couldn't find a song for “\(query)”."
        case .refused(let message):
            message
        }
    }
}










struct SongEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Song"
    static var defaultQuery = SongQuery()

    var videoId: String
    var title: String
    var artist: String

    var id: String {
        let parts = [videoId, title, artist]
        guard let data = try? JSONEncoder().encode(parts),
              let encoded = String(data: data, encoding: .utf8) else { return videoId }
        return encoded
    }

    var displayRepresentation: DisplayRepresentation {
        artist.isEmpty
            ? DisplayRepresentation(title: "\(title)")
            : DisplayRepresentation(title: "\(title)", subtitle: "\(artist)")
    }

    init(videoId: String, title: String, artist: String) {
        self.videoId = videoId
        self.title = title
        self.artist = artist
    }



    init?(id: String) {
        guard let data = id.data(using: .utf8),
              let parts = try? JSONDecoder().decode([String].self, from: data),
              parts.count == 3, !parts[0].isEmpty else { return nil }
        self.init(videoId: parts[0], title: parts[1], artist: parts[2])
    }
}





struct SongQuery: EntityStringQuery {
    func entities(for identifiers: [SongEntity.ID]) async throws -> [SongEntity] {
        identifiers.compactMap { SongEntity(id: $0) }
    }

    func entities(matching string: String) async throws -> [SongEntity] {
        let term = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return [] }
        let hits = try await InnertubeSearch.shared.search(term, scope: "songs")
        var seen = Set<String>()
        return hits
            .filter { $0.isTrack && !$0.id.isEmpty && seen.insert($0.id).inserted }
            .prefix(10)
            .map { SongEntity(videoId: $0.id, title: $0.title, artist: $0.resolvedArtist) }
    }








    func suggestedEntities() async throws -> [SongEntity] {
        await CachedSongs.suggestions()
    }
}







@MainActor
private enum CachedSongs {
    private static let limit = 50

    static func suggestions() -> [SongEntity] {
        var seen = Set<String>()
        var songs: [SongEntity] = []
        for entry in LastPlayed.load()?.tracks ?? [] {
            guard let videoId = entry.videoId,
                  !videoId.isEmpty,
                  !entry.title.isEmpty,
                  seen.insert(videoId).inserted else { continue }
            songs.append(SongEntity(videoId: videoId, title: entry.title, artist: entry.artist))
            if songs.count == limit { break }
        }
        return songs
    }
}








@MainActor
enum AppIntentActions {

    static func togglePlayback() async throws -> String {
        let controller = AppServices.shared.controller
        guard let current = controller.current else { throw NuvxIntentError.nothingPlaying }



        let wasPlaying = controller.isPlaying || controller.isBuffering
        controller.togglePlayPause()
        return wasPlaying ? "Paused \(current.title)" : "Playing \(current.title)"
    }









    static func play(song: SongEntity) async throws -> String {
        await AppServices.shared.waitUntilReady()
        let controller = AppServices.shared.controller
        let entries = (try? await tracks(matching: "\(song.title) \(song.artist)")) ?? []
        if let start = entries.firstIndex(where: { $0.id == song.videoId }) {
            controller.play(entries, at: start, context: "Siri")
        } else {
            let entry = QueueEntry.youtube(
                videoId: song.videoId,
                title: song.title,
                artist: song.artist
            )
            controller.play([entry], at: 0, context: "Siri")
        }
        return song.title
    }



    static func search(query: String) throws -> String {
        let term = try cleaned(query)
        let appModel = AppServices.shared.appModel
        appModel.pendingSearchQuery = term
        appModel.requestedTab = .search
        appModel.focusSearch = true
        return term
    }


    static func likeCurrent() async throws -> String {
        let services = AppServices.shared
        guard services.auth.signedIn else { throw NuvxIntentError.signedOut }
        guard let current = services.controller.current, let videoId = current.videoId else {
            throw NuvxIntentError.nothingPlaying
        }
        if let failure = await services.controller.toggleLikeAndWait(videoId: videoId) {
            throw NuvxIntentError.refused(failure)
        }
        return services.controller.isLiked
            ? "Liked \(current.title)"
            : "Removed \(current.title) from Liked Music"
    }




    static func addToQueue(song: SongEntity) async throws -> String {
        await AppServices.shared.waitUntilReady()
        let entry = QueueEntry.youtube(
            videoId: song.videoId,
            title: song.title,
            artist: song.artist
        )
        AppServices.shared.controller.addToQueue(entry)
        return song.title
    }






    private static func tracks(matching term: String) async throws -> [QueueEntry] {
        let hits = try await InnertubeSearch.shared.search(term, scope: "songs")
        var seen = Set<String>()
        return hits
            .filter { $0.isTrack && !$0.id.isEmpty && seen.insert($0.id).inserted }
            .prefix(10)
            .map { $0.asEntry() }
    }

    private static func cleaned(_ query: String) throws -> String {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { throw NuvxIntentError.emptyQuery }
        return term
    }
}





struct PlayPauseIntent: AppIntent {
    static var title: LocalizedStringResource = "Play or Pause"
    static var description = IntentDescription("Play or pause what nuvx is playing.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let line = try await AppIntentActions.togglePlayback()
        return .result(dialog: "\(line)")
    }
}




struct PlaySongIntent: AppIntent {
    static var title: LocalizedStringResource = "Play a Song"
    static var description = IntentDescription("Search nuvx for a song and start playing it.")
    static var openAppWhenRun = true

    @Parameter(title: "Song", requestValueDialog: "Which song?")
    var song: SongEntity

    init() {}

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$song)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let title = try await AppIntentActions.play(song: song)
        return .result(dialog: "Playing \(title) in Nuvx")
    }
}



struct SearchIntent: AppIntent {
    static var title: LocalizedStringResource = "Search"
    static var description = IntentDescription("Open nuvx's search with a query.")
    static var openAppWhenRun = true

    @Parameter(title: "Search For", requestValueDialog: "What should nuvx search for?")
    var query: String

    init() {}

    init(query: String) {
        self.query = query
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Search for \(\.$query)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let term = try await AppIntentActions.search(query: query)
        return .result(dialog: "Searching nuvx for \(term)")
    }
}




struct LikeCurrentSongIntent: AppIntent {
    static var title: LocalizedStringResource = "Like the Current Song"
    static var description = IntentDescription("Like or unlike what is playing in Nuvx.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let line = try await AppIntentActions.likeCurrent()
        return .result(dialog: "\(line)")
    }
}


struct AddToQueueIntent: AppIntent {
    static var title: LocalizedStringResource = "Add to the Queue"
    static var description = IntentDescription("Find a song and add it to the end of nuvx's queue.")
    static var openAppWhenRun = true

    @Parameter(title: "Song", requestValueDialog: "Which song?")
    var song: SongEntity

    init() {}

    static var parameterSummary: some ParameterSummary {
        Summary("Add \(\.$song) to the queue")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let title = try await AppIntentActions.addToQueue(song: song)
        return .result(dialog: "Added \(title) to the queue")
    }
}








struct NuvxShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PlaySongIntent(),
            phrases: [
                "Play \(\.$song) in \(.applicationName)",
                "Play \(\.$song) on \(.applicationName)"
            ],
            shortTitle: "Play a Song",
            systemImageName: "play.fill"
        )
        AppShortcut(
            intent: PlayPauseIntent(),
            phrases: [
                "Pause \(.applicationName)",
                "Play or pause \(.applicationName)"
            ],
            shortTitle: "Play or Pause",
            systemImageName: "pause.fill"
        )
        AppShortcut(
            intent: SearchIntent(),
            phrases: ["Search in \(.applicationName)"],
            shortTitle: "Search",
            systemImageName: "magnifyingglass"
        )
        AppShortcut(
            intent: LikeCurrentSongIntent(),
            phrases: ["Like this song in \(.applicationName)"],
            shortTitle: "Like This Song",
            systemImageName: "heart.fill"
        )
        AppShortcut(
            intent: AddToQueueIntent(),
            phrases: ["Add \(\.$song) to the queue in \(.applicationName)"],
            shortTitle: "Add to Queue",
            systemImageName: "text.badge.plus"
        )
    }
}







@MainActor
enum AppShortcutRefresh {
    private static var lastUpdate = Date.distantPast
    private static let interval: TimeInterval = 300

    static func schedule() {
        let now = Date()
        guard now.timeIntervalSince(lastUpdate) >= interval else { return }
        lastUpdate = now
        NuvxShortcuts.updateAppShortcutParameters()
    }
}
