import Foundation

/// A string key plus arguments, resolved only when displayed, so text kept in MachineState
/// follows a language change. Arguments may themselves be UiText. Port of model/UiText.kt.
struct UiText {
    let key: String
    let args: [Any]

    init(_ key: String, _ args: Any...) {
        self.key = key
        self.args = args
    }

    func resolve() -> String {
        let resolved: [Any] = args.map { arg -> Any in
            if let nested = arg as? UiText { return nested.resolve() }
            return arg
        }
        return Lf(key, resolved)
    }
}
