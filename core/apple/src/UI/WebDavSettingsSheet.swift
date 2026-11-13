import SwiftUI
import NuvxShared














struct WebDavSettingsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast

    @State private var url: String
    @State private var username: String
    @State private var password = ""

    @State private var problem: String?
    @State private var testing = false


    @State private var tested = false

    @MainActor init() {
        let store = WebDavStore.shared
        _url = State(initialValue: store.url)
        _username = State(initialValue: store.username)
    }

    var body: some View {
        Form {
            Section {
                TextField("https://cloud.example.com/dav", text: $url)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)




                    .keyboardType(.URL)
                    #endif
                    .autocorrectionDisabled()
                TextField("Username", text: $username)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                SecureField(passwordHint, text: $password)
            } header: {
                Text("Share")
            } footer: {
                Text(footer)
            }

            Section {
                Button {
                    Task { await test() }
                } label: {
                    if testing {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("Test Connection")
                    }
                }
                .disabled(testing || url.trimmingCharacters(in: .whitespaces).isEmpty)
            } footer: {



                if testing {
                    Text("Checking…")
                        .foregroundStyle(.secondary)
                } else if let problem {
                    Text(problem).foregroundStyle(.red)
                } else if tested {
                    Label("That works.", systemImage: "checkmark.circle")
                        .foregroundStyle(.green)
                }
            }

            Section {
                Button("Save") {
                    save()
                }
                .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if WebDavStore.shared.isConfigured {
                Section {
                    Button("Disconnect", role: .destructive) {
                        WebDavStore.shared.forget()
                        dismiss()
                    }
                } footer: {
                    Text("Forgets the address, the account and the password. Nothing on the server is touched.")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("WebDAV")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            #else
            ToolbarItem(placement: .cancellationAction) {
                Button("Close") { dismiss() }
            }
            #endif
        }


        .onChange(of: url) { _, _ in tested = false; problem = nil }
        .onChange(of: username) { _, _ in tested = false; problem = nil }
    }

    private var passwordHint: String {
        WebDavStore.shared.hasPassword ? "Password (unchanged)" : "Password"
    }

    private var footer: String {
        if WebDavStore.shared.hasPassword {
            return "Your password is in the Keychain and is never shown. Leave the field empty to keep it."
        }
        return "Leave the password empty for a share that does not need one. It is stored in the Keychain, and never in a backup."
    }

    private func test() async {
        testing = true
        problem = nil
        tested = false
        let answer = await WebDavStore.shared.test(
            url: url.trimmingCharacters(in: .whitespaces),
            username: username.trimmingCharacters(in: .whitespaces),



            password: password.isEmpty ? nil : password
        )
        testing = false
        if let answer {
            problem = answer
        } else {
            tested = true
        }
    }

    private func save() {
        let trimmedUrl = url.trimmingCharacters(in: .whitespaces)
        WebDavStore.shared.save(
            url: trimmedUrl,
            username: username.trimmingCharacters(in: .whitespaces),
            password: password.isEmpty ? nil : password
        )



        url = WebDavStore.shared.url
        toast.show("Saved. Your library is in Library → WebDAV.", kind: .success)
        dismiss()
    }
}
