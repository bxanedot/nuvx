import Darwin
import Foundation









enum MemoryRelief {
    private static let queue = DispatchQueue(label: "nuvx.memory-relief", qos: .utility)
    nonisolated(unsafe) private static var pending: DispatchWorkItem?

    static func soon(after seconds: Double = 3) {
        queue.async {
            pending?.cancel()
            let work = DispatchWorkItem { _ = malloc_zone_pressure_relief(nil, 0) }
            pending = work
            queue.asyncAfter(deadline: .now() + seconds, execute: work)
        }
    }
}
