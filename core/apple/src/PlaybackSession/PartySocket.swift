import Foundation
import NuvxShared








































final class PartySocket: NSObject, PartySocketBridgeImpl, @unchecked Sendable {


    private static let pingIntervalSeconds: Double = 5




    private static let maxBackoffSeconds: Double = 30

    private static let shared = PartySocket()







    private let lock = NSLock()

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var onFrame: PartySocketBridgeFrameCallback?
    private var loop: Task<Void, Never>?
    private var attempt = 0
    private var stopped = false


    static func register() {
        PartySocketBridge.shared.setImpl(value: shared)
    }



    func connect(base: String, code: String, token: String, onFrame callback: PartySocketBridgeFrameCallback) {
        lock.withLock {
            loop?.cancel()
            stopped = false
            attempt = 0
        }
        loop = Task.detached(priority: .userInitiated) { [weak self] in
            await self?.run(base: base, code: code, token: token, onFrame: callback)
        }
    }

    func sendRaw(json: String) {
        let live = lock.withLock { stopped ? nil : task }



        live?.send(.string(json)) { _ in }
    }

    func stop() {
        let (live, session, loop) = lock.withLock { () -> (URLSessionWebSocketTask?, URLSession?, Task<Void, Never>?) in
            stopped = true
            let taken = (task, self.session, self.loop)
            task = nil
            self.session = nil
            self.onFrame = nil
            self.loop = nil
            return taken
        }




        loop?.cancel()
        live?.cancel(with: .goingAway, reason: nil)
        session?.invalidateAndCancel()
    }



    private func run(base: String, code: String, token: String, onFrame callback: PartySocketBridgeFrameCallback) async {
        while !isStopped {
            guard let url = Self.socketURL(base: base, code: code) else { return }


            var request = URLRequest(url: url)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

            let session = URLSession(configuration: .default)
            let task = session.webSocketTask(with: request)

            let abandoned = lock.withLock { () -> Bool in
                guard !stopped else { return true }
                self.session = session
                self.task = task
                self.onFrame = callback



                self.attempt = 0
                return false
            }
            if abandoned {
                session.invalidateAndCancel()
                return
            }

            task.resume()








            await withTaskGroup(of: Void.self) { group in
                let keepGoing: @Sendable () -> Bool = { [weak self] in self.map { !$0.isStopped } ?? false }
                group.addTask { await Self.read(task: task, onFrame: callback, keepGoing: keepGoing) }
                group.addTask { await Self.ping(task: task, keepGoing: keepGoing) }
                await group.next()
                group.cancelAll()
            }

            task.cancel(with: .goingAway, reason: nil)
            session.invalidateAndCancel()
            let wait = lock.withLock { () -> Duration in
                if self.task === task {
                    self.task = nil
                    self.session = nil
                }
                attempt += 1
                return backoff()
            }




            if isStopped { return }
            try? await Task.sleep(for: wait)
        }
    }

    private var isStopped: Bool {
        lock.withLock { stopped }
    }







    private static func read(
        task: URLSessionWebSocketTask,
        onFrame: PartySocketBridgeFrameCallback,
        keepGoing: @Sendable () -> Bool,
    ) async {
        while keepGoing() && !Task.isCancelled {
            let message: URLSessionWebSocketTask.Message
            do {
                message = try await task.receive()
            } catch {



                return
            }
            let text: String?
            switch message {
            case .string(let value): text = value
            case .data(let value): text = String(data: value, encoding: .utf8)
            @unknown default: text = nil
            }
            guard let text, !text.isEmpty else { continue }
            onFrame.onFrame(json: text)
            if isBye(text) { return }
        }
    }













    private static func ping(task: URLSessionWebSocketTask, keepGoing: @Sendable () -> Bool) async {
        while keepGoing() && !Task.isCancelled {
            task.sendPing { _ in }
            task.send(.string(PartyOutgoingJson.shared.ping(clientMs: localNowMs()))) { _ in }
            try? await Task.sleep(for: .seconds(pingIntervalSeconds))
        }
    }

    private func backoff() -> Duration {
        .milliseconds(Int(min(0.5 * pow(2.0, Double(attempt)), Self.maxBackoffSeconds) * 1000))
    }










    static func socketURL(base: String, code: String) -> URL? {
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        let ws: String
        if trimmed.hasPrefix("https://") {
            ws = "wss://" + trimmed.dropFirst("https://".count)
        } else if trimmed.hasPrefix("http://") {
            ws = "ws://" + trimmed.dropFirst("http://".count)
        } else {
            ws = "wss://" + trimmed
        }
        return URL(string: ws + "/ws/parties/" + code)
    }


    static func isBye(_ text: String) -> Bool {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return false }
        return object["type"] as? String == "bye"
    }







    static func localNowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}
