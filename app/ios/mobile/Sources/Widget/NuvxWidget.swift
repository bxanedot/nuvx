import Foundation
import SwiftUI
import WidgetKit
import AppIntents







private let groupDefaults = UserDefaults(suiteName: "group.com.bxane.nuvx")

struct MediaWidgetEntry: TimelineEntry {
    let date: Date
    let title: String
    let artist: String
    let playing: Bool
    let canNext: Bool
    let canPrevious: Bool
    let artwork: Data?
    let hasSession: Bool



    static let empty = MediaWidgetEntry(
        date: .now, title: "nuvx", artist: "Nothing queued",
        playing: false, canNext: false, canPrevious: false,
        artwork: nil, hasSession: false
    )
}

struct MediaWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> MediaWidgetEntry { snapshot() }

    func getSnapshot(in context: Context, completion: @escaping (MediaWidgetEntry) -> Void) {
        completion(snapshot())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MediaWidgetEntry>) -> Void) {


        completion(Timeline(entries: [snapshot()], policy: .never))
    }

    private func snapshot() -> MediaWidgetEntry {
        guard let defaults = groupDefaults, let title = defaults.string(forKey: "widget.title") else {
            return .empty
        }
        return MediaWidgetEntry(
            date: .now,
            title: title,
            artist: defaults.string(forKey: "widget.artist") ?? "",
            playing: defaults.bool(forKey: "widget.playing"),
            canNext: defaults.bool(forKey: "widget.canNext"),
            canPrevious: defaults.bool(forKey: "widget.canPrevious"),
            artwork: defaults.string(forKey: "widget.artworkPath").flatMap { try? Data(contentsOf: URL(fileURLWithPath: $0)) },
            hasSession: true
        )
    }
}

struct MediaWidgetView: View {
    var entry: MediaWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .widgetURL(URL(string: "nuvx://open-player"))
            .containerBackground(.fill.tertiary, for: .widget)
    }

    @ViewBuilder
    private var content: some View {
        if entry.hasSession {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    artwork
                        .frame(width: 56, height: 56)
                        .clipShape(.rect(cornerRadius: 9, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.title)
                            .font(.callout.weight(.semibold))
                            .lineLimit(1)
                        Text(entry.artist)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        HStack(spacing: 6) {
                            Image(systemName: entry.playing ? "pause.fill" : "play.fill")
                                .font(.system(size: 10, weight: .bold))
                            Text(entry.playing ? "Playing" : "Ready to play")
                                .font(.caption2.weight(.medium))
                        }
                        .foregroundStyle(entry.playing ? Color.accentColor : .secondary)
                    }
                    Spacer(minLength: 0)
                }
                if family != .systemSmall {
                    HStack(spacing: 16) {
                        Button(intent: WidgetTransportIntent(command: "previous")) {
                            Image(systemName: "backward.fill")
                        }
                        .disabled(!entry.canPrevious)
                        Button(intent: WidgetTransportIntent(command: "toggle")) {
                            Image(systemName: entry.playing ? "pause.fill" : "play.fill")
                        }
                        Button(intent: WidgetTransportIntent(command: "next")) {
                            Image(systemName: "forward.fill")
                        }
                        .disabled(!entry.canNext)
                        Spacer(minLength: 0)
                    }
                    .font(.title3)
                    .tint(.primary)
                }
            }
            .padding(4)
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
                Text("nuvx")
                    .font(.callout.weight(.semibold))
            }
        }
    }

    @ViewBuilder
    private var artwork: some View {
        if let data = entry.artwork, let image = platformImage(data) {
            Image(platform: image)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                LinearGradient(
                    colors: [Color(red: 0.12, green: 0.10, blue: 0.16),
                             Color(red: 0.05, green: 0.05, blue: 0.08)],
                    startPoint: .top, endPoint: .bottom
                )
                Image(systemName: "music.note")
                    .foregroundStyle(.white.opacity(0.55))
            }
        }
    }
}

#if os(macOS)
private func platformImage(_ data: Data) -> NSImage? { NSImage(data: data) }
private extension Image {
    init(platform image: NSImage) { self.init(nsImage: image) }
}
#else
private func platformImage(_ data: Data) -> UIImage? { UIImage(data: data) }
private extension Image {
    init(platform image: UIImage) { self.init(uiImage: image) }
}
#endif

struct NuvxMediaWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NuvxMediaWidget", provider: MediaWidgetProvider()) { entry in
            MediaWidgetView(entry: entry)
        }
        .configurationDisplayName("nuvx")
        .description("Now-playing state, transport controls, and a tap back into the player.")

        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct NuvxWidgetBundle: WidgetBundle {
    var body: some Widget {
        NuvxMediaWidget()
    }
}

struct WidgetTransportIntent: AppIntent {
    static var title: LocalizedStringResource = "nuvx Transport"
    static var isDiscoverable = false

    @Parameter(title: "Command")
    var command: String

    init() { command = "toggle" }
    init(command: String) { self.command = command }

    func perform() async throws -> some IntentResult {
        UserDefaults(suiteName: "group.com.bxane.nuvx")?.set(command, forKey: "widget.command")
        return .result()
    }
}
