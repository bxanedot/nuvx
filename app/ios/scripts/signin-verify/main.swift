import Foundation



















var failures = 0
var checks = 0

func check(_ label: String, _ ok: Bool, _ detail: String = "") {
    checks += 1
    if ok {
        print("  ok   \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    } else {
        failures += 1
        print("  FAIL \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    }
}

func expect(_ label: String, _ url: String?, _ want: SignInNavigation.Decision) {
    let got = SignInNavigation.decision(for: url.flatMap(URL.init(string:)))
    check("\(label) → \(name(of: want))", got == want, got == want ? "" : "got \(name(of: got))")
}

func name(of decision: SignInNavigation.Decision) -> String {
    switch decision {
    case .load: return "load"
    case .refuse(let why): return "refuse(\(why))"
    }
}

print("sign-in: what the web view will navigate to")








expect("the login page", "https://accounts.google.com/ServiceLogin?service=youtube", .load)
expect("the password step", "https://accounts.google.com/signin/challenge/pwd", .load)
expect("two-factor", "https://accounts.google.com/signin/challenge/2fa", .load)
expect("a passkey prompt", "https://accounts.google.com/signin/challenge", .load)
expect("the consent screen", "https://accounts.google.com/o/oauth2/auth", .load)
expect("the Music origin", "https://music.youtube.com/", .load)
expect("a static asset host", "https://ssl.gstatic.com/accounts/x", .load)
expect("a YouTube asset host", "https://s.ytimg.com/vi/abc/x.jpg", .load)
expect("plain http", "http://accounts.google.com/ServiceLogin", .load)







expect("a regional Google domain", "https://accounts.google.co.uk/ServiceLogin", .load)
expect("a blog post", "https://blog.youtube/", .load)
expect("an unrelated site", "https://example.com/", .load)
expect("the App Store over https", "https://apps.apple.com/app/id123", .load)






expect("the YouTube Music app link", "youtube://music", .refuse(.notAWebPage))
expect("a youtube-music app link", "youtubemusic://", .refuse(.notAWebPage))
expect("a spotify-style app link", "spotify://", .refuse(.notAWebPage))


expect("the App Store", "itms-apps://apps.apple.com/app/id123", .refuse(.notAWebPage))


expect("mailto", "mailto:someone@example.com", .refuse(.notAWebPage))
expect("tel", "tel:+15550100", .refuse(.notAWebPage))
expect("sms", "sms:body", .refuse(.notAWebPage))
expect("facetime", "facetime:someone@example.com", .refuse(.notAWebPage))
expect("maps", "maps://?q=coffee", .refuse(.notAWebPage))
expect("javascript:", "javascript:void(0)", .refuse(.notAWebPage))
expect("data:", "data:text/html,<b>hi</b>", .refuse(.notAWebPage))
expect("file:", "file:///etc/passwd", .refuse(.notAWebPage))
expect("about:", "about:blank", .refuse(.notAWebPage))
expect("blob:", "blob:https://music.youtube.com/abc", .refuse(.notAWebPage))



expect("a scheme with no host", "youtube://", .refuse(.notAWebPage))





check("nil is refused", SignInNavigation.decision(for: nil) == .refuse(.unusable))
expect("an empty string", "", .refuse(.unusable))
expect("no scheme", "accounts.google.com/ServiceLogin", .refuse(.unusable))
expect("a scheme with no host over https", "https://", .refuse(.unusable))



check("a non-web page explains itself", !SignInNavigation.Refusal.notAWebPage.summary.isEmpty)
check("an unusable URL explains itself", !SignInNavigation.Refusal.unusable.summary.isEmpty)
check(
    "the two refusals read differently",
    SignInNavigation.Refusal.notAWebPage.summary != SignInNavigation.Refusal.unusable.summary
)








check("a sign-in host is recognised", SignInNavigation.isSignInHost(URL(string: "https://music.youtube.com/")))
check("so is accounts.google.com", SignInNavigation.isSignInHost(URL(string: "https://accounts.google.com/ServiceLogin")))






check("a regional Google domain is not a sign-in host", !SignInNavigation.isSignInHost(URL(string: "https://accounts.google.co.uk/x")))
check("a lookalike is not", !SignInNavigation.isSignInHost(URL(string: "https://accounts.google.com.evil.test/")))
check("google in the path is not", !SignInNavigation.isSignInHost(URL(string: "https://evil.test/accounts.google.com")))
check("a host merely ending in the letters is not", !SignInNavigation.isSignInHost(URL(string: "https://notgoogle.com/")))
check("a subdomain of a lookalike is not", !SignInNavigation.isSignInHost(URL(string: "https://x.notgoogle.com/")))
check("a non-web scheme is not a sign-in host", !SignInNavigation.isSignInHost(URL(string: "youtube://music.youtube.com")))



check("the host list is not empty", !SignInNavigation.signInDomains.isEmpty)
check(
    "no entry carries a leading dot",
    SignInNavigation.signInDomains.allSatisfy { !$0.hasPrefix(".") },
    SignInNavigation.signInDomains.joined(separator: ", ")
)



let advice = SignInNavigation.offSignInHostAdvice(host: "blog.youtube")
check("the advice names the host", advice.contains("blog.youtube"), advice)
check("the advice is not empty for no host", !SignInNavigation.offSignInHostAdvice(host: nil).isEmpty)
check("the advice is not empty for an empty host", !SignInNavigation.offSignInHostAdvice(host: "").isEmpty)



let musicURL = URL(string: "https://music.youtube.com/")!
check("only the HTTPS Music origin can supply a profile", SignInNavigation.isMusicOrigin(musicURL))
for invalid in [
    "https://music.youtube.com.evil.test/", "https://music.youtube.com@evil.test/",
    "http://music.youtube.com/", "https://music.youtube.com:444/", "https://youtube.com/",
    "https://user:pass@music.youtube.com/"
] {
    check("a different origin cannot supply a profile: \(invalid)", !SignInNavigation.isMusicOrigin(URL(string: invalid)))
}
check("the explicit default port is the Music origin", SignInNavigation.isMusicOrigin(URL(string: "https://music.youtube.com:443/")))

let flow = LoginFlow()
var captureRequests = 0
flow.session.take = { captureRequests += 1 }
flow.navigating(to: URL(string: "https://accounts.google.com/ServiceLogin"))
check("Google login stays in the browser", !flow.showsCompletion && !flow.pageReady)
flow.navigating(to: musicURL)
check("Music's arrival presents preparation, not success", flow.showsCompletion && flow.checkingPage && !flow.pageReady)
flow.inspectedPage(loggedIn: false)
check("an unfinished login or channel chooser remains usable", !flow.showsCompletion && flow.pageReady)
flow.navigating(to: musicURL)
flow.inspectedPage(loggedIn: true)
check("a signed-in page presents the explicit completion step", flow.showsCompletion && flow.pageReady)
check("inspecting a signed-in page never requests capture", captureRequests == 0)
flow.chooseProfile()
check("choosing a channel reveals the existing page", !flow.showsCompletion && flow.pageReady)
flow.navigating(to: URL(string: "https://accounts.google.com/AccountChooser"))
check("leaving Music removes confirmation immediately", !flow.pageReady && !flow.showsCompletion)
flow.navigating(to: URL(string: "https://music.youtube.com/?authuser=1"))
flow.inspectedPage(loggedIn: true)
check("a channel switch stays in the browser for review", !flow.showsCompletion && flow.pageReady)
check("switching profiles never requests capture", captureRequests == 0)
flow.taking = true
check("verification hides the page and disables another capture", flow.showsCompletion && !flow.pageReady)
flow.captureFailed("Verification refused")
check("a rejection keeps the selected page available to retry", flow.pageReady && flow.captureFailedMessage == "Verification refused")
flow.taking = true
flow.captureSucceeded()
check("only completed validation clears Checking", !flow.taking && flow.captureFailedMessage == nil)

let retryFlow = LoginFlow()
retryFlow.navigating(to: musicURL)
retryFlow.inspectedPage(loggedIn: true)
retryFlow.taking = true
retryFlow.captureFailed("Save failed")
check("a failed save stays on completion with a retry", retryFlow.showsCompletion && retryFlow.pageReady)
retryFlow.navigating(to: URL(string: "https://music.youtube.com.evil.test/"))
retryFlow.inspectedPage(loggedIn: true)
check("a lookalike cannot show a successful profile", !retryFlow.pageReady && !retryFlow.showsCompletion)



let menu = """
{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Fixture "},{"text":"Channel"}]},"email":{"simpleText":"fixture@example.test"},"accountPhoto":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/small","width":32},{"url":"https://yt3.googleusercontent.com/large","width":128}]}}}}}}}]}
"""
let preview = SignInProfilePreview.parse(Data(menu.utf8))
check("the preview reads the current account-menu name", preview?.name == "Fixture Channel")
check("the preview reads the account subtitle", preview?.subtitle == "fixture@example.test")
check("the preview uses the larger avatar", preview?.avatarURL?.path == "/large")
check("missing photos have a useful initial fallback", preview?.initials == "FC")
check("a missing account header cannot invent a profile", SignInProfilePreview.parse(Data("{}".utf8)) == nil)
check("invalid JSON cannot invent a profile", SignInProfilePreview.parse(Data("invalid".utf8)) == nil)
check("a channel handle is a valid secondary line", SignInProfilePreview.parse(Data(menu.replacingOccurrences(of: "\"email\"", with: "\"channelHandle\"").utf8))?.subtitle == "fixture@example.test")
check("relative Google photo URLs become HTTPS", SignInProfilePreview.safeAvatarURL("//lh3.googleusercontent.com/avatar")?.scheme == "https")
for url in ["http://yt3.googleusercontent.com/x", "file:///tmp/avatar", "https://googleusercontent.com.evil.test/x", "https://user:password@yt3.googleusercontent.com/x"] {
    check("an unsafe avatar is refused: \(url)", SignInProfilePreview.safeAvatarURL(url) == nil)
}
let originalScope = SignInProfileScope(SignInCaptureFields(loggedIn: true, pageId: "brand", dataSyncId: "account||brand", authUser: "1", visitorData: nil, clientVersion: nil))
let changedScope = SignInProfileScope(SignInCaptureFields(loggedIn: true, pageId: "other-brand", dataSyncId: "account||other-brand", authUser: "1", visitorData: nil, clientVersion: nil))
check("two channels under one account are different review identities", originalScope != changedScope)





let agent = SignInUserAgent.desktopSafari(
    osVersion: OperatingSystemVersion(majorVersion: 17, minorVersion: 5, patchVersion: 0)
)
print("  · agent: \(agent)")



check("names Mozilla", agent.hasPrefix("Mozilla/5.0 "))
check("presents as a desktop", agent.contains("(Macintosh;") && !agent.contains("Mobile/"))
check("carries the WebKit build", agent.contains("AppleWebKit/605.1.15"))
check("names a Safari version", agent.contains("Version/") && agent.contains("Safari/605.1.15"))
check("does not say it is a phone", !agent.contains("iPhone"))
check("does not say it is Android", !agent.contains("Android"))
check("identifies the app", agent.contains("nuvx"))
check("one line, no newline", !agent.contains("\n") && !agent.contains("\r"))



check("has a token after Safari/", agent.split(separator: " ").count >= 8,
      "\(agent.split(separator: " ").count) tokens")
check("the Safari version is a plausible number",
      SignInUserAgent.safariVersion.split(separator: ".").count == 2
          && SignInUserAgent.safariVersion.allSatisfy { $0.isNumber || $0 == "." })


let iOSOwn = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari/604.1"
let macOwn = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)"
check("an iOS engine's own agent is replaced", SignInUserAgent.needsReplacing(defaultAgent: iOSOwn))



check("a macOS engine's own agent is replaced as well",
      SignInUserAgent.needsReplacing(defaultAgent: macOwn),
      "desktop, but naming no browser family")
check("our own agent is not replaced again", !SignInUserAgent.needsReplacing(defaultAgent: agent))
check("an empty agent is replaced", SignInUserAgent.needsReplacing(defaultAgent: ""))



for (label, agent) in [
    ("not Mozilla's", "curl/8.4.0"),
    ("no browser named", "Mozilla/5.0 (Macintosh) AppleWebKit/605.1.15"),
    ("a bot", "Googlebot/2.1"),
] {
    check("an agent that is \(label) is replaced", SignInUserAgent.needsReplacing(defaultAgent: agent))
}








let liveDict: [String: Any] = [
    "loggedIn": "true",
    "pageId": "page-1",
    "dataSyncId": "account||delegated",
    "authUser": "1",
    "visitorData": "CgABC",
    "clientVersion": "1.20250101.01.00",
]
if let fields = SignInCapture.parse(jsResult: liveDict) {
    check("a decoded object parses", fields.loggedIn)
    check("the page id survives", fields.pageId == "page-1")
    check("the raw datasync id survives", fields.dataSyncId == "account||delegated")
    check("the auth user survives", fields.authUser == "1")
    check("visitor data survives", fields.visitorData == "CgABC")
    check("the client version survives", fields.clientVersion == "1.20250101.01.00")
} else {
    check("a decoded object parses", false)
}



let androidJSON = """
{"loggedIn":"true","pageId":"page-1","dataSyncId":"ds-1","authUser":"0","visitorData":"CgABC","clientVersion":"1.0"}
"""
if let fields = SignInCapture.parse(jsResult: androidJSON) {
    check("a JSON string parses too", fields.loggedIn && fields.pageId == "page-1")
} else {
    check("a JSON string parses too", false)
}




check("nil is no session", SignInCapture.parse(jsResult: nil) == nil)
check("NSNull is no session", SignInCapture.parse(jsResult: NSNull()) == nil)
let signedOut: [String: Any] = ["loggedIn": "false"]
check("a signed-out page reports signed out",
      SignInCapture.parse(jsResult: signedOut)?.loggedIn == false)
let nulls: [String: Any] = ["loggedIn": "true", "pageId": NSNull(), "dataSyncId": NSNull()]
if let fields = SignInCapture.parse(jsResult: nulls) {
    check("nulls are absent, not identities", fields.loggedIn && fields.pageId == nil && fields.dataSyncId == nil)
} else {
    check("nulls are absent, not identities", false)
}
check("a number is not a session", SignInCapture.parse(jsResult: 42) == nil)





check("a plain id is returned as is", SignInCapture.normalizeDataSyncId("ds-123") == "ds-123")
check("a delegated id resolves to the active half",
      SignInCapture.normalizeDataSyncId("account||delegated") == "delegated")
check("an empty active half falls back to the account half",
      SignInCapture.normalizeDataSyncId("account||") == "account")
check("blank is absent", SignInCapture.normalizeDataSyncId(nil) == nil
    && SignInCapture.normalizeDataSyncId("") == nil
    && SignInCapture.normalizeDataSyncId("   ") == nil
    && SignInCapture.normalizeDataSyncId("||") == nil)











if let root = CommandLine.arguments.dropFirst().first {
    let source = URL(fileURLWithPath: root)
        .appendingPathComponent("../../core/apple/src/UI/YtMusicLoginView.swift")
    guard let text = try? String(contentsOf: source, encoding: .utf8) else {
        check("the sign-in view's source can be read", false, source.path)
        exit(failures == 0 ? 0 : 1)
    }



    for (what, call) in [
        ("UIApplication.open", "UIApplication.shared.open("),
        ("the deprecated openURL", ".openURL("),
        ("NSWorkspace.open", "NSWorkspace.shared.open("),
        ("a Safari view controller", "SFSafariViewController("),
        ("ASWebAuthenticationSession", "ASWebAuthenticationSession("),
    ] {
        check("the sign-in screen never calls \(what)", !text.contains(call))
    }
    check("no Safari session import remains", !text.contains("SessionImportView") && !text.contains("importPresented"))
    check("no Safari handoff link remains", !text.contains("Link("))

    check("the old open-outside decision is gone", !text.contains("openOutside"))
    check("the policy has no open-outside decision either",
          !String(describing: SignInNavigation.Decision.self).contains("openOutside"))


    check("the confirmation matches upstream", text.contains("Use This Profile"))
    check("no bottom Continue button", !text.contains("Text(\"Continue\")"))
} else {
    print("  · no repository root given, skipping the source guard")
}

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
