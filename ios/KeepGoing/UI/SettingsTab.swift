import SwiftUI
import UIKit

/// Settings tab. In v1 it shows device details and the log; the settings themselves come later
/// (spec ios-app, "Out of scope"). Until then the app uses the Android defaults.
struct SettingsTab: View {
    @EnvironmentObject private var controller: EllipticalController

    var body: some View {
        let state = controller.state
        let inSession = state.phase != .idle && state.phase != .scanning
        TabPage {
            Header()
            if inSession {
                StatusCard(state: state, onDisconnect: { controller.disconnect() })
            }
            LogCard()
        }
    }
}

struct LogCard: View {
    @EnvironmentObject private var controller: EllipticalController

    var body: some View {
        SectionCard(L("card_debug")) {
            HStack(spacing: 16) {
                Button(L("action_copy_log")) {
                    UIPasteboard.general.string = controller.logs.joined(separator: "\n")
                }
                Button(L("action_clear")) { controller.clearLogs() }
            }
            let lines = Array(controller.logs.suffix(150).reversed())
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
