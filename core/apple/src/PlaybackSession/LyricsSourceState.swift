import Foundation
import NuvxShared
import os
#if os(iOS)
import UIKit
#if DEBUG
import AVFoundation
#endif
#else
import AppKit
#endif



enum LyricsSourceState {
    case notFetched
    case fetching
    case found
    case notFound
    case blocked

    init(_ shared: LyricsBridge.LyricsProviderState) {
        switch shared {
        case .fetching: self = .fetching
        case .found: self = .found
        case .notFound: self = .notFound
        case .blocked: self = .blocked
        default: self = .notFetched
        }
    }
}



struct DefaultsLyricsChoiceStore: LyricsChoiceStore {
    let defaults: UserDefaults
    var key = "lyrics_provider_choices"
    var limit = 300

    private struct Entry: Codable {
        let track: String
        let source: String
    }

    func choice(for trackId: String) -> String? {
        entries().first { $0.track == trackId }?.source
    }

    func remember(_ source: String, for trackId: String) {
        var all = entries()
        all.removeAll { $0.track == trackId }
        all.insert(Entry(track: trackId, source: source), at: 0)
        if all.count > limit { all = Array(all.prefix(limit)) }
        guard let data = try? JSONEncoder().encode(all) else { return }
        replace(with: data)
    }

    var snapshot: Data? { defaults.data(forKey: key) }

    func replace(with data: Data) {



        guard (try? JSONDecoder().decode([Entry].self, from: data)) != nil else { return }
        defaults.set(data, forKey: key)
    }




    @discardableResult
    func merge(with cloudData: Data?, preferLocal: Bool) -> Data? {
        let cloudEntries = cloudData.flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
        let localEntries = entries()
        let orderedEntries = preferLocal
            ? localEntries + cloudEntries
            : cloudEntries + localEntries
        var seen = Set<String>()
        let merged = orderedEntries.filter { seen.insert($0.track).inserted }
        let capped = Array(merged.prefix(limit))
        guard let data = try? JSONEncoder().encode(capped) else { return snapshot }
        replace(with: data)
        return data
    }

    private func entries() -> [Entry] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([Entry].self, from: data) else { return [] }
        return decoded
    }
}





final class UbiquitousLyricsChoiceStore: LyricsChoiceStore, @unchecked Sendable {
    private let local: DefaultsLyricsChoiceStore
    private let cloud: NSUbiquitousKeyValueStore
    private let key: String
    private var observer: NSObjectProtocol?

    init(
        defaults: UserDefaults = .standard,
        cloud: NSUbiquitousKeyValueStore = .default,
        key: String = "lyrics_provider_choices"
    ) {
        self.local = DefaultsLyricsChoiceStore(defaults: defaults, key: key)
        self.cloud = cloud
        self.key = key
        self.observer = nil
        self.observer = NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: cloud,
            queue: nil
        ) { [weak self] notification in
            let changedKeys = notification.userInfo?[NSUbiquitousKeyValueStoreChangedKeysKey] as? [String]
            guard changedKeys == nil || changedKeys?.contains(key) == true else { return }
            self?.refreshLocalFromCloud()
        }
        cloud.synchronize()
        refreshLocalFromCloud()
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    func choice(for trackId: String) -> String? {
        synchronizeSnapshots(preferLocal: false)
        return local.choice(for: trackId)
    }

    func remember(_ source: String, for trackId: String) {
        local.remember(source, for: trackId)
        synchronizeSnapshots(preferLocal: true)
    }

    private func refreshLocalFromCloud() {
        synchronizeSnapshots(preferLocal: false)
    }

    private func synchronizeSnapshots(preferLocal: Bool) {
        let cloudData = cloud.object(forKey: key) as? Data
        guard let data = local.merge(with: cloudData, preferLocal: preferLocal), data != cloudData else { return }
        cloud.set(data, forKey: key)
        cloud.synchronize()
    }
}
