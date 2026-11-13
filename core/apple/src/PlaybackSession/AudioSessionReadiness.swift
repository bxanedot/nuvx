import Foundation




final class AudioSessionReadiness: @unchecked Sendable {
    enum ActivationError: Error { case invalidatedDuringActivation }
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var active = false

    var isActive: Bool { lock.withLock { active } }

    func invalidate() {
        lock.withLock {
            generation &+= 1
            active = false
        }
    }

    func activate<T>(_ operation: () throws -> T) throws -> T {
        let intent = lock.withLock { () -> UInt64 in
            generation &+= 1
            active = false
            return generation
        }


        let result = try operation()
        try lock.withLock {
            guard generation == intent else { throw ActivationError.invalidatedDuringActivation }
            active = true
        }
        return result
    }
}
