#if os(macOS)
import AppKit



enum MacKeyboardInput {
    @MainActor static var isEditingText: Bool {
        let responder = NSApp.keyWindow?.firstResponder
        if let editor = responder as? NSTextView { return editor.isEditable }
        if let field = responder as? NSTextField { return field.isEditable }
        return false
    }
}
#endif
