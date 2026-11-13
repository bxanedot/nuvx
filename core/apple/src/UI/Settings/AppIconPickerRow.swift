import SwiftUI
#if os(iOS)
import UIKit


@MainActor
struct AppIconPickerRow: View {
    private struct Choice: Identifiable {
        let id: String
        let title: String
        let imageName: String
        let alternateName: String?

        static let all = [
            Choice(id: "Default", title: "Default", imageName: "AppIcon", alternateName: nil),
            Choice(
                id: "Monochrome",
                title: "Monochrome",
                imageName: "AppIconMonochrome",
                alternateName: "AppIconMonochrome"
            ),
        ]
    }

    @State private var selectedID = ""
    @State private var isChanging = false
    @State private var errorMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SettingsLine(glyph: .appIcon, title: "App icon") { EmptyView() }
            iconChoices
            errorText
        }
        .task {
            selectedID = UIApplication.shared.alternateIconName ?? "Default"
        }
    }

    private var iconChoices: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(Choice.all) { choice in
                    ChoiceButton(
                        choice: choice,
                        selected: selectedID == choice.id,
                        isChanging: isChanging
                    ) {
                        Task { await apply(choice) }
                    }
                }
            }
            .padding(.leading, 41)
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private var errorText: some View {
        if let errorMessage {
            Text(errorMessage)
                .font(.caption)
                .foregroundStyle(.red)
                .padding(.leading, 41)
        }
    }

    private func apply(_ choice: Choice) async {
        guard !isChanging, selectedID != choice.id else { return }
        isChanging = true
        defer { isChanging = false }
        do {
            try await UIApplication.shared.setAlternateIconName(choice.alternateName)
            selectedID = choice.id
            errorMessage = nil
        } catch {
            errorMessage = "Couldn’t change the app icon: \(error.localizedDescription)"
        }
    }

    private struct ChoiceButton: View {
        let choice: Choice
        let selected: Bool
        let isChanging: Bool
        let action: () -> Void

        var body: some View {
            Button(action: action) {
                VStack(spacing: 7) {
                    icon
                    title
                }
                .frame(width: 84)
                .frame(minHeight: 100)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isChanging)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(choice.title) app icon")
            .accessibilityValue(selected ? "Selected" : "Not selected")
            .accessibilityHint("Changes the nuvx icon on your Home Screen.")
            .accessibilityAddTraits(selected ? [.isSelected] : [])
        }

        private var icon: some View {
            iconImage
                .frame(width: 68, height: 68)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay { iconBorder }
        }

        @ViewBuilder
        private var iconImage: some View {
            if let image = UIImage(named: choice.imageName) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: "app.fill")
                    .resizable()
                    .scaledToFit()
                    .padding(20)
            }
        }

        private var iconBorder: some View {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    selected ? Color.accentColor : Color.secondary.opacity(0.25),
                    lineWidth: selected ? 2 : 1
                )
        }

        private var title: some View {
            HStack(spacing: 4) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .accessibilityHidden(true)
                }
                Text(choice.title)
            }
            .font(selected ? .caption.weight(.semibold) : .caption)
            .foregroundStyle(selected ? Color.accentColor : Color.secondary)
            .lineLimit(1)
        }
    }
}
#endif
