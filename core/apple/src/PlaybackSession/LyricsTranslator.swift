import Foundation
import NuvxShared








@MainActor
@Observable
final class LyricsTranslator {
    enum Mode: String, CaseIterable, Identifiable {
        case translate
        case romanize
        var id: String { rawValue }

        var title: String {
            switch self {
            case .translate: return "Translate"
            case .romanize: return "Romanise"
            }
        }

        var subtitle: String {
            switch self {
            case .translate: return "Show the words in another language"
            case .romanize: return "Show them in Latin script, as a pronunciation"
            }
        }

        var symbol: String {
            switch self {
            case .translate: return "character.bubble"
            case .romanize: return "textformat.abc"
            }
        }
    }

    struct Language: Identifiable, Hashable, Decodable {
        var code: String
        var name: String
        var romanizable: Bool
        var id: String { code }
    }


    enum Outcome: Equatable {
        case idle
        case working

        case done(language: String, fromCache: Bool)

        case alreadyInLanguage(language: String)




        case unavailable
    }

    private(set) var outcome: Outcome = .idle
    private(set) var translatedLines: [LyricLineDto]?











    private var originalSides: [Bool] = []

    private var translateLanguages: [Language] = []
    private var romanizeLanguages: [Language] = []
    private var loaded = false


    func languages(for mode: Mode) -> [Language] {
        let list = mode == .translate ? translateLanguages : romanizeLanguages
        return list.map { language in
            Language(
                code: language.code,


                name: Locale.current.localizedString(forIdentifier: language.code)
                    ?? language.name,
                romanizable: language.romanizable
            )
        }
    }







    func canRomanize(lines: [LyricLineDto]) -> Bool {
        lines.contains { line in
            line.text.unicodeScalars.contains { scalar in
                CharacterSet.letters.contains(scalar) && !isLatin(scalar)
            }
        }
    }


    func run(
        mode: Mode,
        target: Language,
        trackId: String,
        lines: [LyricLineDto],
        then: @escaping ([LyricLineDto]?) -> Void,
    ) {
        originalSides = lines.map { $0.alignment == .end }
        guard !lines.isEmpty else {
            outcome = .unavailable
            translatedLines = nil
            then(nil)
            return
        }
        guard let document = encode(lines) else {
            outcome = .unavailable
            then(nil)
            return
        }
        outcome = .working
        let settle: @Sendable (String, String, String, Bool) -> Void = { status, body, language, cached in
            Task { @MainActor in
                switch status {
                case "translated":
                    let decoded = self.decode(body)
                    self.outcome = .done(language: language, fromCache: cached)
                    then(decoded)
                case "same":

                    self.translatedLines = nil
                    self.outcome = language.isEmpty
                        ? .alreadyInLanguage(language: "")
                        : .alreadyInLanguage(language: language)
                    then(nil)
                default:
                    self.translatedLines = nil
                    self.outcome = .unavailable
                    then(nil)
                }
            }
        }
        let callback = TranslationAdapter(settle)
        if mode == .translate {
            LyricsBridge.shared.translate(
                trackId: trackId,
                linesJson: document,
                targetLanguageTag: target.code,
                callback: callback
            )
        } else {
            LyricsBridge.shared.romanize(
                trackId: trackId,
                linesJson: document,
                targetLanguageTag: target.code,
                callback: callback
            )
        }
    }


    func reset() {
        translatedLines = nil
        outcome = .idle
    }



    func loadLanguages() {
        guard !loaded else { return }
        loaded = true
        guard let data = LyricsBridge.shared.languagesJson().data(using: .utf8),
              let listing = try? JSONDecoder().decode(LanguageListing.self, from: data)
        else { return }
        translateLanguages = listing.translate
        romanizeLanguages = listing.romanize
    }

    private struct LanguageListing: Decodable {
        let translate: [Language]
        let romanize: [Language]
    }

    private func encode(_ lines: [LyricLineDto]) -> String? {
        let document = LineList(lines: lines.map { Line(from: $0) })
        guard let data = try? JSONEncoder().encode(document) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func decode(_ document: String) -> [LyricLineDto]? {
        guard let data = document.data(using: .utf8),
              let list = try? JSONDecoder().decode(LineList.self, from: data)
        else { return nil }
        return list.lines.map { $0.toModel() }
    }












    private struct LineList: Codable {
        let lines: [Line]
    }

    private final class Line: Codable {
        var timeMs: Int64
        var text: String
        var words: [Word]
        var sungUntilMs: Int64?
        var background: Line?

        struct Word: Codable {
            var startMs: Int64
            var endMs: Int64
            var text: String
        }







        init(from line: LyricLineDto) {
            timeMs = line.timeMs
            text = line.text
            words = line.words.map {
                Word(startMs: $0.startMs, endMs: $0.endMs, text: $0.text)
            }
            sungUntilMs = line.sungUntilMs?.int64Value
            background = line.background.map { Line(from: $0) }
        }

        func toModel() -> LyricLineDto {
            LyricLineDto(
                timeMs: timeMs,
                text: text,
                words: words.map {
                    LyricWordDto(startMs: $0.startMs, endMs: $0.endMs, text: $0.text)
                },
                sungUntilMs: sungUntilMs.map { KotlinLong(value: $0) },
                background: background?.toModel(),




                alignment: LyricAlignment.start
            )
        }
    }


    private func isLatin(_ scalar: Unicode.Scalar) -> Bool {
        (0x0041...0x007A).contains(scalar.value)
            || (0x00C0...0x00FF).contains(scalar.value)
            || (0x0100...0x024F).contains(scalar.value)
            || (0x1E00...0x1EFF).contains(scalar.value)
    }
}

private final class TranslationAdapter: TranslationCallback {
    private let handler: @Sendable (String, String, String, Bool) -> Void
    init(_ handler: @escaping @Sendable (String, String, String, Bool) -> Void) {
        self.handler = handler
    }
    func onResult(
        status: String,
        document: String,
        sourceLanguage: String,
        fromCache: Bool
    ) {
        handler(status, document, sourceLanguage, fromCache)
    }
}
