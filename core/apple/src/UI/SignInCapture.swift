import Foundation
import Observation










struct SignInCapture: Equatable {

    var cookie: String

    var pageId: String?

    var dataSyncId: String?

    var authUser: String?
    var visitorData: String?
    var clientVersion: String?

    var loggedIn: Bool










    static func parse(jsResult raw: Any?) -> SignInCaptureFields? {
        if let dict = raw as? [String: Any] {
            return fields(from: dict)
        }
        if let text = raw as? String,
           let data = text.data(using: .utf8),
           let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        {
            return fields(from: object)
        }
        return nil
    }

    private static func fields(from dict: [String: Any]) -> SignInCaptureFields {
        SignInCaptureFields(
            loggedIn: string(dict["loggedIn"]) == "true",
            pageId: nonBlank(string(dict["pageId"])),
            dataSyncId: nonBlank(string(dict["dataSyncId"])),
            authUser: nonBlank(string(dict["authUser"])),
            visitorData: nonBlank(string(dict["visitorData"])),
            clientVersion: nonBlank(string(dict["clientVersion"]))
        )
    }




    private static func string(_ value: Any?) -> String? {
        guard let value else { return nil }
        if value is NSNull { return nil }
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }




    static func normalizeDataSyncId(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else { return nil }
        guard let split = value.range(of: "||") else { return value }
        let after = String(value[split.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        if !after.isEmpty { return after }
        let before = String(value[..<split.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        return before.isEmpty ? nil : before
    }
}


struct SignInCaptureFields: Equatable {
    var loggedIn: Bool
    var pageId: String?
    var dataSyncId: String?
    var authUser: String?
    var visitorData: String?
    var clientVersion: String?
}



struct SignInProfilePreview: Equatable {
    let name: String
    let subtitle: String?
    let avatarURL: URL?

    var initials: String {
        String(name.split(whereSeparator: \.isWhitespace).prefix(2).compactMap { $0.first }.map { String($0) }.joined()).uppercased()
    }

    static func parse(_ data: Data) -> SignInProfilePreview? {
        guard let root = try? JSONSerialization.jsonObject(with: data),
              let header = accountHeader(in: root),
              let name = text(header["accountName"]) else { return nil }
        let subtitle = text(header["email"]) ?? text(header["channelHandle"])
        let photos = (header["accountPhoto"] as? [String: Any])?["thumbnails"] as? [[String: Any]] ?? []
        let photo = photos.sorted { ($0["width"] as? Int ?? 0) > ($1["width"] as? Int ?? 0) }
            .compactMap { safeAvatarURL($0["url"] as? String) }.first
        return SignInProfilePreview(name: name, subtitle: subtitle, avatarURL: photo)
    }

    private static func accountHeader(in value: Any) -> [String: Any]? {
        if let object = value as? [String: Any] {
            if let header = object["activeAccountHeaderRenderer"] as? [String: Any] { return header }
            for child in object.values { if let header = accountHeader(in: child) { return header } }
        } else if let array = value as? [Any] {
            for child in array { if let header = accountHeader(in: child) { return header } }
        }
        return nil
    }

    private static func text(_ value: Any?) -> String? {
        guard let object = value as? [String: Any] else { return nil }
        let runs = (object["runs"] as? [[String: Any]])?.compactMap { $0["text"] as? String }.joined()
        let value = ((runs?.isEmpty == false ? runs : object["simpleText"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    static func safeAvatarURL(_ raw: String?) -> URL? {
        guard let raw, let url = URL(string: raw.hasPrefix("//") ? "https:" + raw : raw),
              url.scheme == "https", url.user == nil, url.password == nil,
              let host = url.host?.lowercased(),
              ["googleusercontent.com", "ggpht.com", "ytimg.com"].contains(where: { host == $0 || host.hasSuffix("." + $0) })
        else { return nil }
        return url
    }
}



struct SignInProfileScope: Equatable {
    let pageId: String?
    let dataSyncId: String?
    let authUser: String?
    init(_ fields: SignInCaptureFields) {
        pageId = fields.pageId
        dataSyncId = fields.pageId ?? SignInCapture.normalizeDataSyncId(fields.dataSyncId)
        authUser = fields.authUser
    }
}




@Observable
final class LoginFlow {
    private(set) var reachedMusicOrigin = false
    private(set) var signedInProfileAvailable = false
    private(set) var choosingProfile = false
    private(set) var checkingPage = false
    var taking = false
    var profile: SignInProfilePreview?
    var profileScope: SignInProfileScope?
    var loadingProfile = false
    var captureFailedMessage: String?
    var refusal: SignInNavigation.Refusal?
    var offOriginHost: String?
    let session = SignInSession()

    var pageReady: Bool {



        reachedMusicOrigin && !checkingPage && !loadingProfile && !taking
    }

    var showsCompletion: Bool {
        taking || (reachedMusicOrigin && !choosingProfile && (checkingPage || signedInProfileAvailable))
    }

    func navigating(to url: URL?) {
        reachedMusicOrigin = SignInNavigation.isMusicOrigin(url)
        signedInProfileAvailable = false
        checkingPage = reachedMusicOrigin
        captureFailedMessage = nil
        profile = nil
        profileScope = nil
        loadingProfile = false
        offOriginHost = SignInNavigation.isSignInHost(url) ? nil : url?.host
        refusal = nil
    }

    func inspectedPage(loggedIn: Bool) {
        checkingPage = false
        signedInProfileAvailable = reachedMusicOrigin && loggedIn
    }

    func chooseProfile() {
        choosingProfile = true
        profile = nil
        profileScope = nil
        captureFailedMessage = nil
    }

    func captureFailed(_ reason: String) {
        taking = false
        captureFailedMessage = reason
    }

    func captureSucceeded() {
        taking = false
        captureFailedMessage = nil
    }

    var prompt: String? {
        if let captureFailedMessage { return captureFailedMessage }
        if let refusal { return refusal.summary }
        if reachedMusicOrigin {
            return signedInProfileAvailable
                ? "Choose your profile using the avatar, then tap Use This Profile to finish signing in to Nuvx."
                : "Finish signing in and selecting your profile, then tap Use This Profile."
        }
        if let offOriginHost { return SignInNavigation.offSignInHostAdvice(host: offOriginHost) }
        return nil
    }
}



final class SignInSession {
    var take: (() -> Void)?
}
