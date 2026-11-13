import Foundation







































enum SignInUserAgent {






    static func desktopSafari(
        osVersion: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion
    ) -> String {
        let major = osVersion.majorVersion
        let minor = osVersion.minorVersion
        let patch = osVersion.patchVersion
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/\(safariVersion) Safari/605.1.15"
            + " (nuvx; macOS \(major).\(minor).\(patch))"
    }


    static let safariVersion = "17.6"



















    static func needsReplacing(defaultAgent: String) -> Bool {
        !isAcceptable(defaultAgent)
    }


    static func isAcceptable(_ agent: String) -> Bool {
        guard !agent.isEmpty else { return false }
        guard !agent.contains("Mobile/") else { return false }
        guard agent.hasPrefix("Mozilla/") else { return false }



        guard agent.contains("Safari/") else { return false }
        guard agent.contains("Version/") else { return false }
        return true
    }
}
