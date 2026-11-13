import Foundation


struct TransitionPair: Hashable {
    let outgoing: String
    let incoming: String
}










struct TransitionPacing<Style> {
    struct Entry {
        let pair: TransitionPair
        let style: Style
    }


    private(set) var entries: [Entry] = []






    func allowsEffect(for pair: TransitionPair, isEffect: (Style) -> Bool) -> Bool {
        let last = entries.filter { $0.pair != pair }.suffix(3)
        return !(last.count == 3 && last.allSatisfy { isEffect($0.style) })
    }




    mutating func note(_ style: Style, for pair: TransitionPair) {
        entries.removeAll { $0.pair == pair }
        entries.append(Entry(pair: pair, style: style))
        if entries.count > 4 { entries.removeFirst(entries.count - 4) }
    }
}
