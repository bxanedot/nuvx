import SwiftUI
import NuvxShared
















struct SourcesView: View {
    @Environment(ToastCenter.self) private var toast

    @State private var sourceState = SourceListState.shared
    private var configs: [ConfigDocument] { sourceState.configs }
    private var health: [String: Health] { sourceState.health }
    private var probing: Set<String> { sourceState.probing }
    @State private var editing: ConfigDocument?
    @State private var removingAddon: ConfigDocument?

    @State private var confirmingJioSaavn = false



    @State private var editingWebDav = false
    @State private var webDavStore = WebDavStore.shared
    @State private var upgradeLengthSlackSeconds = Int(
        PlatformSettings.shared.getInt(key: "upgrade_length_slack_seconds", default: 3)
    )


    #if os(iOS)
    @State private var editMode: EditMode = .inactive
    #endif

    typealias Health = SourceHealthSnapshot

    var body: some View {
        List {
            if let error = sourceState.loadError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                    Button("Try again") { Task { await reload() } }
                        .disabled(sourceState.loading)
                }
            } else if sourceState.loading && configs.isEmpty {
                ProgressView("Loading sources…")
            }
            Section {
                rowsView
                Button {
                    editing = .newAddon()
                } label: {
                    Label("Add an addon", systemImage: "plus.circle")
                }
                .disabled(sourceState.changing != nil)
            } header: {
                Text("Sources, tried in this order")
            } footer: {
                orderFooter
            }

            Section {
                webDavRow
            } header: {
                Text("Your libraries")
            } footer: {
                Text("A library you host yourself, played from the server rather than from a catalogue. It takes no part in the ranking above — it holds your own files, so it is not standing in for anything.")
                    .lineLimit(nil).fixedSize(horizontal: false, vertical: true)
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #else
        .listStyle(.inset)
        #endif
        .navigationTitle("Sources")
        #if os(iOS)


        .environment(\.editMode, $editMode)
        .toolbar {
            if addonCount > 1 {
                ToolbarItem(placement: .automatic) {
                    Button(editMode == .active ? "Done" : "Reorder") {
                        withAnimation { editMode = editMode == .inactive ? .active : .inactive }
                    }
                }
            }
        }
        #endif
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(item: $editing) { config in
            SourceEditorSheet(config: config) { await reload() }
        }
        .sheet(isPresented: $editingWebDav) {
            WebDavSettingsSheet()
        }
        .alert(
            "Remove addon?",
            isPresented: Binding(
                get: { removingAddon != nil },
                set: { if !$0 { removingAddon = nil } }
            ),
            presenting: removingAddon
        ) { config in
            Button("Remove addon", role: .destructive) { removeAddon(config.id) }
            Button("Cancel", role: .cancel) {}
        } message: { config in
            Text("Remove \(config.displayName) from nuvx's sources?")
        }
        .confirmationDialog(
            jioSaavnWarning,
            isPresented: $confirmingJioSaavn,
            titleVisibility: .visible
        ) {
            Button("Enable JioSaavn", role: .destructive) { setEnabled(jioSaavnId, true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("nuvx will still prefer its copy when the match is the same recording. It will not when it is not.")
        }
    }





    @ViewBuilder
    private var rowsView: some View {
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, config in
            SourceRow(
                position: index + 1,
                config: config,
                health: health[config.id],
                probing: probing.contains(config.id),



                skippedByQuality: config.enabled && !ceilingPermits(config.kind),
                onMetered: isMetered,
                ceiling: ceilingLabel,
                onClick: config.needsServer ? { editing = config } : nil,



                onToggle: toggle(for: config),



                changing: sourceState.changing != nil,
                onCheck: config.needsServer && config.isComplete && config.enabled ? {
                    _ = Task<Void, Never> { await check(config) }
                } : nil,
                handle: config.kind == "ADDON" ? { AnyView(dragHandle(config.id)) } : nil
            )
            .moveDisabled(config.kind != "ADDON" || sourceState.changing != nil)
            #if os(iOS)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if config.kind == "ADDON" {
                    Button("Remove", systemImage: "trash") {
                        removingAddon = config
                    }
                    .tint(.red)
                    .disabled(sourceState.changing != nil)
                }
            }
            #endif
        }
        .onMove(perform: moveAddons)
    }







    private var webDavRow: some View {
        HStack(spacing: 10) {
            Image(systemName: "cloud")
                .font(.title3)
                .foregroundStyle(.primary)
                .frame(width: 26)

            VStack(alignment: .leading, spacing: 2) {
                Text("WebDAV")
                    .font(.body)
                Text(webDavStore.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            if webDavStore.isConfigured {
                Button("Edit") { editingWebDav = true }
                    .buttonStyle(.bordered)
            } else {
                Button("Set Up") { editingWebDav = true }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 5)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .onTapGesture { editingWebDav = true }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        var parts = ["WebDAV"]
        parts.append(webDavStore.isConfigured ? "Set up at \(webDavStore.url)" : "Not set up")
        return parts.joined(separator: ", ")
    }

    @ViewBuilder
    private var orderFooter: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Reachable means the provider answered its checks. Audio quality is verified separately for each track.")
            Text("If a source does not have a track or cannot be reached, nuvx tries the next one. Sources above YouTube can replace its recording when they return a better version.")
            if !AudioRouteState.shared.carriesLossless {
                Text("Bluetooth compresses the output. Choosing Lossless still allows lossless sources; addons that require a lossless output need a speaker or wired connection.")
            }
            if cappedByQuality {
                Text("This connection is set below High quality, so streams are transcoded to fit that limit. Downloads are unaffected.")
            } else if !anyLosslessSource {
                Text("No enabled source can provide lossless audio. Add an addon to play lossless files without transcoding.")
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Track Length Tolerance")
                            .foregroundStyle(.primary)
                        Text("Maximum duration difference allowed for a replacement stream")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Text("\(upgradeLengthSlackSeconds) s")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(
                    value: Binding(
                        get: { Double(upgradeLengthSlackSeconds) },
                        set: { upgradeLengthSlackSeconds = Int($0.rounded()) }
                    ),
                    in: Double(AppSettings.shared.MIN_UPGRADE_LENGTH_SLACK_SECONDS)...Double(AppSettings.shared.MAX_UPGRADE_LENGTH_SLACK_SECONDS),
                    step: 1
                )
                .accessibilityLabel("Track Length Tolerance")
                .accessibilityValue("\(upgradeLengthSlackSeconds) seconds")
                .onChange(of: upgradeLengthSlackSeconds) { _, value in
                    AppSettings.shared.setUpgradeLengthSlackSeconds(value: Int32(value))
                }
            }
            .padding(.top, 6)
            Text("A larger tolerance accepts more source matches; a smaller tolerance is stricter. The default is 3 seconds.")
        }
        .foregroundStyle(.secondary)
        .lineLimit(nil)
        .fixedSize(horizontal: false, vertical: true)
    }



    private func toggle(for config: ConfigDocument) -> ((Bool) -> Void)? {
        if config.kind == "YOUTUBE" { return nil }
        return { on in


            if config.kind == "JIOSAAVN", on, !config.enabled {
                confirmingJioSaavn = true
            } else {
                setEnabled(config.id, on)
            }
        }
    }

    private var jioSaavnWarning: String {
        "JioSaavn matches by searching its own catalogue, which can select a different version of a song — a live take, a remix, the wrong release. YouTube's own recording is what you picked; this is not it."
    }















    private var rows: [ConfigDocument] {
        let addons = configs.filter { $0.kind == "ADDON" }
        let fixed = configs.filter { $0.kind != "ADDON" }
            .sorted { lhs, rhs in
                SourceResolverBridge.shared.rankOf(kind: lhs.kind)
                    < SourceResolverBridge.shared.rankOf(kind: rhs.kind)
            }
        return addons + fixed
    }

    private var addonCount: Int { configs.filter { $0.kind == "ADDON" }.count }

    private var jioSaavnId: String? { configs.first { $0.kind == "JIOSAAVN" }?.id }










    private var ceilingName: String { SourceResolverBridge.shared.activeCeiling() }

    private var ceilingLabel: String { AudioQuality(name: ceilingName).label }

    private var cappedByQuality: Bool { ceilingName != "LOSSLESS" }

    private func ceilingPermits(_ kind: String) -> Bool {
        SourceResolverBridge.shared.activeCeilingPermits(kind: kind)
    }



    private var anyLosslessSource: Bool {
        configs.contains { $0.enabled && $0.isComplete && $0.canServeLossless }
    }



    @MainActor private func reload() async {
        let revision = sourceState.beginLoad()
        let document = await withCheckedContinuation { continuation in
            SourceResolverBridge.shared.configs(callback: ListingReader { continuation.resume(returning: $0) })
        }
        guard !Task.isCancelled else { sourceState.cancelLoad(revision); return }
        guard sourceState.finishLoad(document, revision: revision) else { return }
        for config in configs {
            guard !Task.isCancelled else { return }
            await check(config)
        }
    }

    @MainActor private func check(_ config: ConfigDocument) async {
        guard let request = sourceState.beginProbe(config) else { return }
        let answer = await withCheckedContinuation { continuation in
            SourceResolverBridge.shared.health(configId: config.id, callback: HealthProbe { _, name, detail in
                continuation.resume(returning: Health(name: name, detail: detail))
            })
        }
        sourceState.finishProbe(request, result: Task.isCancelled ? nil : answer)
    }

    @MainActor private func change(_ id: String, operation: @escaping (SourceResolverBridgeActionCallback) -> Void,
                                    accepted: @escaping () -> Void = {}) {
        guard sourceState.beginChange(id) else { return }
        Task { @MainActor in
            let result = await withCheckedContinuation { continuation in
                operation(NoReply { ok, message in continuation.resume(returning: (ok, message)) })
            }
            sourceState.finishChange()
            guard result.0 else {
                toast.show(result.1 ?? "Could not save this source change. Try again.", kind: .failure)
                return
            }
            accepted()
            await reload()
        }
    }

    private func setEnabled(_ id: String?, _ enabled: Bool) {
        guard let id else { return }
        change(id, operation: { callback in
            SourceResolverBridge.shared.setEnabled(configId: id, enabled: enabled, callback: callback)
        }, accepted: { sourceState.acceptEnabled(id, enabled) })
    }

    private func removeAddon(_ id: String) {
        guard configs.contains(where: { $0.id == id && $0.kind == "ADDON" }) else { return }
        change(id, operation: { callback in
            SourceResolverBridge.shared.remove(configId: id, callback: callback)
        }, accepted: { sourceState.acceptRemoved(id) })
    }



    private func moveAddons(from source: IndexSet, to dest: Int) {



        var ordered = rows
        ordered.move(fromOffsets: source, toOffset: dest)
        let ids = ordered.filter { $0.kind == "ADDON" }.map(\.id)
        change("reorder", operation: { callback in
            SourceResolverBridge.shared.reorderAddons(orderedIds: ids, callback: callback)
        }, accepted: { sourceState.acceptOrder(ids) })
    }




    private func dragHandle(_ id: String) -> some View {
        #if os(macOS)
        return AnyView(
            Menu {
                Button("Move Up") { nudge(id, by: -1) }
                    .disabled(rows.first?.id == id)
                Button("Move Down") { nudge(id, by: 1) }
                    .disabled(rows.filter { $0.kind == "ADDON" }.last?.id == id)
            } label: {
                Image(systemName: "line.3.horizontal")
                    .foregroundStyle(.secondary)
                    .help("Change this addon's position")
                    .accessibilityLabel("Reorder")
            }
        )
        #else
        return AnyView(EmptyView())
        #endif
    }

    private func nudge(_ id: String, by offset: Int) {
        var ordered = rows.filter { $0.kind == "ADDON" }
        guard let from = ordered.firstIndex(where: { $0.id == id }) else { return }
        let to = from + offset
        guard to >= 0, to < ordered.count else { return }
        ordered.move(fromOffsets: IndexSet(integer: from), toOffset: to > from ? to + 1 : to)
        let ids = ordered.filter { $0.kind == "ADDON" }.map(\.id)
        change("reorder", operation: { callback in
            SourceResolverBridge.shared.reorderAddons(orderedIds: ids, callback: callback)
        }, accepted: { sourceState.acceptOrder(ids) })
    }

}





private struct SourceRow: View {
    let position: Int
    let config: ConfigDocument
    let health: SourcesView.Health?
    let probing: Bool
    let skippedByQuality: Bool
    let onMetered: Bool
    let ceiling: String
    let onClick: (() -> Void)?

    let onToggle: ((Bool) -> Void)?
    let changing: Bool
    let onCheck: (() -> Void)?
    let handle: (() -> AnyView)?

    var body: some View {



        let dimmed = !config.enabled || skippedByQuality

        HStack(spacing: 10) {
            Text("\(position)")
                .font(.body)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(width: 20, alignment: .trailing)
                .opacity(dimmed ? 0.4 : 1)

            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.primary)
                .frame(width: 26)
                .opacity(dimmed ? 0.4 : 1)

            VStack(alignment: .leading, spacing: 3) {
                if let onClick {
                    Button(action: onClick) { description }
                        .buttonStyle(.plain)
                        .disabled(changing)
                        .accessibilityHint("Edit this source")
                } else {
                    description
                }
                if let onCheck, config.enabled, health?.name == "unreachable" || health?.name == "rejected" {
                    Button("Try again", action: onCheck)
                        .font(.caption).disabled(probing || changing)
                }
            }

            Spacer(minLength: 8)

            if let onToggle {
                Toggle("", isOn: Binding(get: { config.enabled }, set: onToggle))
                    .labelsHidden()
                    .disabled(changing)
                    .accessibilityLabel("Enable \(config.displayName)")
            } else {
                Text("Always on")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if onClick != nil || onCheck != nil {
                Menu {
                    if let onClick { Button("Edit source", action: onClick) }
                    if let onCheck { Button("Check again", action: onCheck).disabled(probing) }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(changing)
                .accessibilityLabel("Actions for \(config.displayName)")
            }
            if let handle { handle().disabled(changing) }
        }
        .padding(.vertical, 5)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .contain)
    }

    private var description: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(config.displayName).font(.body).lineLimit(1)
            statusLine.font(.subheadline).foregroundStyle(lineColour).lineLimit(2)
            if config.kind == "ADDON" {
                Text(renewalDescription).font(.caption).foregroundStyle(.secondary)
            }
            if let checkedAt = health?.checkedAt, config.enabled && !probing {
                Text("Checked \(checkedAt.formatted(date: .omitted, time: .shortened))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .opacity(!config.enabled || skippedByQuality ? 0.4 : 1)
    }

    private var renewalDescription: String {
        if config.refreshUrlUnavailable == true { return "Renewal link unavailable · unlock your device or Keychain" }
        return config.refreshEnabled == true ? "Automatic renewal on · renews when needed" : "Automatic renewal off · configure in Edit source"
    }

    private var icon: String {
        switch config.kind {
        case "ADDON", "MODULE", "CUSTOM_MODULE": return "puzzlepiece.extension"
        case "JIOSAAVN": return "waveform"
        default: return "play.circle"
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if !config.enabled {
            Text("Off · not checked or used for playback")
        } else if !config.isComplete {
            Text("Tap to finish setting up")
        } else if config.kind == "JIOSAAVN" {
            Text("May match the wrong version of a song. Enable at your own risk.")
        } else if skippedByQuality {
            Text("Not used on \(onMetered ? "mobile data" : "Wi-Fi") · quality is set to \(ceiling)")
        } else if probing {
            Text("Checking…").foregroundStyle(.secondary)
        } else if let health {
            switch health.name {
            case "ok":
                Text(["Reachable", health.detail, config.labels.prefix(3).joined(separator: " · ")]
                    .filter { !$0.isEmpty }
                    .joined(separator: " · "))
            case "rejected":
                Text("Needs attention · \(health.detail)")
            default:
                Text("Can’t reach it right now · \(health.detail)")
            }
        } else if config.needsServer {
            Text("Not checked yet").foregroundStyle(.secondary)
        } else {
            Text(config.labels.prefix(3).joined(separator: " · "))
        }
    }





    private var lineColour: Color {
        guard !skippedByQuality, !probing, config.isComplete,
              config.kind != "JIOSAAVN", health?.name == "rejected"
        else { return .secondary }
        return .red
    }

}





struct AudioQuality {
    var name: String

    var label: String {
        switch name {
        case "LOW": return "Low"
        case "MEDIUM": return "Medium"
        case "HIGH": return "High"
        default: return "Lossless"
        }
    }
}




private final class ListingReader: SourceResolverBridgeConfigsCallback {
    private let handler: (String?) -> Void
    init(_ handler: @escaping (String?) -> Void) { self.handler = handler }
    func onResult(json: String?) { handler(json) }
}

private final class HealthProbe: SourceResolverBridgeHealthCallback {
    private let handler: (String, String, String) -> Void
    init(_ handler: @escaping (String, String, String) -> Void) { self.handler = handler }
    func onResult(configId: String, health: String, detail: String) {
        handler(configId, health, detail)
    }
}

private final class NoReply: SourceResolverBridgeActionCallback {
    private let handler: (Bool, String?) -> Void
    init(_ handler: @escaping (Bool, String?) -> Void) { self.handler = handler }
    func onResult(ok: Bool, message: String?) { handler(ok, message) }
}









private var isMetered: Bool {
    SourceResolverBridge.shared.activeConnectionIsMetered()
}
