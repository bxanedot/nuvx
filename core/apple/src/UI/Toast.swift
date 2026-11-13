import SwiftUI












@Observable
final class ToastCenter {





    struct Action: Equatable {
        let title: String
        let perform: () -> Void

        static func == (a: Action, b: Action) -> Bool { a.title == b.title }
    }

    struct Notice: Identifiable, Equatable {
        enum Kind: Equatable {
            case success
            case failure
            case info
        }
        let id = UUID()
        let message: String
        let kind: Kind
        var action: Action?
    }

    private(set) var current: Notice?







    private(set) var trackLogEntry: QueueEntry?



    private(set) var confirmation: ConfirmationRequest?

    @ObservationIgnored private var dismissTask: Task<Void, Never>?



    private let dwell: Duration = .seconds(3)

    func show(_ message: String, kind: Notice.Kind = .success, action: Action? = nil) {
        show(message, kind: kind, action: action, dwell: Self.readingDwell(message, floor: dwell))
    }






    static func readingDwell(_ message: String, floor: Duration) -> Duration {
        let seconds = min(10, max(Double(floor.components.seconds), 1.5 + Double(message.count) / 12))
        return .milliseconds(Int(seconds * 1000))
    }







    func queueNotice(_ message: String) {
        show(message, kind: .info, dwell: .seconds(2))
    }

    private func show(_ message: String, kind: Notice.Kind, action: Action? = nil, dwell: Duration) {
        dismissTask?.cancel()
        current = Notice(message: message, kind: kind, action: action)
        dismissTask = Task { [weak self] in
            try? await Task.sleep(for: dwell)
            guard !Task.isCancelled else { return }
            self?.current = nil
        }
    }

    func dismiss() {
        dismissTask?.cancel()
        current = nil
    }


    func showTrackLog(_ entry: QueueEntry) {
        trackLogEntry = entry
    }

    func dismissTrackLog() {
        trackLogEntry = nil
    }




    func requestConfirmation(_ request: ConfirmationRequest) {
        confirmation = request
    }

    func resolveConfirmation() {
        confirmation?.onConfirm()
        confirmation = nil
    }

    func dismissConfirmation() {
        confirmation = nil
    }
}






struct ConfirmationRequest: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let confirm: String
    let onConfirm: () -> Void
}







struct ToastHost: View {
    @Environment(ToastCenter.self) private var center

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            if let notice = center.current {
                ToastBanner(notice: notice)
                    .transition(.move(edge: .bottom).combined(with: .opacity))



                    .padding(.bottom, Self.bottomClearance)
                    .padding(.horizontal, 16)
            }
        }
        .animation(.spring(response: 0.36, dampingFraction: 0.86), value: center.current)


        .allowsHitTesting(center.current != nil)
        .sheet(item: trackLogBinding) { entry in
            TrackLogSheet(entry: entry)
        }
        .sheet(item: confirmationBinding) { request in
            ConfirmationDialog(
                title: request.title,
                message: request.message,
                confirm: request.confirm
            ) {
                center.resolveConfirmation()
            }
        }
    }

    #if os(macOS)
    private static let bottomClearance: CGFloat = 96
    #else
    private static let bottomClearance: CGFloat = 8
    #endif

    private var trackLogBinding: Binding<QueueEntry?> {
        Binding(
            get: { center.trackLogEntry },
            set: { if $0 == nil { center.dismissTrackLog() } }
        )
    }

    private var confirmationBinding: Binding<ConfirmationRequest?> {
        Binding(
            get: { center.confirmation },
            set: { if $0 == nil { center.dismissConfirmation() } }
        )
    }
}

private struct ToastBanner: View {
    let notice: ToastCenter.Notice
    @Environment(ToastCenter.self) private var center

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.callout)
                .foregroundStyle(tint)


            Text(notice.message)
                .font(.callout)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            if let action = notice.action {
                Button(action.title) {
                    action.perform()
                    center.dismiss()
                }
                .buttonStyle(.plain)
                .font(.callout.weight(.semibold))
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)


        .background(.regularMaterial, in: shape)
        .overlay {
            shape.strokeBorder(.separator.opacity(0.5), lineWidth: 0.5)
        }
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
        .frame(maxWidth: 420)

        .help(notice.message)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
    }

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: notice.message.count > 48 ? 18 : 22, style: .continuous)
    }

    private var symbol: String {
        switch notice.kind {
        case .success: "checkmark.circle.fill"
        case .failure: "exclamationmark.triangle.fill"
        case .info: "info.circle.fill"
        }
    }

    private var tint: Color {
        switch notice.kind {
        case .success: .green
        case .failure: .orange
        case .info: .accentColor
        }
    }
}
