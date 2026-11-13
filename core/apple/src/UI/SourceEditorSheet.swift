import SwiftUI
import NuvxShared









struct SourceEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast


    let config: ConfigDocument

    var onSaved: () async -> Void

    @State private var url = ""
    @State private var name = ""
    @State private var testing = false
    @State private var result: TestOutcome?
    @State private var saving = false
    @State private var autoRefresh = false
    @State private var refreshUrl = ""

    struct TestOutcome {
        var name: String
        var detail: String
    }

    private var isNew: Bool { config.id.isEmpty }






    private var kindName: String { config.kind }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: $url)
                        .textContentType(.URL)
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        #endif
                    TextField("Name (optional)", text: $name)
                } header: {
                    Text("Address")
                } footer: {
                    Text(urlHelp)
                }

                if config.kind == "ADDON" {
                    Section {
                        Toggle("Automatically renew temporary URLs", isOn: $autoRefresh)
                        if autoRefresh {
                            TextField("Renewal link from the addon provider", text: $refreshUrl)
                                .textContentType(.URL)
                                #if os(iOS)
                                .keyboardType(.URL)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                #endif
                        }
                    } header: {
                        Text("Automatic renewal")
                    } footer: {
                        if config.refreshUrlUnavailable == true {
                            Text("The renewal link is temporarily unavailable. Unlock your device or Keychain, then reopen this editor to edit it.")
                        }
                        Text("Use a renewal link supplied by the addon provider. Supported services can return a fresh addon URL automatically. Pages requiring sign-in or a CAPTCHA need manual renewal.")
                    }
                }

                Section {
                    Button {
                        test()
                    } label: {
                        HStack {
                            Text("Test")
                            Spacer()
                            if testing { ProgressView().controlSize(.small) }
                        }
                    }
                    .disabled(testing || !validConfiguration)

                    if let result, !testing {
                        Label {
                            Text(result.detail)
                        } icon: {
                            Image(systemName: icon(for: result.name))
                                .foregroundStyle(colour(for: result.name))
                        }
                        .font(.subheadline)
                    }

                    Button(isNew ? "Add" : "Save") { save() }
                        .disabled(!validConfiguration || saving)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(isNew ? "Add an addon" : "Edit source")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 380)
        #endif
        .onAppear {
            url = config.baseUrl
            name = config.label
            autoRefresh = config.refreshEnabled ?? false
            refreshUrl = config.refreshUrl ?? ""
        }
    }






    private var normalised: String {
        url.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private var validConfiguration: Bool {
        !normalised.isEmpty && (!autoRefresh || !refreshUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || config.refreshUrlUnavailable == true)
    }



    private var urlHelp: String {
        switch config.kind {
        case "MODULE":
            return "The module index — the JSON document listing plugins. Each plugin ships a JavaScript file that nuvx runs to search and stream."
        case "CUSTOM_MODULE":
            return "A module index configured by an earlier build. Still works, but new sources should be addons."
        default:
            return "Paste the addon's URL — either its root or its manifest.json. nuvx reads what it can search and stream, then asks it for tracks over plain HTTP."
        }
    }

    private func icon(for name: String) -> String {
        switch name {
        case "ok": return "checkmark.circle.fill"
        case "rejected": return "exclamationmark.triangle.fill"
        default: return "wifi.slash"
        }
    }

    private func colour(for name: String) -> Color {



        name == "rejected" ? .red : .secondary
    }









    private func test() {
        guard validConfiguration else { return }
        testing = true
        result = nil
        SourceResolverBridge.shared.probeCandidateWithRefresh(
            kind: kindName, baseUrl: normalised, label: name,
            refreshEnabled: autoRefresh, refreshUrl: refreshUrl,
            callback: Verdict { ok, message in
                Task { @MainActor in
                    testing = false
                    let detail = message ?? ""
                    result = TestOutcome(
                        name: ok ? "ok" : "rejected",



                        detail: detail.isEmpty
                            ? (ok
                                ? "Reachable"
                                : "The server answered, but not with something nuvx can use")
                            : detail,
                    )
                }
            }
        )
    }

    private func save() {
        guard validConfiguration else { return }




        if SourceResolverBridge.shared.isDuplicate(
            url: normalised, exceptId: config.id.nilIfEmpty
        ) {
            toast.show("That address is already configured", kind: .failure)
            return
        }
        saving = true
        SourceResolverBridge.shared.saveWithRefresh(
            id: config.id.nilIfEmpty ?? UUID().uuidString,
            kind: kindName,
            label: name,
            baseUrl: normalised,
            enabled: config.enabled,
            refreshEnabled: autoRefresh,
            refreshUrl: refreshUrl,
            preserveUnavailableRefreshUrl: config.refreshUrlUnavailable == true && refreshUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            callback: Saved { ok, message in
                Task { @MainActor in
                    saving = false
                    guard ok else {
                        toast.show(message ?? "Could not save this source", kind: .failure)
                        return
                    }
                    await onSaved()
                    dismiss()
                }
            }
        )
    }
}

extension String {


    var nilIfEmpty: String? { isEmpty ? nil : self }
}






private final class Verdict: SourceResolverBridgeActionCallback {
    private let handler: (Bool, String?) -> Void
    init(_ handler: @escaping (Bool, String?) -> Void) { self.handler = handler }
    func onResult(ok: Bool, message: String?) { handler(ok, message) }
}

private final class Saved: SourceResolverBridgeActionCallback {
    private let handler: (Bool, String?) -> Void
    init(_ handler: @escaping (Bool, String?) -> Void) { self.handler = handler }
    func onResult(ok: Bool, message: String?) { handler(ok, message) }
}
