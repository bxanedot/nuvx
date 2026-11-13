import Foundation
import JavaScriptCore
import NuvxShared





























final class ModuleJsEngine: NSObject, ModuleEngineImpl {
    static let shared = ModuleJsEngine()






    private static let maxModules = 12






    private static let enginesPerModule = 3


    private static let searchLimit = 50

    private static let requestTimeout: TimeInterval = 20



    private final class Pool {
        let scriptUrl: String
        let fetchBase: String
        let script: String
        let lock = NSCondition()

        var made: [Engine] = []

        var free: [Engine] = []

        var started = 0

        var lastUsed: UInt64 = 0

        init(scriptUrl: String, fetchBase: String, script: String) {
            self.scriptUrl = scriptUrl
            self.fetchBase = fetchBase
            self.script = script
        }
    }


    private final class Engine {
        let queue: DispatchQueue
        let context: JSContext

        init(moduleId: String, script: String, fetchBase: String) {
            queue = DispatchQueue(
                label: "com.bxane.nuvx.module.\(moduleId).\(UUID().uuidString)"
            )
            guard let context = JSContext() else {


                fatalError("JavaScriptCore refused to create a context")
            }
            self.context = context
            context.exceptionHandler = { context, exception in

                DebugLog.shared.d(
                    message: "module JS threw: \(exception?.toString() ?? "unknown") "
                        + "at \(context?.description ?? "unknown")"
                )
            }
            ModuleJsEngine.prepare(context, moduleId: moduleId, script: script, fetchBase: fetchBase)
        }
    }

    private let poolsLock = NSLock()
    private var pools: [String: Pool] = [:]
    private var clock: UInt64 = 0



    func search(
        moduleId: String,
        scriptUrl: String,
        query: String,
        tier: String?,
        callback: ModuleEngineRowsCallback
    ) {
        answer(
            moduleId: moduleId, scriptUrl: scriptUrl, export: "searchTracks",
            args: [Self.quote(query), String(Self.searchLimit), Self.contextArg(tier: tier)]
        ) { json, error in
            callback.onResult(json: json, error: error)
        }
    }

    func stream(
        moduleId: String,
        scriptUrl: String,
        trackId: String,
        tier: String?,
        callback: ModuleEngineStreamCallback
    ) {







        answer(
            moduleId: moduleId, scriptUrl: scriptUrl, export: "getTrackStreamUrl",
            args: [Self.quote(trackId), Self.quote(Self.moduleQuality(tier)), Self.contextArg(tier: tier)]
        ) { json, error in
            callback.onResult(json: json, error: error)
        }
    }



    private func answer(
        moduleId: String,
        scriptUrl: String,
        export: String,
        args: [String],
        then: @escaping @Sendable (String?, String?) -> Void
    ) {
        let trimmed = scriptUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            then(nil, "module \(moduleId) has no script URL")
            return
        }


        if let pool = existingPool(moduleId) {
            serve(moduleId: moduleId, pool: pool, export: export, args: args, then: then)
            return
        }


        Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            guard let script = try? await Self.downloadScript(trimmed) else {
                then(nil, "could not download the script for \(moduleId)")
                return
            }
            guard let pool = self.loadPool(moduleId: moduleId, scriptUrl: trimmed, script: script)
            else { return }
            self.serve(moduleId: moduleId, pool: pool, export: export, args: args, then: then)
        }
    }

    private func serve(
        moduleId: String,
        pool: Pool,
        export: String,
        args: [String],
        then: @escaping @Sendable (String?, String?) -> Void
    ) {
        guard let engine = acquire(moduleId: moduleId, pool: pool) else {
            then(nil, "no engine available for \(moduleId)")
            return
        }



        var result: (String?, String?) = (nil, "no answer")
        engine.queue.sync { result = ModuleJsEngine.callOn(engine.context, export: export, args: args) }
        release(engine, pool: pool)



        if let json = result.0, let refusal = Self.envelopeError(json) {
            then(nil, refusal)
            return
        }
        then(result.0, result.1)
    }









    private static func callOn(
        _ context: JSContext, export: String, args: [String]
    ) -> (String?, String?) {
        let kind = context.evaluateScript("typeof __nuvx_mod['\(export)']")
        guard kind?.toString() == "function" else {
            return (nil, "\(export) is not a function on this module")
        }
        context.evaluateScript("""
        var __nuvx_resolved_json = undefined;
        (async function() {
            var __fn = __nuvx_mod['\(export)'];
            if (!__fn) { __nuvx_resolved_json = JSON.stringify({error: 'not found'}); return; }
            try {
                var r = await __fn(\(args.joined(separator: ",")));
                __nuvx_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
            } catch(e) {
                __nuvx_resolved_json = JSON.stringify({ error: (e && e.message) ? e.message : String(e) });
            }
        })();
        """)
        return (readAnswer(context), nil)
    }







    private static func readAnswer(_ context: JSContext) -> String? {
        let deadline = Date().addingTimeInterval(5)
        while true {
            let value = context.evaluateScript(
                "typeof __nuvx_resolved_json !== 'undefined' ? __nuvx_resolved_json : undefined"
            )
            if let text = value?.toString(), text != "undefined" { return text }
            if Date() >= deadline { return nil }


            _ = context.evaluateScript("void 0")
            Thread.sleep(forTimeInterval: 0.005)
        }
    }

    private static func envelopeError(_ json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let error = object["error"] as? String, !error.isEmpty,


              object.count <= 2
        else { return nil }
        return error
    }



    private func existingPool(_ moduleId: String) -> Pool? {
        poolsLock.lock()
        clock &+= 1
        let pool = pools[moduleId]
        if let pool { pool.lastUsed = clock }
        poolsLock.unlock()
        return pool
    }

    private func loadPool(moduleId: String, scriptUrl: String, script: String) -> Pool? {
        poolsLock.lock()
        clock &+= 1
        if let existing = pools[moduleId] {
            existing.lastUsed = clock
            poolsLock.unlock()
            return existing
        }
        while pools.count >= Self.maxModules,
              let coldest = pools.min(by: { $0.value.lastUsed < $1.value.lastUsed })?.key,
              let victim = pools.removeValue(forKey: coldest) {
            poolsLock.unlock()

            Self.discard(victim)
            poolsLock.lock()
        }
        let pool = Pool(scriptUrl: scriptUrl, fetchBase: scriptUrl, script: script)
        pool.lastUsed = clock
        pools[moduleId] = pool
        poolsLock.unlock()
        return pool
    }

    private static func discard(_ pool: Pool) {
        pool.lock.lock()
        let engines = pool.made
        pool.made.removeAll()
        pool.free.removeAll()
        pool.started = 0
        pool.lock.unlock()
        for engine in engines {


            engine.queue.sync { engine.context.exceptionHandler = nil }
        }
    }

    private func acquire(moduleId: String, pool: Pool) -> Engine? {
        pool.lock.lock()
        if let engine = pool.free.popLast() {
            pool.lock.unlock()
            return engine
        }
        let claimed = pool.started < Self.enginesPerModule
        if claimed { pool.started += 1 }
        pool.lock.unlock()

        if claimed {
            let engine = Engine(
                moduleId: moduleId, script: pool.script, fetchBase: pool.fetchBase
            )
            pool.lock.lock()
            pool.made.append(engine)
            pool.lock.unlock()
            return engine
        }


        pool.lock.lock()
        while pool.free.isEmpty { pool.lock.wait() }
        let engine = pool.free.popLast()
        pool.lock.unlock()
        return engine
    }

    private func release(_ engine: Engine, pool: Pool) {
        pool.lock.lock()
        pool.free.append(engine)
        pool.lock.signal()
        pool.lock.unlock()
    }



    private struct ModuleError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static func downloadScript(_ url: String) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            SourceBridge.shared.downloadScript(url: url, callback: ScriptResult { body, message in
                if let body {
                    continuation.resume(returning: body)
                } else {
                    continuation.resume(
                        throwing: ModuleError(message: message ?? "script download failed")
                    )
                }
            })
        }
    }


    private static func prepare(
        _ context: JSContext, moduleId: String, script: String, fetchBase: String
    ) {
        bindConsole(context)
        bindHost(context, fetchBase: fetchBase)
        context.evaluateScript(polyfills)

        context.evaluateScript("""
        var __nuvx_init_error = null;
        var __nuvx_mod = (function() {
            try {
                var module = { exports: {} };
                var exports = module.exports;
                var self = {};
                \(preprocess(script))
                if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                    return module.exports;
                }
                return {};
            } catch(e) {
                __nuvx_init_error = (e && e.message) ? e.message : String(e);
                return {};
            }
        })();
        """)

        if let failure = context.evaluateScript("__nuvx_init_error")?.toString(),
           !failure.isEmpty, failure != "null", failure != "undefined" {
            DebugLog.shared.d(message: "module \(moduleId) init error: \(failure)")
        }
    }







    private static func bindConsole(_ context: JSContext) {
        for name in ["log", "info", "warn", "error", "debug"] {
            let sink: @convention(block) (String) -> Void = { message in
                DebugLog.shared.d(message: "[\(name)] \(message)")
            }
            context.setObject(sink, forKeyedSubscript: "__nuvx_console_\(name)" as NSString)
        }
        context.evaluateScript("""
        var console = {
            log: __nuvx_console_log, info: __nuvx_console_info, debug: __nuvx_console_debug,
            warn: __nuvx_console_warn, error: __nuvx_console_error
        };
        """)
    }








    private static func bindHost(_ context: JSContext, fetchBase: String) {
        let fetch: @convention(block) (String, String, String, String?) -> String = {
            rawUrl, method, headersJson, body in
            let (status, text) = perform(
                url: resolve(rawUrl, against: fetchBase),
                method: method, headersJson: headersJson, body: body
            )
            return "{\"status\":\(status),\"ok\":\(status >= 200 && status < 299),"
                + "\"body\":\(quote(text))}"
        }
        context.setObject(fetch, forKeyedSubscript: "__nuvx_fetch" as NSString)




        let timer: @convention(block) (String?, Int) -> Int = { [weak context] callbackBody, milliseconds in
            let delay = max(0, milliseconds) / 1000
            if delay > 0 { Thread.sleep(forTimeInterval: min(TimeInterval(delay), 10)) }
            guard let callbackBody, !callbackBody.isEmpty else { return 0 }
            context?.evaluateScript("(\(callbackBody))()")
            return 0
        }
        context.setObject(timer, forKeyedSubscript: "__nuvx_setTimeout" as NSString)

        let encode: @convention(block) (String) -> String = { Data($0.utf8).base64EncodedString() }
        context.setObject(encode, forKeyedSubscript: "__nuvx_btoa" as NSString)

        let decode: @convention(block) (String) -> String = { value in
            guard let data = Data(base64Encoded: value, options: [.ignoreUnknownCharacters]),
                  let text = String(data: data, encoding: .utf8)
            else { return "" }
            return text
        }
        context.setObject(decode, forKeyedSubscript: "__nuvx_atob" as NSString)

        context.evaluateScript("""
        var btoa = __nuvx_btoa;
        var atob = __nuvx_atob;
        var clearTimeout = function() {};

        // A web-compatible surface over the native binding. `ok` and `status`
        // come from the real response: reporting `ok: true` unconditionally —
        // which the previous host did — makes a module parse a 403 error page as
        // a search result instead of refusing it.
        var fetch = async function(url, options) {
            var method = 'GET', headers = '{}', body = null;
            if (options) {
                method = options.method || 'GET';
                if (options.headers) {
                    headers = typeof options.headers === 'string'
                        ? options.headers
                        : (function() { try { return JSON.stringify(options.headers); } catch(e) { return '{}'; } })();
                }
                if (options.body !== undefined && options.body !== null) {
                    body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                }
                if (options.signal && options.signal.aborted) throw new Error('Aborted');
            }
            var raw = JSON.parse(await __nuvx_fetch(String(url), method, headers, body));
            var respBody = raw.body;
            var response = {
                ok: raw.ok,
                status: raw.status,
                statusText: raw.ok ? 'OK' : 'Error',
                url: String(url),
                json: function() {
                    try { return JSON.parse(respBody); }
                    catch(e) { throw new Error('Invalid JSON: ' + String(respBody).substring(0, 200)); }
                },
                text: function() { return respBody; },
                blob: function() { return respBody; },
                arrayBuffer: function() { throw new Error('Not implemented'); },
                clone: function() { return response; },
                headers: { get: function() { return null; } }
            };
            // `json()` and `text()` answer values, not promises: a module written
            // against this protocol awaits the response and then reads the body,
            // and a promise here reads as `[object Promise]` — a silent empty
            // result rather than a failure anyone would notice.
            return response;
        };

        var setTimeout = function(fn, ms) {
            return __nuvx_setTimeout(typeof fn === 'function' ? fn.toString() : null, ms || 0);
        };
        """)
    }

    private static func perform(
        url: String, method: String, headersJson: String, body: String?
    ) -> (Int, String) {
        guard let endpoint = URL(string: url) else { return (0, "") }
        let verb = method.uppercased()
        var request = URLRequest(url: endpoint)
        request.httpMethod = verb.isEmpty ? "GET" : verb
        request.timeoutInterval = requestTimeout

        var hasUserAgent = false
        if let data = headersJson.data(using: .utf8),
           let headers = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (key, value) in headers {
                guard let string = value as? String else { continue }
                request.setValue(string, forHTTPHeaderField: key)
                if key.lowercased() == "user-agent" { hasUserAgent = true }
            }
        }


        if !hasUserAgent {
            request.setValue(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
                forHTTPHeaderField: "User-Agent"
            )
        }
        if let body, !body.isEmpty {
            request.httpBody = Data(body.utf8)
            if request.value(forHTTPHeaderField: "Content-Type") == nil {
                request.setValue(
                    "application/json; charset=utf-8", forHTTPHeaderField: "Content-Type"
                )
            }
        }


        var status = 0
        var text = ""
        let done = DispatchSemaphore(value: 0)
        session.dataTask(with: request) { data, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            done.signal()
        }.resume()


        _ = done.wait(timeout: .now() + requestTimeout + 5)
        return (status, text)
    }



    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .onlyFromMainDocumentDomain
        configuration.httpCookieStorage = HTTPCookieStorage()
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = requestTimeout
        configuration.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: configuration)
    }()










    private static func preprocess(_ code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)

        if let open = firstMatch(of: #"^export\s+const\s+\w+\s*=\s*`"#, in: trimmed),
           let close = closingBacktick(in: trimmed, from: open.upperBound) {
            return String(trimmed[close..<trimmed.endIndex])
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        var result = trimmed
        result = replacing(
            #"\bexport\s+default\s+(?=function|class|const|let|var|async)"#, in: result
        ) { $0.replacingOccurrences(of: "export default", with: "") }
        result = replacing(
            #"\bexport\s+(const|let|var|function|class|async)\b"#, in: result
        ) { $0.replacingOccurrences(of: "export", with: "") }
        return replacing(#"\bexport\s*\{[^}]*\}\s*;?"#, in: result) { _ in "" }
    }

    private static func firstMatch(
        of pattern: String, in text: String
    ) -> Range<String.Index>? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let match = regex.firstMatch(
            in: text, range: NSRange(text.startIndex..., in: text)
        )
        guard let match else { return nil }
        return Range(match.range, in: text)
    }

    private static func replacing(
        _ pattern: String, in text: String, with transform: (String) -> String
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        var result = ""
        var cursor = text.startIndex
        regex.enumerateMatches(in: text, range: NSRange(text.startIndex..., in: text)) {
            match, _, _ in
            guard let match, let matched = Range(match.range, in: text) else { return }
            result += text[cursor..<matched.lowerBound]
            result += transform(String(text[matched]))
            cursor = matched.upperBound
        }
        return result + text[cursor...]
    }

    private static func closingBacktick(
        in text: String, from start: String.Index
    ) -> String.Index? {
        var index = start
        while index < text.endIndex {
            if text[index] == "\\" {
                index = text.index(index, offsetBy: 2, limitedBy: text.endIndex)
                    ?? text.endIndex
                continue
            }
            if text[index] == "`" { return index }
            index = text.index(after: index)
        }
        return nil
    }




    private static func quote(_ value: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: [value]))
            ?? Data(#"[""]"#.utf8)
        let array = String(data: data, encoding: .utf8) ?? #"[""]"#
        return String(array.dropFirst().dropLast())
    }





    private static func contextArg(tier: String?) -> String {
        let name = moduleQuality(tier)
        guard !name.isEmpty else { return "{settings:{}}" }
        return "{settings:{\"quality\":{\"value\":\(quote(name))}}}"
    }

    private static func moduleQuality(_ tier: String?) -> String {
        switch tier {
        case "LOSSLESS": return "LOSSLESS"
        case "HIGH", "BEST": return "HIGH"
        case "MEDIUM": return "LOW"
        default: return ""
        }
    }



    private static func resolve(_ url: String, against base: String) -> String {
        if url.hasPrefix("http://") || url.hasPrefix("https://") { return url }
        guard !base.isEmpty,
              let endpoint = URL(string: base),
              let scheme = endpoint.scheme,
              let host = endpoint.host
        else { return url }
        if url.hasPrefix("/") { return "\(scheme)://\(host)\(url)" }

        var directory = endpoint.path
        let looksLikeFile = directory.hasSuffix(".json") || directory.hasSuffix(".js")
        if looksLikeFile {
            directory = (directory as NSString).deletingLastPathComponent
        }
        if !directory.hasSuffix("/") { directory += "/" }
        return "\(scheme)://\(host)\(directory)\(url)"
    }







    private static let polyfills = """
    if (typeof Object.assign !== 'function') {
        Object.assign = function(target) {
            if (target == null) throw new TypeError('Cannot convert undefined or null to object');
            var to = Object(target);
            for (var i = 1; i < arguments.length; i++) {
                var source = arguments[i];
                if (source != null) for (var key in source) {
                    if (Object.prototype.hasOwnProperty.call(source, key)) to[key] = source[key];
                }
            }
            return to;
        };
    }
    if (typeof AbortController === 'undefined') {
        var AbortController = function() { this.signal = { aborted: false }; };
        AbortController.prototype.abort = function() { this.signal.aborted = true; };
    }
    if (typeof URL === 'undefined') {
        var URL = function(url) {
            this.href = url;
            try {
                var m = String(url).match(/^https?:\\/\\/([^/]+)(\\/.*)?$/);
                if (m) { this.hostname = m[1]; this.pathname = m[2] || '/'; }
            } catch(e) {}
        };
    }
    """
}



enum ModuleEngineWiring {
    static func install() {
        ModuleEngine.shared.setImpl(value: ModuleJsEngine.shared)
    }
}


private final class ScriptResult: SourceBridgeStreamCallback {
    private let handler: (String?, String?) -> Void
    init(_ handler: @escaping (String?, String?) -> Void) { self.handler = handler }
    func onResult(json: String?, message: String?) { handler(json, message) }
}
