import SwiftUI
import Observation
import NuvxShared

extension Notification.Name {
    static let partyMembershipDidChange = Notification.Name("NuvxPartyMembershipDidChange")
}














@MainActor
@Observable
final class PartyStore {







    private(set) var state: PartyState = PartyCoordinator.shared.session.current {
        didSet {
            guard oldValue.inParty != state.inParty else { return }
            NotificationCenter.default.post(name: .partyMembershipDidChange, object: nil)
        }
    }






    private(set) var server: ServerChoice?




    private(set) var busy = false






    var failure: String?







    var pendingPreview: PartyPreview?


    private(set) var avatarUrl: String?






    @ObservationIgnored private var playerBinding: PartySync?


    var serverAnswered: Bool {
        guard let server else { return false }
        return server.connection is ServerConnectionCustomOnline
            || server.connection is ServerConnectionDefaultOnline
    }














    func setForCheck(_ state: PartyState, code: String) {
        self.state = state
        self.checkCode = code
        self.checkStateInstalled = true
    }

    @ObservationIgnored private var checkCode = ""
    @ObservationIgnored private var checkStateInstalled = false


    private static var installed = false




    func attach(player: PartySync?) {
        playerBinding = player
        reconcilePlayerBinding()
    }







    private func reconcilePlayerBinding() {
        if PartyCoordinator.shared.membership != nil {
            playerBinding?.start()
        } else {
            playerBinding?.stop()
        }
    }







    static func install() {
        guard !installed else { return }
        installed = true
        PartyCoordinator.shared.localNowMs = { KotlinLong(value: PartySocket.localNowMs()) }
        PartyCoordinator.shared.onStateChanged = { newState in


            Task { @MainActor in
                shared.state = newState
                shared.reconcilePlayerBinding()
            }
        }
        shared.syncFromCoordinator()
    }

    static let shared = PartyStore()


    func syncFromCoordinator() {
        if checkStateInstalled { return }
        state = PartyCoordinator.shared.session.current
        if let value = PartyCoordinator.shared.server.value as? ServerChoice { server = value }
        if let value = PartyCoordinator.shared.busy.value as? Bool { busy = value }
        avatarUrl = PartyAccountCache.avatarUrl
        reconcilePlayerBinding()
    }


    func accountChanged() {
        let account = PartyAccountCache.account
        avatarUrl = account?.avatarUrl
        AppSettings.shared.setPartyAccount(
            value: account.map {
                PartyAccount(name: $0.name, email: $0.email, avatarUrl: $0.avatarUrl)
            }
        )
    }








    var partyPositionMs: Int64? {
        let session = PartyCoordinator.shared.session
        let playback = session.current.playback
        guard session.current.clockSynced else { return nil }
        return session.correctedPosition(playback: playback, localNowMs: PartySocket.localNowMs())
    }







    var hasServer: Bool { !PartyCoordinator.shared.defaultServer().isEmpty }








    var code: String {
        let live = PartyCoordinator.shared.session.code
        return live.isEmpty ? checkCode : live
    }

    var inParty: Bool { state.inParty }

    var isHost: Bool { state.isHost }






    var isLive: Bool { state.connection == PartyConnection.live }


    var accountName: String { PartyAccountCache.account?.name ?? "" }





    var nickname: String {
        let stored = PlatformSettings.shared.getString(key: "listen_together_nickname", default: "")
        return stored.isEmpty ? accountName : stored
    }

    func setNickname(_ value: String) {
        AppSettings.shared.setListenTogetherNickname(value: value)
    }


    var inviteLink: String? { PartyCoordinator.shared.inviteLink() }




    var configuredServer: String {
        PlatformSettings.shared.getString(key: "listen_together_server", default: "")
    }

    func setConfiguredServer(_ value: String) {
        AppSettings.shared.setListenTogetherServer(value: value)
    }


    func serverProblem(_ raw: String) -> String? { PartyCoordinator.shared.serverProblem(raw: raw) }

    func resolveServer() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            PartyCoordinator.shared.resolveServer { _ in
                Task { @MainActor in
                    self.syncFromCoordinator()
                    continuation.resume()
                }
            }
        }
    }



    func create(nickname: String, autoplay: Bool) async throws {
        setNickname(nickname)
        do {
            _ = try await createMembership(nickname: nickname, autoplay: autoplay)
            failure = nil
        } catch {
            failure = Self.describe(error)
            throw error
        }
    }

    func join(code: String, nickname: String) async throws {
        setNickname(nickname)
        do {
            _ = try await joinMembership(code: code, nickname: nickname)
            failure = nil
        } catch {
            failure = Self.describe(error)
            throw error
        }
    }

    func joinInvite(link: String, nickname: String) async throws {
        setNickname(nickname)
        do {
            _ = try await joinInviteMembership(link: link, nickname: nickname)
            failure = nil
        } catch {
            failure = Self.describe(error)
            throw error
        }
    }





    @discardableResult
    func preview(code: String) async throws -> PartyPreview {
        do {
            let value = try await previewParty(code: code)
            failure = nil
            pendingPreview = value
            return value
        } catch {
            failure = Self.describe(error)
            pendingPreview = nil
            throw error
        }
    }

    func leave() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            PartyCoordinator.shared.leave { _ in
                Task { @MainActor in
                    self.syncFromCoordinator()
                    continuation.resume()
                }
            }
        }
        failure = nil
    }



    func play() { PartyCoordinator.shared.play() }
    func pause() { PartyCoordinator.shared.pause() }
    func next() { PartyCoordinator.shared.next() }
    func previous() { PartyCoordinator.shared.previous() }
    func seek(toMs ms: Int64) { PartyCoordinator.shared.seek(positionMs: ms) }
    func setMaxMembers(_ count: Int32) { PartyCoordinator.shared.setMaxMembers(count: count) }
    func setHostOnlyControl(_ on: Bool) { PartyCoordinator.shared.setHostOnlyControl(enabled: on) }
    func kick(_ memberId: String) { PartyCoordinator.shared.kick(memberId: memberId) }
    func addToPartyQueue(_ entry: QueueEntry) { PartyCoordinator.shared.queueAdd(tracks: [entry.asPartyTrack()], playNext: false) }
    func removeFromPartyQueue(_ videoId: String) { PartyCoordinator.shared.queueRemove(videoId: videoId) }


















    @discardableResult
    func playInParty(_ entry: QueueEntry) -> Bool {
        let track = entry.asPartyTrack()
        guard PartyCoordinator.shared.setTrack(track: track) else { return false }
        PartyCoordinator.shared.queueAdd(tracks: [track], playNext: false)
        return true
    }










    static func describe(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == "KotlinException" {
            let said = ns.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if !said.isEmpty { return said }
        }
        if let url = ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL {
            return "Couldn’t reach the party server at \(url.host() ?? "that address")."
        }
        return "Couldn’t reach the party server."
    }
}











private func createMembership(nickname: String, autoplay: Bool) async throws -> PartyMembership {
    try await withCheckedThrowingContinuation { continuation in
        PartyCoordinator.shared.create(nickname: nickname, autoplay: autoplay) { value, error in
            if let error { continuation.resume(throwing: error) }
            else if let value { continuation.resume(returning: value) }
            else { continuation.resume(throwing: PartyStoreError.noValue) }
        }
    }
}

private func joinMembership(code: String, nickname: String) async throws -> PartyMembership {
    try await withCheckedThrowingContinuation { continuation in
        PartyCoordinator.shared.join(code: code, nickname: nickname) { value, error in
            if let error { continuation.resume(throwing: error) }
            else if let value { continuation.resume(returning: value) }
            else { continuation.resume(throwing: PartyStoreError.noValue) }
        }
    }
}

private func joinInviteMembership(link: String, nickname: String) async throws -> PartyMembership {
    try await withCheckedThrowingContinuation { continuation in
        PartyCoordinator.shared.joinInvite(link: link, nickname: nickname) { value, error in
            if let error { continuation.resume(throwing: error) }
            else if let value { continuation.resume(returning: value) }
            else { continuation.resume(throwing: PartyStoreError.noValue) }
        }
    }
}

private func previewParty(code: String) async throws -> PartyPreview {
    try await withCheckedThrowingContinuation { continuation in
        PartyCoordinator.shared.preview(code: code) { value, error in
            if let error { continuation.resume(throwing: error) }
            else if let value { continuation.resume(returning: value) }
            else { continuation.resume(throwing: PartyStoreError.noValue) }
        }
    }
}

enum PartyStoreError: Error {




    case noValue
}





enum PartyAccountCache {
    private static var stored: PartyAccountCacheValue?

    struct PartyAccountCacheValue {
        let name: String
        let email: String
        let avatarUrl: String?
    }

    static var account: PartyAccountCacheValue? { stored }

    static var avatarUrl: String? { stored?.avatarUrl }

    static func update(name: String?, email: String?, avatarUrl: String?) {
        guard let name, !name.isEmpty else {
            stored = nil
            return
        }
        stored = PartyAccountCacheValue(
            name: name,
            email: email ?? "",
            avatarUrl: avatarUrl.flatMap { $0.hasPrefix("http") ? $0 : nil }
        )
    }
}

extension QueueEntry {







    func asPartyTrack() -> PartyTrack {
        PartyTrack(
            videoId: videoId ?? id,
            title: title,
            artist: artist,
            thumbnailUrl: thumbnailUrl,
            durationMs: durationSeconds > 0 ? KotlinLong(value: Int64(durationSeconds * 1000)) : nil,
            fromAutoplay: fromAutoplay
        )
    }








    static func asPartyTrack(_ track: PartyTrack) -> QueueEntry {
        QueueEntry(
            id: track.videoId,
            title: track.title,
            artist: track.artist,
            source: "yt:" + track.videoId,
            thumbnailUrl: track.thumbnailUrl,
            durationText: nil,
            albumName: nil,
            artworkData: nil,
            isLocal: false,
            fromAutoplay: track.fromAutoplay
        )
    }
}
