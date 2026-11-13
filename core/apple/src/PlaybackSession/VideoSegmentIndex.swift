import Foundation











struct VideoSegmentIndex: Equatable, Sendable {
    struct Segment: Equatable, Sendable {
        let offset: Int64
        let size: Int64
        let seconds: Double
    }


    let initEnd: Int64
    let segments: [Segment]




    static func parse(head: Data) -> VideoSegmentIndex? {
        let bytes = [UInt8](head)
        var position = 0
        var moovEnd: Int64?
        while position + 8 <= bytes.count {
            var size = Int64(bytes.uint32(at: position))
            let type = String(decoding: bytes[(position + 4)..<(position + 8)], as: UTF8.self)
            var header = 8
            if size == 1 {
                guard position + 16 <= bytes.count else { return nil }
                size = Int64(bytes.uint64(at: position + 8))
                header = 16
            }
            guard size >= Int64(header) else { return nil }
            let end = Int64(position) + size
            switch type {
            case "moov":
                moovEnd = end
            case "sidx":
                guard end <= Int64(bytes.count), let moovEnd else { return nil }
                return sidx(bytes, body: position + header, end: end, initEnd: moovEnd)
            case "moof", "mdat":

                return nil
            default:
                break
            }
            guard end <= Int64(Int.max) else { return nil }
            position = Int(end)
        }
        return nil
    }

    private static func sidx(_ bytes: [UInt8], body: Int, end: Int64, initEnd: Int64) -> VideoSegmentIndex? {
        var cursor = body
        func need(_ count: Int) -> Bool { Int64(cursor + count) <= end }
        guard need(12) else { return nil }
        let version = bytes[cursor]
        cursor += 4
        cursor += 4
        let timescale = Double(bytes.uint32(at: cursor))
        cursor += 4
        guard timescale > 0 else { return nil }
        let firstOffset: Int64
        if version == 0 {
            guard need(8) else { return nil }
            firstOffset = Int64(bytes.uint32(at: cursor + 4))
            cursor += 8
        } else {
            guard need(16) else { return nil }
            firstOffset = Int64(bytes.uint64(at: cursor + 8))
            cursor += 16
        }
        guard need(4) else { return nil }
        let count = Int(bytes.uint16(at: cursor + 2))
        cursor += 4
        guard count > 0, need(count * 12) else { return nil }
        var offset = end + firstOffset
        var segments: [Segment] = []
        segments.reserveCapacity(count)
        for _ in 0..<count {
            let reference = bytes.uint32(at: cursor)


            guard reference & 0x8000_0000 == 0 else { return nil }
            let size = Int64(reference & 0x7FFF_FFFF)
            let seconds = Double(bytes.uint32(at: cursor + 4)) / timescale
            segments.append(Segment(offset: offset, size: size, seconds: seconds))
            offset += size
            cursor += 12
        }
        return VideoSegmentIndex(initEnd: initEnd, segments: segments)
    }


    func playlist(url: String) -> String {
        let target = Int((segments.map(\.seconds).max() ?? 1).rounded(.up))
        var lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-TARGETDURATION:\(max(1, target))",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MAP:URI=\"\(url)\",BYTERANGE=\"\(initEnd)@0\"",
        ]
        for segment in segments {
            lines.append(String(format: "#EXTINF:%.3f,", segment.seconds))
            lines.append("#EXT-X-BYTERANGE:\(segment.size)@\(segment.offset)")
            lines.append(url)
        }
        lines.append("#EXT-X-ENDLIST")
        return lines.joined(separator: "\n") + "\n"
    }
}

private extension [UInt8] {
    func uint16(at index: Int) -> UInt16 {
        UInt16(self[index]) << 8 | UInt16(self[index + 1])
    }
    func uint32(at index: Int) -> UInt32 {
        (index..<index + 4).reduce(0) { $0 << 8 | UInt32(self[$1]) }
    }
    func uint64(at index: Int) -> UInt64 {
        (index..<index + 8).reduce(0) { $0 << 8 | UInt64(self[$1]) }
    }
}
