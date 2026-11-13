import SwiftUI


















struct Marquee<Content: View>: View {
    var enabled = true
    var startDelay: Double = 0
    var onOverflowChange: (Bool) -> Void = { _ in }
    @ViewBuilder var content: Content

    @State private var contentWidth: CGFloat = 0
    @State private var boxWidth: CGFloat = 0
    @State private var anchor = Date()

    private static var gap: CGFloat { 48 }
    private static var speed: CGFloat { 26 }
    private static var rest: Double { 5 }
    private static var fade: CGFloat { 20 }

    private var overflowing: Bool { boxWidth > 0 && contentWidth > boxWidth + 0.5 }
    private var crawls: Bool { enabled && overflowing }
    private var travel: CGFloat { contentWidth + Self.gap }
    private var passDuration: Double { max(0.4, Double(travel / Self.speed)) }

    var body: some View {
        content
            .fixedSize()
            .hidden()
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { contentWidth = $0 }


            .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { boxWidth = $0 }
            .overlay(alignment: .leading) {
                TimelineView(MarqueeSchedule(
                    anchor: anchor, startDelay: startDelay, pass: passDuration,
                    rest: Self.rest, paused: !crawls
                )) { context in
                    let left = crawls ? remainingPass(at: context.date) : nil
                    HStack(spacing: Self.gap) {
                        content.fixedSize()
                        if crawls { content.fixedSize() }
                    }
                    .offset(x: left == nil ? 0 : -travel)



                    .animation(left.map { .linear(duration: $0) }, value: left == nil)
                    .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
                    .mask(fadeMask(away: left != nil))
                }
            }
            .clipped()
            .onChange(of: crawls, initial: true) { _, now in
                anchor = Date()
                onOverflowChange(now)
            }
            .onChange(of: contentWidth) { anchor = Date() }
    }



    private func remainingPass(at date: Date) -> Double? {
        let elapsed = date.timeIntervalSince(anchor) - startDelay
        guard elapsed >= 0 else { return nil }
        let cycle = passDuration + Self.rest
        let within = elapsed.truncatingRemainder(dividingBy: cycle)
        let left = passDuration - within

        return left > 0.05 ? left : nil
    }

    @ViewBuilder
    private func fadeMask(away: Bool) -> some View {
        if overflowing {
            HStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(away ? 0 : 1), .black], startPoint: .leading, endPoint: .trailing)
                    .frame(width: Self.fade)
                    .animation(.easeInOut(duration: 0.3), value: away)
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing)
                    .frame(width: Self.fade)
            }
        } else {
            Rectangle()
        }
    }
}



private struct MarqueeSchedule: TimelineSchedule {
    var anchor: Date
    var startDelay: Double
    var pass: Double
    var rest: Double
    var paused: Bool

    func entries(from startDate: Date, mode: TimelineScheduleMode) -> AnyIterator<Date> {
        if paused {
            var sent = false
            return AnyIterator {
                defer { sent = true }
                return sent ? nil : startDate
            }
        }
        let origin = anchor.addingTimeInterval(startDelay)
        let cycle = pass + rest
        var next: Date? = startDate
        return AnyIterator {
            guard let current = next else { return nil }
            let elapsed = current.timeIntervalSince(origin)
            if elapsed < 0 {
                next = origin
            } else {
                let within = elapsed.truncatingRemainder(dividingBy: cycle)

                next = current.addingTimeInterval((within < pass ? pass - within : cycle - within) + 0.001)
            }
            return current
        }
    }
}


struct ExplicitMark: View {
    var color: Color

    var body: some View {
        Image(systemName: "e.square.fill")
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(color)
            .accessibilityLabel("Explicit")
    }
}
