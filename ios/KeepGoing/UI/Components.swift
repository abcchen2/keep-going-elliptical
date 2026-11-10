import SwiftUI
import UIKit

enum Theme {
    /// Same green as the Android theme (0xFF2E7D32).
    static let primary = Color(red: 0x2E / 255.0, green: 0x7D / 255.0, blue: 0x32 / 255.0)
    static let primaryContainer = Color(red: 0xC8 / 255.0, green: 0xE6 / 255.0, blue: 0xC9 / 255.0)
    static let warning = Color(red: 0xF9 / 255.0, green: 0xA8 / 255.0, blue: 0x25 / 255.0)
}

/// Scrollable page with the grouped background, used by every tab.
struct TabPage<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                content()
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
    }
}

struct SectionCard<Content: View>: View {
    let title: String?
    @ViewBuilder let content: () -> Content

    init(_ title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title).font(.headline)
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct PhaseDot: View {
    let phase: ConnectionPhase

    var body: some View {
        Circle().fill(color).frame(width: 12, height: 12)
    }

    private var color: Color {
        switch phase {
        case .ready: return Theme.primary
        case .idle: return .gray
        default: return Theme.warning
        }
    }
}

struct Metric: View {
    let label: String
    let value: String?

    var body: some View {
        VStack(spacing: 2) {
            Text(value ?? "—").font(.system(size: 22, weight: .semibold)).monospacedDigit()
            Text(label).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

struct Hint: View {
    let text: String
    var color: Color = .secondary

    var body: some View {
        Text(text).font(.caption).foregroundColor(color).fixedSize(horizontal: false, vertical: true)
    }
}

enum Format {
    static func duration(_ sec: Int) -> String {
        if sec >= 3600 {
            return String(format: "%ld:%02ld:%02ld", sec / 3600, sec % 3600 / 60, sec % 60)
        }
        return String(format: "%02ld:%02ld", sec / 60, sec % 60)
    }

    static func distance(_ m: Double) -> String {
        m >= 1000 ? L("unit_km", m / 1000) : L("unit_m", m)
    }

    static func one(_ v: Double) -> String { String(format: "%.1f", v) }

    static func zero(_ v: Double) -> String { String(format: "%.0f", v) }

    static let historyDate: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}
