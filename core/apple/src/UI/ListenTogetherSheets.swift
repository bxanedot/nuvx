import SwiftUI
import NuvxShared
import CoreImage
import CoreImage.CIFilterBuiltins
#if os(iOS)
import UIKit
#else
import AppKit
#endif







struct CreatePartySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast
    @Environment(PartyStore.self) private var party
    @Environment(AuthController.self) private var auth



    var onCreated: () -> Void

    @State private var nickname = ""
    @State private var maxMembers = 5

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        PartyAvatar(url: party.avatarUrl, name: nickname, size: 40)
                        TextField(PartyCopy.createNicknamePlaceholder, text: $nickname)
                            .textFieldStyle(.plain)
                            .onChange(of: nickname) { _, value in



                                let trimmed = String(value.prefix(48))
                                if trimmed != value { nickname = trimmed }
                            }
                    }
                    .padding(.vertical, 2)
                } header: {
                    Text(PartyCopy.createSubtitle)
                } footer: {


                    if nickname.isEmpty, !party.accountName.isEmpty {
                        Text("You will show up as \(party.accountName).")
                    }
                }

                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Party size")
                            Text(PartyCopy.createSizeSubtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        StepperStep(symbol: "minus", enabled: maxMembers > 2) {
                            maxMembers -= 1
                        }
                        Text("\(maxMembers)")
                            .font(.title3.monospacedDigit())
                            .frame(minWidth: 32)
                            .multilineTextAlignment(.center)
                        StepperStep(symbol: "plus", enabled: maxMembers < 10) {
                            maxMembers += 1
                        }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Create a party")




            .modifier(PartySheetSubtitle(text: PartyCopy.createSubtitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(party.busy ? "Creating…" : "Create") {
                        Task { await create() }
                    }



                    .disabled(party.busy || !party.hasServer || !auth.signedIn)
                }
            }
            .onAppear {
                if nickname.isEmpty { nickname = party.nickname }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460)
        #endif
    }

    private func create() async {
        do {
            try await party.create(nickname: nickname, autoplay: true)



            if maxMembers != 5 { party.setMaxMembers(Int32(maxMembers)) }
            dismiss()
            onCreated()
            toast.show("Party \(party.code) created")
        } catch {

        }
    }
}





struct JoinPartySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(PartyStore.self) private var party
    @Environment(AuthController.self) private var auth

    @State private var code: String
    @State private var looked = false

    init(initialCode: String = "") {
        _code = State(initialValue: initialCode)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PartyCodeField(code: $code) {
                        Task { await lookUp() }
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                } header: {



                    Text(PartyCopy.joinCodeHint)
                }

                if let message = party.failure {
                    Section {
                        Text(message)
                            .font(.callout)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button(party.busy ? "Looking up…" : "Join") {
                        Task { await lookUp() }
                    }


                    .disabled(party.busy || code.count < 6 || !auth.signedIn || !party.hasServer)
                    .frame(maxWidth: .infinity)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Join a party")
            .modifier(PartySheetSubtitle(text: PartyCopy.joinCodeHint))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460)
        #endif
    }

    private func lookUp() async {
        guard code.count >= 6, !party.busy else { return }
        looked = true
        do {
            let preview = try await party.preview(code: code)
            party.pendingPreview = preview
            dismiss()
        } catch {

        }
        _ = looked
    }
}



struct JoinConfirmSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(PartyStore.self) private var party
    @Environment(AuthController.self) private var auth

    let preview: PartyPreview?
    let server: String?

    @State private var nickname = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        PartyAvatar(url: party.avatarUrl, name: nickname, size: 40)
                        TextField(PartyCopy.createNicknamePlaceholder, text: $nickname)
                            .textFieldStyle(.plain)
                    }
                } footer: {
                    Text(PartyCopy.joinPreviewFooter)
                }

                if let preview {
                    Section {
                        HStack(spacing: 12) {
                            MemberAvatarStack(
                                members: preview.members.map {
                                    (name: $0.displayName, avatar: $0.avatarUrl)
                                },
                                total: Int(preview.memberCount)
                            )
                            VStack(alignment: .leading, spacing: 2) {
                                Text(title(for: preview))
                                    .font(.headline)
                                if preview.memberCount > 0 {
                                    Text("Listening · \(preview.memberCount) of \(preview.maxMembers)")
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 4)
                    }
                    if preview.isFull {
                        Section {
                            Label("This party is full", systemImage: "person.2.slash")
                                .foregroundStyle(.secondary)
                        }
                    }
                    if let server, !server.isEmpty {
                        Section {
                            LabeledContent("Server") {
                                Text(server).lineLimit(1).truncationMode(.middle)
                            }
                        } footer: {
                            Text("This invite points at somebody else’s party server, so joining switches this device to it.")
                        }
                    }
                }

                if let message = party.failure {
                    Section {
                        Text(message)
                            .font(.callout)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button(party.busy ? "Joining…" : "Join") {
                        Task { await join() }
                    }
                    .disabled(party.busy || preview?.isFull == true || !party.hasServer || !auth.signedIn)
                    .frame(maxWidth: .infinity)

                    Button("Not now", role: .cancel) { dismiss() }
                        .frame(maxWidth: .infinity)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Join")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear {
                if nickname.isEmpty { nickname = party.nickname }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460)
        #endif
    }

    private func title(for preview: PartyPreview) -> String {
        PartyCopy.joinQuestion(hostName: preview.hostName)
    }

    private func join() async {
        guard let preview else { return }
        do {
            if let server, !server.isEmpty {





                party.setConfiguredServer(server)
            }
            try await party.join(code: preview.code, nickname: nickname)
            dismiss()
        } catch {

        }
    }
}


struct InviteSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast
    @Environment(PartyStore.self) private var party

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 14) {
                        if let link = party.inviteLink {
                            PartyQRCode.view(for: link)
                                .frame(width: 160, height: 160)
                                .accessibilityLabel("QR code for the party invite")
                        }
                        Text(party.code)
                            .font(.title2.monospaced().weight(.semibold))
                            .tracking(3)
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }

                Section {
                    if let link = party.inviteLink {
                        ShareLink(
                            item: link,
                            subject: Text("Listen together"),
                            message: Text("Listen with me on nuvx — party code \(party.code)")
                        ) {
                            Label("Share invite", systemImage: "link")
                        }
                    }
                    Button {
                        copyCode()
                    } label: {
                        Label("Copy code", systemImage: "doc.on.doc")
                    }
                } footer: {




                    Text(PartyCopy.inviteFooter)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Invite")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460)
        #endif
    }

    private func copyCode() {
        #if os(iOS)
        UIPasteboard.general.string = party.code
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(party.code, forType: .string)
        #endif
        toast.show("Copied \(party.code)")
    }
}








struct PartyServerEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(ToastCenter.self) private var toast
    @Environment(PartyStore.self) private var party

    @State private var address = ""
    @State private var testing = false
    @State private var result: String?
    @State private var reached = false

    var body: some View {
        NavigationStack {
            Form {
                Section {



                    TextField("https://your-server.com", text: $address)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                } header: {
                    Text("Party server")
                } footer: {
                    if let problem = party.serverProblem(address) {
                        Text(problem).foregroundStyle(.red)
                    } else if address.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text("Leave this empty to use the default server.")
                    } else {
                        Text("Everyone in a party has to be pointed at the same server, and it has to be one somebody is running.")
                    }
                }

                Section {
                    Button {
                        Task { await test() }
                    } label: {
                        if testing {
                            HStack {
                                ProgressView().controlSize(.small)
                                Text("Testing…")
                            }
                        } else {
                            Text("Test connection")
                        }
                    }
                    .disabled(testing || party.serverProblem(address) != nil)
                } footer: {
                    if let result {



                        Text(result).foregroundStyle(reached ? Color.accentColor : Color.red)
                    } else {
                        Text("A party server is a deployment somebody has to run. This app does not ship one.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Party server")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(party.serverProblem(address) != nil)
                }
            }
            .onAppear { address = party.configuredServer }
        }
        #if os(macOS)
        .frame(minWidth: 460, idealWidth: 500)
        #endif
    }

    private func test() async {
        testing = true
        result = nil
        party.setConfiguredServer(address)
        await party.resolveServer()
        testing = false




        guard let connection = party.server?.connection else {
            testing = false
            reached = false
            result = "Enter an address to test."
            return
        }
        if let online = connection as? ServerConnectionCustomOnline {
            reached = true
            result = "Answered in \(online.latencyMs) ms."
        } else if connection is ServerConnectionCustomFallback {
            reached = false
            result = "That address did not answer. The default server is being used instead."
        } else if let builtIn = connection as? ServerConnectionDefaultOnline {
            reached = true
            result = "Answered in \(builtIn.latencyMs) ms."
        } else if connection is ServerConnectionOffline {
            reached = false
            result = "Couldn’t reach that address."
        } else if connection is ServerConnectionChecking {
            result = "Still checking."
        } else {
            result = "Enter an address to test."
        }
    }

    private func save() {
        party.setConfiguredServer(address)
        dismiss()
        Task {
            await party.resolveServer()
            toast.show("Party server saved")
        }
    }
}










private struct PartySheetSubtitle: ViewModifier {
    let text: String

    func body(content: Content) -> some View {
        if #available(iOS 26.0, macOS 26.0, *) {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}






struct PartyLanding: View {
    let avatarUrl: String?
    let enabled: Bool
    let serverMissing: Bool
    let busy: Bool
    let onCreate: () -> Void
    let onJoin: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 28)
            GlowingPartyAvatar(url: avatarUrl, size: 104)
            Spacer().frame(height: 20)
            Text(PartyCopy.landingTitle)
                .scaledFont(size: 30, weight: .bold)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 8)
            Text(PartyCopy.landingSubtitle)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 28)
            Button(action: onCreate) {
                Text(busy ? "Working…" : "Create a party")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 30)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .clipShape(Capsule())
            .disabled(!enabled)
            Spacer().frame(height: 6)
            Button(action: onJoin) {
                Text("Join existing")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 26)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .disabled(!enabled)
            if serverMissing {
                Spacer().frame(height: 10)
                Text(PartyCopy.landingNoServer)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity)
    }
}







private struct GlowingPartyAvatar: View {
    let url: String?
    var size: CGFloat = 104

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathing = false

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            Color.accentColor.opacity(breathing ? 0.6 : 0.35),
                            Color.accentColor.opacity(breathing ? 0.21 : 0.12),
                            .clear,
                        ],
                        center: .center,
                        startRadius: 0,
                        endRadius: size * 0.875
                    )
                )


                .blur(radius: 28)
                .frame(width: size * 1.75, height: size * 1.75)
                .scaleEffect(breathing ? 1.14 : 0.92)



                .animation(
                    reduceMotion ? nil : .easeInOut(duration: 2.6).repeatForever(autoreverses: true),
                    value: breathing
                )
            PartyAvatar(url: url, name: "", size: size)
                .overlay { Circle().strokeBorder(.white.opacity(0.12), lineWidth: 2) }
        }
        .frame(width: size * 2.1, height: size * 2.1)
        .onAppear {
            guard !reduceMotion else { return }



            breathing = true
            Task {
                try? await Task.sleep(for: .milliseconds(700))
                self.breathing = false
            }
        }
    }
}











struct PartyCodeField: View {
    @Binding var code: String
    var onSubmit: () -> Void

    @FocusState private var focused: Bool
    @State private var caretOn = false

    private let length = 6

    var body: some View {
        ZStack {
            HStack(spacing: 8) {
                ForEach(0..<length, id: \.self) { index in
                    cell(at: index)
                }
            }
            TextField("", text: $code)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.characters)
                #endif
                .focused($focused)
                .opacity(0.001)
                .accessibilityLabel("Party code")
                .onChange(of: code) { _, value in



                    let cleaned = String(
                        value.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(length)
                    )
                    if cleaned != value {
                        code = cleaned
                        return
                    }
                    if cleaned.count == length { onSubmit() }
                }
                .onSubmit(onSubmit)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            focused = true
            caretOn = true
        }
    }

    private func cell(at index: Int) -> some View {


        let isNext = focused && index == min(code.count, length - 1)
        return RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(.quaternary.opacity(0.5))
            .frame(height: 52)
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(
                        isNext ? Color.accentColor : Color.secondary.opacity(0.35),
                        lineWidth: isNext ? 1.5 : 1
                    )
            }
            .overlay {
                if index < code.count {
                    Text(String(code[code.index(code.startIndex, offsetBy: index)]))
                        .font(.title2)
                } else if isNext {



                    RoundedRectangle(cornerRadius: 1)
                        .fill(Color.accentColor)
                        .frame(width: 2, height: 22)
                        .opacity(caretOn ? 1 : 0.15)
                        .animation(
                            focused && !reduceMotion
                                ? .easeInOut(duration: 0.6).repeatForever(autoreverses: true)
                                : .default,
                            value: caretOn
                        )
                }
            }
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
}






struct MemberAvatarStack: View {





    let members: [(name: String, avatar: String?)]
    let total: Int
    var faceSize: CGFloat = 44
    var shown = 3

    var body: some View {
        HStack(spacing: -12) {
            ForEach(Array(members.prefix(shown).enumerated()), id: \.offset) { _, member in
                PartyAvatar(
                    url: member.avatar,
                    name: member.name,
                    size: faceSize
                )
                .overlay { Circle().strokeBorder(.background, lineWidth: 2) }
            }
            let extra = max(0, total - min(shown, members.count))
            if extra > 0 {
                ZStack {
                    Circle().fill(Color.accentColor)
                    Text("+\(extra)")
                        .scaledFont(size: faceSize * 0.34, weight: .semibold)
                        .foregroundStyle(.white)
                }
                .frame(width: faceSize, height: faceSize)
                .overlay { Circle().strokeBorder(.background, lineWidth: 2) }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(total) in this party")
    }
}







enum PartyQRCode {
    private static let context = CIContext()
    private static let cache = NSCache<NSString, PartyQRImage>()

    #if os(iOS)
    typealias PartyQRImage = UIImage
    #else
    typealias PartyQRImage = NSImage
    #endif




    #if os(iOS)
    static func view(for text: String) -> some View {
        Group {
            if let image = image(for: text) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {



                EmptyView()
            }
        }
    }
    #else
    static func view(for text: String) -> some View {
        Group {
            if let image = image(for: text) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                EmptyView()
            }
        }
    }
    #endif

    static func image(for text: String) -> PartyQRImage? {
        if let cached = cache.object(forKey: text as NSString) { return cached }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        #if os(iOS)
        let image = UIImage(cgImage: cg)
        #else
        let image = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
        #endif
        cache.setObject(image, forKey: text as NSString)
        return image
    }
}
