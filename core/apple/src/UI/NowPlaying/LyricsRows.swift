import SwiftUI
import NuvxShared





















struct LyricsClock {
    var playing: Bool
    var read: (Date) -> Double


    func lyricMs(at date: Date, offsetMs: Int32) -> Int64 {
        max(0, Int64(read(date) * 1000) - Int64(offsetMs))
    }
}



struct LyricFocusState: Equatable {
    var document: ObjectIdentifier?
    var active: [Int] = []
    var lead = 0
    var gap: Int?
}







struct LyricTimeline {
    private let starts: [Int64]
    private let ends: [Int64]
    private let holds: [Bool]
    private let gaps: [Bool]
    let synced: Bool

    static let scrollLeadMinMs: Int64 = 350
    static let scrollLeadMaxMs: Int64 = 500

    init(lines: [LyricLineDto], synced: Bool) {
        var starts: [Int64] = [], ends: [Int64] = [], holds: [Bool] = [], gaps: [Bool] = []
        starts.reserveCapacity(lines.count)
        ends.reserveCapacity(lines.count)
        holds.reserveCapacity(lines.count)
        gaps.reserveCapacity(lines.count)
        starts = Self.startTimes(lines)
        for line in lines {
            ends.append(line.endMs)
            holds.append(line.hasKnownEnd || line.background?.hasKnownEnd == true)
            gaps.append(line.isGap)
        }
        self.starts = starts
        self.ends = ends
        self.holds = holds
        self.gaps = gaps
        self.synced = synced
    }






    static func startTimes(_ lines: [LyricLineDto]) -> [Int64] {
        let raw = lines.map(startMs)
        let known = raw.indices.map { $0 == 0 || raw[$0] > 0 }
        if known.allSatisfy({ $0 }) { return raw }
        var starts = raw
        var previous = 0
        var index = 1
        while index < lines.count {
            if known[index] {
                previous = index
                index += 1
                continue
            }
            var next = index
            while next < lines.count, !known[next] { next += 1 }
            let base = raw[previous]
            for missing in index..<next {
                let steps = Int64(missing - previous)
                starts[missing] = next < lines.count
                    ? base + max(raw[next] - base, 0) * steps / Int64(next - previous)
                    : base + untimedLineMs * steps
            }
            index = next
        }
        return starts
    }

    private static let untimedLineMs: Int64 = 4_000


    static func startMs(_ line: LyricLineDto) -> Int64 {
        guard line.timeMs == 0 else { return line.timeMs }
        if let first = line.words.map(\.startMs).min() { return first }
        if let first = line.background?.words.map(\.startMs).min() { return first }
        return line.timeMs
    }

    private func lastStarted(by ms: Int64, skippingGaps: Bool = false) -> Int {
        var index = starts.count - 1
        while index >= 0 {
            if starts[index] <= ms, !(skippingGaps && gaps[index]) { return index }
            index -= 1
        }
        return -1
    }

    func focus(at ms: Int64, document: ObjectIdentifier?) -> LyricFocusState {
        guard synced, !starts.isEmpty else { return LyricFocusState(document: document) }
        let latest = lastStarted(by: ms)
        var active: [Int] = []
        if latest >= 0 {
            for index in 0...latest where !gaps[index] {
                if index == latest || (holds[index] && starts[index] <= ms && ms < ends[index]) {
                    active.append(index)
                }
            }
        }
        let lead: Int
        if let first = active.first {
            lead = first
        } else {
            lead = lastStarted(by: ms + scrollLead(latest: latest), skippingGaps: true)
        }
        return LyricFocusState(
            document: document,
            active: active,
            lead: lead < 0 ? 0 : min(lead, starts.count - 1),
            gap: latest >= 0 && gaps[latest] ? latest : nil
        )
    }

    private func scrollLead(latest: Int) -> Int64 {
        guard latest >= 0, latest + 1 < starts.count else { return Self.scrollLeadMinMs }
        return min(max(starts[latest + 1] - ends[latest], Self.scrollLeadMinMs), Self.scrollLeadMaxMs)
    }
}

struct LyricWord: Equatable {
    let text: String
    let startMs: Int64
    let endMs: Int64
}



struct LyricRow: Identifiable {
    let id: Int
    let isGap: Bool


    let timed: Bool
    let secondVoice: Bool
    let rightToLeft: Bool


    let leadText: String
    let backingText: String?


    let leadSwept: Text?
    let backingSwept: Text?
    let timeMs: Int64

    let untilMs: Int64
}


final class PreparedLyrics {
    let identity: [ObjectIdentifier]
    let rows: [LyricRow]
    let timeline: LyricTimeline
    let synced: Bool

    let duet: Bool

    let fullyTimed: Bool

    init(lines: [LyricLineDto]) {
        identity = lines.map(ObjectIdentifier.init)
        let synced = LyricFocus.shared.isSynced(lines: lines)
        self.synced = synced
        timeline = LyricTimeline(lines: lines, synced: synced)
        var rows: [LyricRow] = []
        rows.reserveCapacity(lines.count)
        let starts = LyricTimeline.startTimes(lines)
        for (index, line) in lines.enumerated() {
            rows.append(Self.row(line, at: index, synced: synced,
                                 until: index + 1 < lines.count ? starts[index + 1] : line.endMs))
        }
        self.rows = rows
        duet = lines.contains { $0.alignment == .end }
        let lyricRows = rows.filter { !$0.isGap }
        fullyTimed = lyricRows.allSatisfy(\.timed)
    }

    private static func row(_ line: LyricLineDto, at index: Int, synced: Bool, until: Int64) -> LyricRow {
        let leadText: String
        let backingText: String?
        let leadWords: [LyricWord]
        let backingWords: [LyricWord]
        if let background = line.background, !background.text.isEmpty {
            leadText = line.text
            backingText = background.text
            leadWords = line.words.map(word)
            backingWords = background.words.map(word)
        } else {
            let split = splitBackground(line.text)
            leadText = split.lead
            backingText = split.backing
            leadWords = line.words.filter { !isBackingToken($0.text) }.map(word)
            backingWords = line.words.filter { isBackingToken($0.text) }.map(word)
        }
        let timed = line.timeMs > 0 || line.sungUntilMs != nil ||
            line.words.contains(where: { $0.startMs > 0 || $0.endMs > 0 }) ||
            (line.background?.timeMs ?? 0) > 0 ||
            line.background?.words.contains(where: { $0.startMs > 0 || $0.endMs > 0 }) == true ||

            (index == 0 && synced)
        let shown = leadWords.isEmpty ? leadText : leadWords.map(\.text).joined(separator: " ")
        let backingShown = backingWords.isEmpty ? backingText : backingWords.map(\.text).joined(separator: " ")
        return LyricRow(
            id: index,
            isGap: line.isGap,
            timed: timed,
            secondVoice: line.alignment == .end,
            rightToLeft: lyricTextIsRightToLeft(line.text),
            leadText: shown.isEmpty ? "♪" : shown,
            backingText: backingShown.flatMap { $0.isEmpty ? nil : $0 },
            leadSwept: swept(leadWords),
            backingSwept: swept(backingWords),
            timeMs: line.timeMs,
            untilMs: until
        )
    }

    private static func word(_ dto: LyricWordDto) -> LyricWord {
        LyricWord(
            text: dto.text.replacingOccurrences(of: "(", with: "").replacingOccurrences(of: ")", with: ""),
            startMs: dto.startMs,
            endMs: dto.endMs
        )
    }


    private static func swept(_ words: [LyricWord]) -> Text? {
        guard !words.isEmpty else { return nil }
        var text = Text(verbatim: "")
        for (index, word) in words.enumerated() {
            let piece = Text(verbatim: word.text)
                .customAttribute(LyricWordTiming(startMs: word.startMs, endMs: word.endMs))
            text = index == 0 ? piece : Text("\(text) \(piece)")
        }
        return text
    }

    private static func isBackingToken(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        return trimmed.hasPrefix("(") || trimmed.hasSuffix(")")
    }


    static func splitBackground(_ text: String) -> (lead: String, backing: String?) {
        guard let open = text.firstIndex(of: "("), let close = text.lastIndex(of: ")"), close > open else {
            return (text, nil)
        }
        let inner = text[text.index(after: open)..<close].trimmingCharacters(in: .whitespaces)
        guard !inner.isEmpty else { return (text, nil) }
        let lead = (text[..<open] + text[text.index(after: close)...]).trimmingCharacters(in: .whitespaces)
        return (lead.isEmpty ? "♪" : String(lead), String(inner))
    }
}



final class LyricsDocumentCache {
    private var current: PreparedLyrics?

    func document(for lines: [LyricLineDto]) -> PreparedLyrics {
        if let current, current.identity.count == lines.count,
           zip(current.identity, lines).allSatisfy({ $0 == ObjectIdentifier($1) }) {
            return current
        }
        let prepared = PreparedLyrics(lines: lines)
        current = prepared
        return prepared
    }
}




enum LyricStyle {

    static let settle = Animation.timingCurve(0.41, 0, 0.12, 0.99, duration: 0.4)
    static let falloffAlpha: [Double] = [1, 0.8, 0.7, 0.58, 0.46]
    static let falloffAlphaPhone: [Double] = [1, 0.74, 0.6, 0.48, 0.4, 0.34]
    static let falloffBlur: [CGFloat] = [0, 1, 1, 1.7, 2.4]
    static let inactiveScale: CGFloat = 0.98
    static let browsingAlpha = 0.8
    static let unsungAlpha = 0.45
    static let backingAlpha = 0.72
    static let backingScale: CGFloat = 23.0 / 34.0
    static let wipeFeather: CGFloat = 30
    static let wordRise: CGFloat = 2
    static let glowAlpha = 0.62
    static let glowRadius: CGFloat = 6

    static let heldNoteMs: Int64 = 900
    static let duetLane: CGFloat = 44
    static let rowInset: CGFloat = 10
}

struct LyricRowLook: Equatable {
    var alpha: Double
    var blur: CGFloat
    var scale: CGFloat
}




struct LyricWordTiming: TextAttribute {
    let startMs: Int64
    let endMs: Int64
}







struct LyricSweepRenderer: TextRenderer {
    var ms: Int64
    var glow: Bool

    var displayPadding: EdgeInsets {
        let room = LyricStyle.glowRadius * 2
        return EdgeInsets(top: room, leading: room, bottom: room, trailing: room)
    }

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        for line in layout {
            for run in line {
                guard let timing = run[LyricWordTiming.self] else {

                    var dim = context
                    dim.opacity = LyricStyle.unsungAlpha
                    dim.draw(run)
                    continue
                }
                draw(run, timing: timing, in: context)
            }
        }
    }

    private func draw(_ run: Text.Layout.Run, timing: LyricWordTiming, in base: GraphicsContext) {
        let span = max(1, timing.endMs - timing.startMs)
        let progress = min(1, max(0, Double(ms - timing.startMs) / Double(span)))
        var context = base
        if progress > 0 {


            let rise = 1 - pow(1 - min(1, progress * 3), 3)
            context.translateBy(x: 0, y: -LyricStyle.wordRise * rise)
        }
        if progress >= 1 {
            context.draw(run)
            return
        }
        var dim = context
        dim.opacity = LyricStyle.unsungAlpha
        dim.draw(run)
        guard progress > 0 else { return }

        let rect = run.typographicBounds.rect
        if glow, span >= LyricStyle.heldNoteMs {

            let envelope = sin(Double.pi * progress)
            var halo = context
            halo.addFilter(.blur(radius: LyricStyle.glowRadius))
            halo.opacity = LyricStyle.glowAlpha * envelope
            halo.draw(run)
        }

        let feather = LyricStyle.wipeFeather
        let travel = rect.width + feather
        let rtl = run.layoutDirection == .rightToLeft
        let edge = rtl ? rect.maxX + feather - progress * travel : rect.minX - feather + progress * travel
        let band = rect.insetBy(dx: -feather, dy: -LyricStyle.glowRadius)
        var lit = context
        lit.clipToLayer { mask in
            mask.fill(
                Path(band),
                with: .linearGradient(
                    Gradient(colors: [.white, .white.opacity(0)]),
                    startPoint: CGPoint(x: edge, y: band.midY),
                    endPoint: CGPoint(x: rtl ? edge - feather : edge + feather, y: band.midY)
                )
            )
        }
        lit.draw(run)
    }
}





struct LyricsFocusTicker: View {
    let document: PreparedLyrics
    let clock: LyricsClock
    let offsetMs: Int32
    let onChange: (LyricFocusState) -> Void

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !clock.playing)) { timeline in
            let focus = document.timeline.focus(
                at: clock.lyricMs(at: timeline.date, offsetMs: offsetMs),
                document: ObjectIdentifier(document)
            )
            Color.clear
                .onChange(of: focus, initial: true) { _, focus in onChange(focus) }
        }
        .frame(width: 0, height: 0)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}



enum LyricTapMode: Equatable {
    case reveal, pick, seek
}

struct LyricRowView: View, Equatable {
    let row: LyricRow
    let document: ObjectIdentifier
    let active: Bool
    let look: LyricRowLook
    let fontSize: CGFloat
    let duet: Bool
    let picked: Bool
    let picking: Bool
    let tapMode: LyricTapMode
    let offsetMs: Int32
    let clock: LyricsClock
    let onTap: () -> Void

    static func == (lhs: LyricRowView, rhs: LyricRowView) -> Bool {
        lhs.row.id == rhs.row.id && lhs.document == rhs.document && lhs.active == rhs.active &&
            lhs.look == rhs.look && lhs.fontSize == rhs.fontSize && lhs.duet == rhs.duet &&
            lhs.picked == rhs.picked && lhs.picking == rhs.picking && lhs.tapMode == rhs.tapMode &&
            lhs.offsetMs == rhs.offsetMs && (!lhs.active || lhs.clock.playing == rhs.clock.playing)
    }

    private var trailingSide: Bool { row.secondVoice != row.rightToLeft }

    var body: some View {
        VStack(alignment: row.secondVoice ? .trailing : .leading, spacing: 2) {
            line(row.leadText, swept: row.leadSwept, size: fontSize, glow: true)
            if let backing = row.backingText {
                line(backing, swept: row.backingSwept, size: fontSize * LyricStyle.backingScale, glow: false)
                    .opacity(LyricStyle.backingAlpha)
            }
        }
        .frame(maxWidth: .infinity, alignment: row.secondVoice ? .trailing : .leading)
        .padding(.leading, duet && row.secondVoice ? LyricStyle.duetLane : 0)
        .padding(.trailing, duet && !row.secondVoice ? LyricStyle.duetLane : 0)
        .padding(.vertical, LyricStyle.rowInset * 0.8)
        .padding(.horizontal, LyricStyle.rowInset)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(.white.opacity(picked ? 0.16 : (picking ? 0.05 : 0)))
        )
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .onTapGesture(perform: onTap)
        .environment(\.layoutDirection, row.rightToLeft ? .rightToLeft : .leftToRight)
        .scaleEffect(look.scale, anchor: trailingSide ? .trailing : .leading)
        .opacity(look.alpha)
        .blur(radius: look.blur)
        .animation(LyricStyle.settle, value: look)
    }

    @ViewBuilder
    private func line(_ plain: String, swept: Text?, size: CGFloat, glow: Bool) -> some View {
        Group {
            if active, let swept {
                TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !clock.playing)) { timeline in
                    swept.textRenderer(LyricSweepRenderer(
                        ms: clock.lyricMs(at: timeline.date, offsetMs: offsetMs),
                        glow: glow
                    ))
                }
            } else {
                Text(verbatim: plain)
            }
        }
        .scaledFont(size: size, weight: .heavy, design: .default)
        .foregroundStyle(.white)
        .multilineTextAlignment(row.secondVoice ? .trailing : .leading)
        .lineSpacing(size * 0.06)
        .fixedSize(horizontal: false, vertical: true)
    }
}




struct LyricGapRow: View {
    let row: LyricRow
    let open: Bool
    let look: LyricRowLook
    let offsetMs: Int32
    let clock: LyricsClock
    let onTap: () -> Void

    private let dot: CGFloat = 13
    private let spacing: CGFloat = 5

    var body: some View {
        ZStack(alignment: row.secondVoice ? .trailing : .leading) {
            if open {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !clock.playing)) { timeline in
                    dots(through: through(at: timeline.date))
                }
                .transition(.opacity.combined(with: .scale(scale: 0.76, anchor: .leading)))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: open ? dot + LyricStyle.rowInset * 2 + 16 : 0)
        .padding(.horizontal, LyricStyle.rowInset)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .opacity(look.alpha)
        .blur(radius: look.blur)
        .animation(LyricStyle.settle, value: open)
        .accessibilityLabel("Instrumental")
    }

    private func through(at date: Date) -> Double {
        let now = clock.lyricMs(at: date, offsetMs: offsetMs)
        let span = max(1, row.untilMs - row.timeMs)
        return min(1, max(0, Double(now - row.timeMs) / Double(span)))
    }

    private func dots(through: Double) -> some View {
        HStack(spacing: spacing) {
            ForEach(0..<3, id: \.self) { index in
                let lit = min(1, max(0, through * 3 - Double(index)))
                Circle()
                    .fill(.white.opacity(0.25 + 0.75 * lit))
                    .frame(width: dot, height: dot)
            }
        }
    }
}
