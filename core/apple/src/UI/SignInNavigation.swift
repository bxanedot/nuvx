import Foundation



























































enum SignInNavigation {



    static func isMusicOrigin(_ url: URL?) -> Bool {
        url?.scheme?.lowercased() == "https"
            && url?.host?.lowercased() == "music.youtube.com"
            && (url?.port == nil || url?.port == 443)
            && url?.user == nil && url?.password == nil
    }

    enum Refusal: Equatable {

        case notAWebPage


        case unusable



        var summary: String {
            switch self {
            case .notAWebPage:
                return "That link opens another app. It is blocked, so your sign-in keeps its place here."
            case .unusable:
                return "That link could not be read, so it is blocked."
            }
        }
    }

    enum Decision: Equatable {

        case load

        case refuse(Refusal)
    }

    static func decision(for url: URL?) -> Decision {
        guard let url, let scheme = url.scheme?.lowercased() else { return .refuse(.unusable) }



        guard scheme == "https" || scheme == "http" else { return .refuse(.notAWebPage) }
        guard let host = url.host?.lowercased(), !host.isEmpty else { return .refuse(.unusable) }
        return .load
    }













    static let signInDomains = [
        "google.com",
        "googleapis.com",
        "gstatic.com",
        "youtube.com",
        "ytimg.com",
    ]


    static func isSignInHost(_ url: URL?) -> Bool {
        guard let scheme = url?.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = url?.host?.lowercased(), !host.isEmpty
        else { return false }
        return signInDomains.contains { host == $0 || host.hasSuffix("." + $0) }
    }







    static func offSignInHostAdvice(host: String?) -> String {
        guard let host, !host.isEmpty else { return "This page is not part of Google's sign-in." }
        return "**\(host)** is not part of Google's sign-in. Go back to keep signing in."
    }
}
