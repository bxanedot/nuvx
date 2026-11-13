import Foundation
import Observation
import NuvxShared
#if os(iOS)
import UIKit
#endif




@MainActor @Observable
final class AuthController {
    var signedIn = false
    var accountName: String?
    var accountEmail: String?
    var accountPhotoUrl: String?
    var loginPresented = false
    private(set) var sharedSessionOffer: SharedSessionCredential?

    var sessionEpoch = 0 {
        didSet { PageSession.reset(); LikeStore.shared.clear() }
    }

    private var sessionExpiredObserver: NSObjectProtocol?
    private var sharedSessionPublishingEnabled: Bool {
        !UserDefaults.standard.bool(forKey: "shared_session_sync_suppressed")
    }

    init() {


        SecretStoreWiring.install()


        restore()


        sessionExpiredObserver = NotificationCenter.default.addObserver(
            forName: .sessionExpired, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.noteSessionExpired() }
        }
    }







    var sessionUnavailableReason: String?

    func restore() {
        sessionUnavailableReason = nil
        AccountStore.shared.restore()


        guard let cookie = AccountStore.shared.activeAccount()?.cookie ?? AuthStore.cookie else {
            if AuthStore.isLocked {
                sessionUnavailableReason =
                    "Your session is saved but this device has not been unlocked yet."
            } else if let offer = AuthStore.sharedSession {
                sharedSessionOffer = offer


                loginPresented = true
            }
            return
        }
        guard AuthBridge.shared.applyCookie(cookieHeader: cookie) else {














            sessionUnavailableReason =
                "Your saved session could not be restored. Sign in again to continue."
            return
        }
        signedIn = true
        sessionEpoch += 1
        refreshAccounts()
        Task { await refreshAccount() }
    }









    private func publishAccount() {
        MainActor.assumeIsolated {
            PartyAccountCache.update(
                name: accountName,
                email: accountEmail,
                avatarUrl: accountPhotoUrl
            )
            PartyStore.shared.accountChanged()
        }
    }












    func accept(_ session: SignInCapture, onComplete: ((Bool) -> Void)? = nil) {
        let previousCookie = AccountStore.shared.activeAccount()?.cookie ?? AuthStore.cookie
        guard session.loggedIn,
              AuthBridge.shared.hasApiSid(cookieHeader: session.cookie),
              AuthBridge.shared.applyCookie(cookieHeader: session.cookie)
        else {



            sessionUnavailableReason =
                "Google gave a session this app could not use. Try signing in again."
            onComplete?(false)
            return
        }
        AuthBridge.shared.adoptSessionScope(
            pageId: session.pageId,
            dataSyncId: session.dataSyncId,
            authUser: session.authUser,
            visitorData: session.visitorData,
            clientVersion: session.clientVersion,
            loggedIn: true
        )
        let generation = PageSession.generation()
        AuthBridge.shared.ensureSession(callback: SessionDoneAdapter { ok, _ in
            guard ok else {
                Task { @MainActor in
                    guard PageSession.generation() == generation else { onComplete?(false); return }
                    self.restorePreviousSession(cookie: previousCookie)
                    self.sessionUnavailableReason =
                        "Google gave a session this app could not use. Try signing in again."
                    onComplete?(false)
                }
                return
            }
            AccountBridge.shared.account(callback: AccountCallbackAdapter { json, _ in
                Task { @MainActor in
                    guard PageSession.generation() == generation else { onComplete?(false); return }
                    guard let json,
                          let info = try? JSONDecoder().decode(AccountInfo.self, from: Data(json.utf8))
                    else {
                        self.restorePreviousSession(cookie: previousCookie)
                        self.sessionUnavailableReason =
                            "Google gave a session this app could not use. Try signing in again."
                        onComplete?(false)
                        return
                    }



                    let id = self.accounts.first(where: {
                        !info.email.isEmpty && $0.email.caseInsensitiveCompare(info.email) == .orderedSame
                    })?.id ?? AccountSessionsKt.sessionIdOf(
                        cookie: session.cookie, dataSyncId: session.dataSyncId
                    )
                    let profileId = AccountSessionsKt.profileIdOf(
                        pageId: session.pageId, dataSyncId: session.dataSyncId, name: info.name
                    )
                    guard self.record(account: AccountSummary(
                        id: id,
                        name: info.name,
                        email: info.email,
                        cookie: session.cookie,
                        profiles: [AccountProfile(
                            profileId: profileId,
                            name: info.name,
                            pageId: session.pageId,
                            dataSyncId: session.dataSyncId,
                            authUser: session.authUser
                        )]
                    )) else {
                        self.restorePreviousSession(cookie: previousCookie)
                        self.sessionUnavailableReason =
                            "Your account was verified, but its session could not be saved. Try again."
                        onComplete?(false)
                        return
                    }


                    _ = AuthStore.save(session.cookie)
                    self.sessionUnavailableReason = nil
                    UserDefaults.standard.removeObject(forKey: "shared_session_sync_suppressed")
                    self.signedIn = true
                    self.sessionEpoch += 1
                    self.accountName = info.name
                    self.accountEmail = info.email
                    self.accountPhotoUrl = info.photoUrl
                    self.publishAccount()
                    Task { await self.refreshAccount() }
                    onComplete?(true)
                }
            })
        })
    }



    func adoptSharedSessionOffer(onComplete: @escaping (Bool) -> Void) {
        guard let offer = sharedSessionOffer, offer.isAdoptable else {
            onComplete(false)
            return
        }
        accept(offer.signInCapture) { [weak self] accepted in
            guard let self else { onComplete(false); return }
            if accepted { self.sharedSessionOffer = nil }
            onComplete(accepted)
        }
    }



    func dismissSharedSessionOffer() {
        sharedSessionOffer = nil
    }

    private func restorePreviousSession(cookie: String?) {
        if AccountStore.shared.activeAccount() != nil {
            AccountStore.shared.restore()
        } else {
            _ = AuthBridge.shared.applyCookie(cookieHeader: cookie)
            AuthBridge.shared.clearChannelOverride()
        }
    }




    static func isSessionExpired(_ message: String?) -> Bool {
        AuthBridge.shared.isSessionExpired(message: message)
    }



    @discardableResult
    static func reportIfSessionExpired(_ error: Error, message: String? = nil) -> Bool {
        let text = message ?? error.localizedDescription
        guard isSessionExpired(text) else { return false }
        NotificationCenter.default.post(name: .sessionExpired, object: nil)
        return true
    }








    func noteSessionExpired() {
        guard signedIn else { return }
        _ = AuthBridge.shared.applyCookie(cookieHeader: nil)
        signedIn = false
        sessionUnavailableReason = "Your session has expired. Sign in again to continue."
        sessionEpoch += 1
    }

    func signOut() {




        guard AuthStore.clearSharedSession() else {
            sessionUnavailableReason = "The shared session could not be removed from iCloud Keychain. Try again after unlocking this device."
            return
        }
        guard AuthStore.clear() else {
            sessionUnavailableReason = "The session could not be removed from Keychain. Try again after unlocking this device."
            return
        }
        guard AccountStore.shared.forget(accountId: AccountStore.shared.activeAccount()?.accountId ?? "") else {
            sessionUnavailableReason = "The account could not be removed from Keychain. Try again after unlocking this device."
            return
        }
        UserDefaults.standard.set(true, forKey: "shared_session_sync_suppressed")
        Task { await PageRequestCoordinator.shared.invalidate() }
        if let remaining = AccountStore.shared.activeAccount() {
            AccountStore.shared.restore()
            _ = AuthStore.save(remaining.cookie)
            signedIn = true
            sessionEpoch += 1
            refreshAccounts()
            accountName = listeningAs?.displayName
            accountEmail = listeningAs?.email
            accountPhotoUrl = listeningAs?.activeProfile?.avatar
            publishAccount()
            Task { await refreshAccount() }
            return
        }
        _ = AuthBridge.shared.applyCookie(cookieHeader: nil)
        signedIn = false
        accountName = nil
        accountEmail = nil
        accountPhotoUrl = nil
        sessionUnavailableReason = nil
        sessionEpoch += 1
        refreshAccounts()
        publishAccount()
    }








    private(set) var accounts: [AccountSummary] = []




    private(set) var listeningAs: AccountSummary?


    @discardableResult
    func record(account: AccountSummary) -> Bool {



        let profiles = account.profiles.map { profile in
            YouTubeProfile(
                profileId: profile.id,
                name: profile.name,
                handle: profile.handle,
                avatar: profile.avatar,
                pageId: profile.pageId,
                dataSyncId: profile.dataSyncId,
                authUser: profile.authUser,
                isBrandAccount: false
            )
        }
        let saved = AccountStore.shared.record(
            accountId: account.id,
            cookie: account.cookie,
            name: account.name,
            email: account.email,
            profiles: profiles
        )
        refreshAccounts()
        return saved
    }






    func select(accountId: String?, profileId: String?) {
        let previous = AccountStore.shared.activeSelection()
        AccountStore.shared.select(accountId: accountId, profileId: profileId)
        refreshAccounts()
        if previous?.account.accountId != AccountStore.shared.activeSelection()?.account.accountId
            || previous?.profile.profileId != AccountStore.shared.activeSelection()?.profile.profileId {
            sessionEpoch += 1
            accountName = listeningAs?.displayName
            accountEmail = listeningAs?.email
            accountPhotoUrl = listeningAs?.activeProfile?.avatar
            publishAccount()
            Task { await refreshAccount() }
        }
    }



    @discardableResult
    func stepProfile(forward: Bool) -> Bool {
        let moved = AccountStore.shared.step(forward: forward)
        if moved {
            refreshAccounts()
            sessionEpoch += 1
            accountName = listeningAs?.displayName
            accountEmail = listeningAs?.email
            accountPhotoUrl = listeningAs?.activeProfile?.avatar
            publishAccount()
            Task { await refreshAccount() }
        }
        return moved
    }

    private func refreshAccounts() {
        let summaries: [AccountSummary] = AccountStore.shared.accounts().map { AccountSummary($0) }
        accounts = summaries
        if let selection = AccountStore.shared.activeSelection() {
            listeningAs = AccountSummary(selection.account, active: selection.profile)
        } else {
            listeningAs = summaries.first
        }
    }

    private func refreshAccount() async {
        let epoch = sessionEpoch
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            AuthBridge.shared.ensureSession(callback: SessionDoneAdapter { ok, message in
                guard ok else {
                    Task { @MainActor in
                        if self.sessionEpoch == epoch { self.sessionUnavailableReason = message ?? "Your saved session could not be verified." }
                        cont.resume()
                    }
                    return
                }
                AccountBridge.shared.account(callback: AccountCallbackAdapter { json, message in
                    Task { @MainActor in
                        if self.signedIn, self.sessionEpoch == epoch,
                           let json, let info = try? JSONDecoder().decode(AccountInfo.self, from: Data(json.utf8)) {
                            self.accountName = info.name
                            self.accountEmail = info.email
                            self.accountPhotoUrl = info.photoUrl
                            self.sessionUnavailableReason = nil
                            self.publishAccount()
                            self.publishSharedSession(info: info)
                        } else if self.signedIn, self.sessionEpoch == epoch {
                            self.sessionUnavailableReason = message ?? "Your saved session could not be verified."
                        }
                        cont.resume()
                    }
                })
            })
        }
    }



    private func publishSharedSession(info: AccountInfo) {
        guard sharedSessionPublishingEnabled,
              let account = AccountStore.shared.activeAccount(),
              !account.cookie.isEmpty
        else { return }
        let profile = listeningAs?.activeProfile
        let credential = SharedSessionCredential(
            schemaVersion: SharedSessionCredential.currentSchemaVersion,
            cookie: account.cookie,
            accountName: info.name,
            email: info.email,
            avatarURL: info.photoUrl,
            pageId: profile?.pageId,
            dataSyncId: profile?.dataSyncId,
            authUser: profile?.authUser,
            deviceName: Self.localDeviceName,
            createdAt: Date()
        )
        _ = AuthStore.saveSharedSession(credential)
    }

    private static var localDeviceName: String {
        #if os(iOS)
        let name = UIDevice.current.name
        #else
        let name = ProcessInfo.processInfo.hostName
        #endif
        return name.isEmpty ? "This device" : name
    }
}

private final class SessionDoneAdapter: AuthBridgeDoneCallback {
    private let onResult: (Bool, String?) -> Void
    init(onResult: @escaping (Bool, String?) -> Void) { self.onResult = onResult }
    func onResult(ok: Bool, message: String?) { onResult(ok, message) }
}

private final class IdentityDoneAdapter: AuthBridgeIdentityCallback {
    private let onResult: (String?, String?, String?) -> Void
    init(onResult: @escaping (String?, String?, String?) -> Void) { self.onResult = onResult }
    func onResult(pageId: String?, dataSyncId: String?, authUser: String?) {
        onResult(pageId, dataSyncId, authUser)
    }
}

private final class AccountCallbackAdapter: AccountBridgeAccountCallback {
    private let onResult: (String?, String?) -> Void
    init(onResult: @escaping (String?, String?) -> Void) { self.onResult = onResult }
    func onResult(json: String?, message: String?) { onResult(json, message) }
}

private struct AccountInfo: Decodable {
    let name: String
    let email: String
    let photoUrl: String?
}







struct AccountSummary: Identifiable, Hashable {
    let id: String
    let name: String
    let email: String
    let cookie: String
    let profiles: [AccountProfile]




    var activeProfileId: String? = nil

    init(_ session: GoogleAccountSession, active: YouTubeProfile? = nil) {
        id = session.accountId
        name = session.name
        email = session.email
        cookie = session.cookie
        profiles = session.profiles.map(AccountProfile.init)
        activeProfileId = (active ?? session.profiles.first)?.profileId
    }

    init(
        id: String, name: String, email: String, cookie: String,
        profiles: [AccountProfile], activeProfileId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.email = email
        self.cookie = cookie
        self.profiles = profiles
        self.activeProfileId = activeProfileId
    }




    var displayName: String {
        if !name.isEmpty { return name }
        if !email.isEmpty { return email }
        return String(id.prefix(8))
    }

    var activeProfile: AccountProfile? {
        profiles.first { $0.id == activeProfileId } ?? profiles.first
    }
}


struct AccountProfile: Identifiable, Hashable {
    let id: String
    let name: String
    let handle: String
    let avatar: String?
    let pageId: String?
    let dataSyncId: String?
    let authUser: String?

    init(
        profileId: String, name: String, handle: String = "", avatar: String? = nil,
        pageId: String? = nil, dataSyncId: String? = nil, authUser: String? = nil
    ) {
        id = profileId
        self.name = name
        self.handle = handle
        self.avatar = avatar
        self.pageId = pageId
        self.dataSyncId = dataSyncId
        self.authUser = authUser
    }

    init(_ profile: YouTubeProfile) {
        id = profile.profileId
        name = profile.name
        handle = profile.handle
        avatar = profile.avatar
        pageId = profile.pageId
        dataSyncId = profile.dataSyncId
        authUser = profile.authUser
    }

    var subtitle: String {
        handle.isEmpty ? name : handle
    }
}
