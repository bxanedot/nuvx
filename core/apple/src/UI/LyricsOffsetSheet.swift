import SwiftUI


















struct LyricsOffsetSheet: View {
    @Environment(PlaybackController.self) private var controller
    @Environment(\.dismiss) private var dismiss




    private var offsetMs: Int32 {
        LyricsOffsetBridge.offsetMs()
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Spacer(minLength: 12)




                Text(LyricsOffsetBridge.format(offsetMs))
                    .scaledFont(size: 44, weight: .bold, design: .rounded)
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .animation(.snappy, value: offsetMs)
                    .foregroundStyle(.primary)
                    .padding(.bottom, 4)

                Text("Positive values show lyrics later")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 24)




                HStack(spacing: 20) {
                    stepButton("minus", label: "Earlier", delta: -LyricsOffsetBridge.stepMs) {
                        LyricsOffsetBridge.decrease()
                    }
                    Slider(
                        value: Binding(
                            get: { LyricsOffsetBridge.fraction(offsetMs) },
                            set: { LyricsOffsetBridge.set(fraction: $0) }
                        ),
                        in: 0...1
                    )
                    .accessibilityLabel("Lyrics offset")
                    .accessibilityValue(LyricsOffsetBridge.format(offsetMs))
                    .accessibilityHint("Swipe up or down to adjust when the lyrics appear")
                    stepButton("plus", label: "Later", delta: LyricsOffsetBridge.stepMs) {
                        LyricsOffsetBridge.increase()
                    }
                }
                .padding(.horizontal, 28)



                if offsetMs != 0 {
                    Button("Reset") {
                        LyricsOffsetBridge.reset()
                    }
                    .padding(.top, 20)
                }

                Spacer(minLength: 0)

                Text("""
                    This is a preference, not a per-track setting — the same lag \
                    applies to every track, so it is set once.
                    """)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.bottom, 20)
            }
            .navigationTitle("Lyrics Offset")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #if os(iOS)






        .presentationDetents([.height(340)])
        .presentationDragIndicator(.visible)
        #endif
    }






    private func stepButton(
        _ icon: String, label: String, delta: Int32, action: @escaping () -> Void
    ) -> some View {
        let atLimit = delta < 0
            ? offsetMs <= LyricsOffsetBridge.minMs
            : offsetMs >= LyricsOffsetBridge.maxMs
        return Button(action: action) {
            Image(systemName: icon)
                .scaledFont(size: 17, weight: .bold)
                .frame(width: 44, height: 44)
                .background(.quaternary, in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(atLimit)
        .opacity(atLimit ? 0.35 : 1)
        .accessibilityLabel(label)
    }
}
