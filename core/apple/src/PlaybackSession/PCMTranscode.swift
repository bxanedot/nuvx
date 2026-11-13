import Foundation






enum AVFoundationReadable {
    static func canOpen(_ path: String) -> Bool {
        guard let handle = FileHandle(forReadingAtPath: path) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 4), head.count == 4 else { return false }
        let bytes = [UInt8](head)
        if bytes == [0x1A, 0x45, 0xDF, 0xA3] { return false }
        if bytes == Array("OggS".utf8) { return false }
        return true
    }
}










enum PCMTranscode {

    typealias Writer = @Sendable (_ path: String, _ destination: URL) -> Bool

    static func write(path: String, writer: Writer) -> URL? {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("nuvx-analysis-\(UUID().uuidString).wav")
        guard writer(path, url) else {
            try? FileManager.default.removeItem(at: url)
            NSLog("[Nuvx] analysis transcode failed for %@", path)
            return nil
        }
        return url
    }
}
