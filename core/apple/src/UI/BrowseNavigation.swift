import SwiftUI








struct PushRoute: Hashable {
    let id: UUID
    let make: () -> AnyView

    static func == (lhs: PushRoute, rhs: PushRoute) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

extension EnvironmentValues {


    @Entry var inBrowseStack = false
}


struct PageStack<Content: View>: View {
    @Environment(\.inBrowseStack) private var inBrowseStack
    @ViewBuilder var content: Content

    var body: some View {
        if inBrowseStack {
            content
        } else {
            NavigationStack {
                content
                    .navigationDestination(for: PushRoute.self) { $0.make() }
            }
        }
    }
}




struct PushLink<Label: View, Destination: View>: View {
    @Environment(\.inBrowseStack) private var inBrowseStack



    @State private var id = UUID()
    private let destination: () -> Destination
    private let label: Label

    init(destination: Destination, @ViewBuilder label: () -> Label) {
        self.destination = { destination }
        self.label = label()
    }

    init(@ViewBuilder destination: @escaping () -> Destination, @ViewBuilder label: () -> Label) {
        self.destination = destination
        self.label = label()
    }

    var body: some View {
        if inBrowseStack {
            let make = destination
            NavigationLink(value: PushRoute(id: id, make: { AnyView(make()) })) { label }
        } else {
            NavigationLink(destination: destination) { label }
        }
    }
}

extension PushLink where Label == Text {
    init(_ title: LocalizedStringKey, @ViewBuilder destination: @escaping () -> Destination) {
        self.init(destination: destination) { Text(title) }
    }
}


@MainActor
protocol HistoryNavigating: AnyObject, Observable {
    var canGoBack: Bool { get }
    var canGoForward: Bool { get }
    func goBack()
    func goForward()
}



@MainActor
@Observable
final class PathNavigator: HistoryNavigating {
    private(set) var history: [[PushRoute]] = [[]]
    private(set) var index = 0

    var canGoBack: Bool { index > 0 }
    var canGoForward: Bool { index < history.count - 1 }

    var path: [PushRoute] {
        get { history[index] }
        set {
            guard newValue != history[index] else { return }
            history.removeSubrange((index + 1)...)
            history.append(newValue)
            index = history.count - 1
        }
    }

    func goBack() { if canGoBack { index -= 1 } }
    func goForward() { if canGoForward { index += 1 } }
}

#if os(macOS)





@MainActor
@Observable
final class MacNavigator: HistoryNavigating {
    struct Location: Equatable {
        var side: MacSide
        var path: [PushRoute]


        var showedPlayer = false
    }


    var onReturnToPlayer: (() -> Void)?


    func pushFromPlayer(_ route: PushRoute) {
        history[index].showedPlayer = true
        visit(Location(side: current.side, path: current.path + [route]))
    }

    private(set) var history: [Location]
    private(set) var index = 0
    private static let limit = 100

    init(side: MacSide = .home) {
        history = [Location(side: side, path: [])]
    }

    var current: Location { history[index] }
    var canGoBack: Bool { index > 0 }
    var canGoForward: Bool { index < history.count - 1 }

    var side: MacSide {
        get { current.side }
        set {

            visit(Location(side: newValue, path: []))
        }
    }

    var path: [PushRoute] {
        get { current.path }
        set { visit(Location(side: current.side, path: newValue)) }
    }



    func replaceSide(_ side: MacSide) {
        history[index] = Location(side: side, path: [])
    }

    func goBack() {
        guard canGoBack else { return }
        index -= 1
        if history[index].showedPlayer {



            history[index].showedPlayer = false
            onReturnToPlayer?()
        }
    }

    func goForward() {
        guard canGoForward else { return }
        index += 1
    }

    private func visit(_ location: Location) {
        guard location != current else { return }
        history.removeSubrange((index + 1)...)
        history.append(location)
        if history.count > Self.limit {
            history.removeFirst(history.count - Self.limit)
        }
        index = history.count - 1
    }
}


struct MacHistoryButtons<Navigator: HistoryNavigating>: View {
    let navigator: Navigator

    var body: some View {
        ControlGroup {
            Button {
                navigator.goBack()
            } label: {
                Image(systemName: "chevron.left")
            }
            .disabled(!navigator.canGoBack)
            .help("Back")
            .accessibilityLabel("Back")
            .keyboardShortcut("[", modifiers: .command)

            Button {
                navigator.goForward()
            } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(!navigator.canGoForward)
            .help("Forward")
            .accessibilityLabel("Forward")
            .keyboardShortcut("]", modifiers: .command)
        }
        .controlGroupStyle(.navigation)
    }
}
#endif
