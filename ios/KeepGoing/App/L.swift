import Foundation

/// Localised string for `key` (generated from the Android strings.xml, same keys).
/// Like Android `getString(id)` without arguments: the text is returned as is.
func L(_ key: String) -> String {
    Bundle.main.localizedString(forKey: key, value: key, table: nil)
}

/// Localised, formatted string. Like Android `getString(id, args...)`.
func L(_ key: String, _ args: Any...) -> String {
    Lf(key, args)
}

/// Array form of `L(key, args...)`, for forwarding variadic arguments.
func Lf(_ key: String, _ args: [Any]) -> String {
    let format = L(key)
    if args.isEmpty { return format }
    return SafeFormat.format(format, args)
}

/// Formats Foundation format strings with untyped arguments. Each argument is converted to
/// the type its specifier expects (`%@` -> String, `%ld`/`%lX` -> Int, `%f` -> Double), so a
/// mismatch can never crash the way a raw `String(format:)` call does.
enum SafeFormat {
    private enum Kind { case object, integer, double }

    static func format(_ format: String, _ args: [Any]) -> String {
        let kinds = specifierKinds(format)
        if kinds.isEmpty { return format }
        let count = (kinds.keys.max() ?? 0)
        var values: [CVarArg] = []
        for position in 1...count {
            let arg: Any? = position <= args.count ? args[position - 1] : nil
            switch kinds[position] ?? .object {
            case .object: values.append(describe(arg))
            case .integer: values.append(asInt(arg))
            case .double: values.append(asDouble(arg))
            }
        }
        return String(format: format, arguments: values)
    }

    /// Position (1-based) -> expected argument type, for positional and sequential specifiers.
    private static func specifierKinds(_ format: String) -> [Int: Kind] {
        var result: [Int: Kind] = [:]
        let chars = Array(format)
        var i = 0
        var sequential = 0
        while i < chars.count {
            guard chars[i] == "%" else { i += 1; continue }
            i += 1
            guard i < chars.count else { break }
            if chars[i] == "%" { i += 1; continue }
            // Optional "n$"
            var j = i
            var digits = ""
            while j < chars.count, chars[j].isASCII, chars[j].isNumber { digits.append(chars[j]); j += 1 }
            var position: Int
            if j < chars.count, chars[j] == "$", let n = Int(digits) {
                position = n
                i = j + 1
            } else {
                sequential += 1
                position = sequential
            }
            // Flags, width, precision, length modifiers.
            while i < chars.count, "-+ #0123456789.lhqLzjt".contains(chars[i]) { i += 1 }
            guard i < chars.count else { break }
            let conversion = chars[i]
            i += 1
            let kind: Kind
            switch conversion {
            case "d", "i", "u", "x", "X", "o", "c": kind = .integer
            case "f", "F", "e", "E", "g", "G", "a", "A": kind = .double
            default: kind = .object
            }
            result[position] = kind
        }
        return result
    }

    private static func describe(_ arg: Any?) -> String {
        switch arg {
        case nil: return ""
        case let text as UiText: return text.resolve()
        case let s as String: return s
        case let d as Double: return String(d)
        default: return String(describing: arg!)
        }
    }

    private static func asInt(_ arg: Any?) -> Int {
        switch arg {
        case let v as Int: return v
        case let v as Int64: return Int(v)
        case let v as Int32: return Int(v)
        case let v as UInt8: return Int(v)
        case let v as UInt16: return Int(v)
        case let v as UInt32: return Int(v)
        case let v as Double: return v.isFinite ? Int(v) : 0
        case let v as Bool: return v ? 1 : 0
        case let v as String: return Int(v) ?? 0
        default: return 0
        }
    }

    private static func asDouble(_ arg: Any?) -> Double {
        switch arg {
        case let v as Double: return v
        case let v as Float: return Double(v)
        case let v as Int: return Double(v)
        case let v as Int64: return Double(v)
        case let v as UInt8: return Double(v)
        case let v as String: return Double(v) ?? 0
        default: return 0
        }
    }
}
