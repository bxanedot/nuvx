import Foundation



enum PlaybackSwapConfirmation {
    static func wait(
        isCurrent: @escaping @Sendable () async -> Bool,
        outcome: @escaping @Sendable () async -> Bool?,
        pollNanoseconds: UInt64 = 100_000_000
    ) async -> Bool? {
        await Task.detached { () -> Bool? in
            while await isCurrent() {
                if let result = await outcome() { return result }
                try? await Task.sleep(nanoseconds: pollNanoseconds)
            }
            return nil
        }.value
    }
}
