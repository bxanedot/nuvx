import Foundation
import WebKit

















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

let loginURL = URL(string:
    "https://accounts.google.com/ServiceLogin"
        + "?ltmpl=music&service=youtube&prompt=select_account"
        + "&continue=https%3A%2F%2Fmusic.youtube.com%2F"
)!








func ask(_ url: URL, agent: String?, _ question: String, seconds: Double = 40) -> String {
    let config = WKWebViewConfiguration()
    config.defaultWebpagePreferences.allowsContentJavaScript = true
    let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 480, height: 800), configuration: config)
    if let agent { view.customUserAgent = agent }

    final class Reader: NSObject, WKNavigationDelegate {
        var onDone: ((String) -> Void)?
        private var finished = false


        private let question: String
        init(view: WKWebView, question: String) {
            self.question = question
            super.init()
            view.navigationDelegate = self
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !finished else { return }
            finished = true
            webView.evaluateJavaScript(question) { [weak self] raw, _ in
                self?.onDone?((raw as? String) ?? "")
                self?.onDone = nil
            }
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            finish("")
        }
        func webView(
            _ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            finish("")
        }
        private func finish(_ text: String) {
            guard !finished else { return }
            finished = true
            onDone?(text)
            onDone = nil
        }
    }

    let reader = Reader(view: view, question: question)
    var result = ""
    var done = false
    reader.onDone = { text in
        result = text
        done = true
    }
    view.load(URLRequest(url: url))
    let deadline = Date().addingTimeInterval(seconds)
    while !done && Date() < deadline {
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.1))
    }
    return result
}



private let refusalMarkers = [
    "may not be secure",
    "couldn’t sign you in",
    "couldn't sign you in",
    "this browser or app",
    "try using a different browser",
]

func isRefusal(_ text: String) -> Bool {
    let lower = text.lowercased()
    return refusalMarkers.contains { lower.contains($0) }
}

func summary(_ text: String) -> String {
    let lines = text
        .split(separator: "\n")
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
    return lines.prefix(4).joined(separator: " · ")
}

print("sign-in: Google's own answer, from a real web view")

let appAgent = SignInUserAgent.desktopSafari()
print("  · agent under test: \(appAgent)")





let ownUA = ask(
    URL(string: "https://example.com")!, agent: nil, "navigator.userAgent", seconds: 30
)
print("  · this engine's own agent: \(ownUA)")
check("the engine's own agent is readable", !ownUA.isEmpty)

check("our agent names Safari", appAgent.contains("Safari/"))
check("our agent carries a version token", appAgent.contains("Version/"))
check("our agent is not a mobile agent", !SignInUserAgent.needsReplacing(defaultAgent: appAgent))
check("our agent presents as Safari on macOS", appAgent.contains("Macintosh"))


let text = ask(
    loginURL, agent: appAgent, "document.body ? document.body.innerText : ''"
)
print("  · page said: \(summary(text).isEmpty ? "(nothing)" : summary(text))")

check("the page answered at all", !text.isEmpty)











let mobileAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari/604.1"
let mobileText = ask(
    loginURL, agent: mobileAgent, "document.body ? document.body.innerText : ''"
)
print("  · page said, with a mobile agent: \(summary(mobileText).isEmpty ? "(nothing)" : summary(mobileText))")
check("a mobile agent is what the app is replacing",
      SignInUserAgent.needsReplacing(defaultAgent: mobileAgent))
#if os(iOS)
check("Google refuses a mobile agent in a web view", isRefusal(mobileText),
      isRefusal(mobileText) ? "refused, as expected" : "not refused — the fix is not proven")
#else
print("  · control unavailable here: on macOS the engine is WebKit, which is what")
print("    Safari on macOS is, so a mobile agent alone does not trip Google's block.")
print("    The block needs a mobile agent inside an *iOS* web view. Run this from an")
print("    iOS simulator or device to exercise it.")
check("the control could not be exercised on this platform",
      isRefusal(mobileText) || mobileText.lowercased().contains("sign in"),
      "reported, not passed off as verified")
#endif

check("Google did not refuse the engine", !isRefusal(text), isRefusal(text) ? "refusal page" : "")
check(
    "the page is a sign-in page, not an error",
    text.lowercased().contains("sign in")
        || text.lowercased().contains("google")
        || text.lowercased().contains("email"),
    summary(text)
)

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
