import Foundation
import NuvxShared











enum LyricsOffsetBridge {
    private static let key = "lyrics_offset_ms"






    static var stepMs: Int32 { LyricsOffset.shared.stepMs() }
    static var minMs: Int32 { LyricsOffset.shared.minMs() }
    static var maxMs: Int32 { LyricsOffset.shared.maxMs() }

    static func offsetMs() -> Int32 {
        LyricsOffset.shared.coerce(
            value: PlatformSettings.shared.getInt(
                key: key, default: LyricsOffset.shared.defaultMs()
            )
        )
    }



    private static func write(_ value: Int32) {
        let clamped = LyricsOffset.shared.coerce(value: value)
        PlatformSettings.shared.putInt(key: key, value: clamped)
        NotificationCenter.default.post(name: .lyricsOffsetChanged, object: nil)
    }

    static func set(fraction: Float) {
        write(LyricsOffset.shared.value(fraction: fraction))
    }

    static func increase() {
        write(LyricsOffset.shared.increase(value: offsetMs()))
    }

    static func decrease() {
        write(LyricsOffset.shared.decrease(value: offsetMs()))
    }

    static func reset() {
        write(LyricsOffset.shared.defaultMs())
    }

    static func format(_ value: Int32) -> String {
        LyricsOffset.shared.format(value: value)
    }

    static func fraction(_ value: Int32) -> Float {
        LyricsOffset.shared.fraction(value: value)
    }
}

extension Notification.Name {




    static let lyricsOffsetChanged = Notification.Name("nuvx.lyricsOffsetChanged")




    static let libraryPlaylistsChanged = Notification.Name("nuvx.libraryPlaylistsChanged")




    static let sessionExpired = Notification.Name("nuvx.sessionExpired")




    static let playlistDeleted = Notification.Name("nuvx.playlistDeleted")
}
