import SwiftUI
import UIKit

/// Workout tab. Same layout as the Android Workout tab: header, status, connect card and
/// device list while idle; compact session bar, workout card, demo card and resistance card
/// while connected.
struct WorkoutTab: View {
    @EnvironmentObject private var controller: EllipticalController
    @EnvironmentObject private var workout: WorkoutTracker

    var body: some View {
        let state = controller.state
        let inSession = state.phase != .idle && state.phase != .scanning
        TabPage {
            if inSession {
                SessionBar(state: state, onDisconnect: { controller.disconnect() })
            } else {
                Header()
                StatusCard(state: state, onDisconnect: { controller.disconnect() })
            }
            if inSession || workout.stats.status != .idle {
                WorkoutCard(stats: workout.stats, state: state, tracker: workout)
                if state.protocolKind == .demo && inSession {
                    DemoCard()
                }
            }
            if inSession {
                ResistanceCard(state: state, onSet: { controller.setResistance($0) })
            } else {
                ConnectCard()
                ForEach(controller.devices) { d in
                    DeviceRow(device: d, highlight: controller.likelyMachine(d)) {
                        controller.connect(address: d.address, name: d.name)
                    }
                }
            }
        }
    }
}

struct Header: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L("app_name")).font(.system(size: 26, weight: .bold)).foregroundColor(Theme.primary)
            Text(L("app_subtitle")).font(.caption).foregroundColor(.secondary)
        }
    }
}

struct StatusCard: View {
    let state: MachineState
    let onDisconnect: () -> Void

    var body: some View {
        SectionCard(L("card_status")) {
            HStack {
                PhaseDot(phase: state.phase)
                Text(L(state.phase.labelKey)).fontWeight(.medium)
                Spacer()
                if state.phase != .idle && state.phase != .scanning {
                    Button(L("action_disconnect"), action: onDisconnect)
                }
            }
            if let name = state.deviceName {
                Text(L("label_device", name, state.deviceAddress ?? "")).lineLimit(2).truncationMode(.middle)
            }
            if let kind = state.protocolKind {
                Text(L("label_protocol", L(kind.labelKey)))
            }
            if let desc = state.machineDescription {
                Text(L("label_machine", desc.resolve()))
            }
            let extra = extraLine
            if !extra.isEmpty {
                Text(extra).font(.caption)
            }
            if let err = state.lastError {
                Text(L(err)).foregroundColor(.red)
            }
        }
    }

    private var extraLine: String {
        var parts: [String] = []
        if let model = state.modelNumber { parts.append(L("label_model", model)) }
        if let fw = state.firmware { parts.append(L("label_firmware", fw)) }
        if let battery = state.battery, battery > 0 { parts.append(L("label_battery", battery)) }
        return parts.joined(separator: " · ")
    }
}

/// One-line connection status while connected, so metrics and resistance fit on screen.
struct SessionBar: View {
    let state: MachineState
    let onDisconnect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                PhaseDot(phase: state.phase)
                Text(L(state.phase.labelKey)).fontWeight(.medium)
                if let name = state.deviceName {
                    Text("· \(name)").font(.caption).foregroundColor(.secondary).lineLimit(1)
                }
                Spacer()
                Button(L("action_disconnect"), action: onDisconnect)
            }
            if let err = state.lastError {
                Hint(text: L(err), color: .red)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct ConnectCard: View {
    @EnvironmentObject private var controller: EllipticalController

    var body: some View {
        let scanning = controller.state.phase == .scanning
        SectionCard(L("card_connect")) {
            Hint(text: L("hint_connect"))
            HStack {
                if scanning {
                    Button(L("action_stop_scan")) { controller.stopScan() }
                        .buttonStyle(.bordered)
                } else {
                    Button(L("action_scan")) { controller.startScan() }
                        .buttonStyle(.borderedProminent)
                }
                if let last = controller.lastDeviceLabel {
                    Button { controller.connectLast() } label: {
                        Text(L("action_connect_last", last)).lineLimit(1)
                    }
                    .buttonStyle(.bordered)
                }
            }
            if let issue = controller.bluetoothIssue {
                Hint(text: L(issue), color: .red)
                Button(L("ios_open_settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(.bordered)
            }
            Divider()
            Hint(text: L("hint_no_machine"))
            Button(L("action_demo")) { controller.startDemo() }
                .buttonStyle(.bordered)
        }
    }
}

struct DeviceRow: View {
    let device: FoundDevice
    let highlight: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(device.name ?? L("label_no_name")).fontWeight(.medium).foregroundColor(.primary)
                    Text(subtitle).font(.caption).foregroundColor(.secondary).lineLimit(1).truncationMode(.middle)
                }
                Spacer()
                Text("\(device.rssi) dBm").font(.caption).foregroundColor(.secondary)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(highlight ? Theme.primaryContainer : Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private var subtitle: String {
        if device.serviceHints.isEmpty { return device.address }
        return device.address + "  " + L("label_services", device.serviceHints.joined(separator: ","))
    }
}

struct WorkoutCard: View {
    let stats: WorkoutStats
    let state: MachineState
    let tracker: WorkoutTracker

    var body: some View {
        let now = monotonicMs()
        let liveRpm = state.rpm.flatMap { $0 > 0 && now - state.rpmUpdatedAt <= 5000 ? $0 : nil }
        let liveHr = state.heartRate.flatMap { now - state.heartRateUpdatedAt <= 5000 ? $0 : nil }
        SectionCard(L("card_workout", L(stats.status.labelKey))) {
            Text(Format.duration(stats.activeSec))
                .font(.system(size: 44, weight: .bold))
                .monospacedDigit()
                .frame(maxWidth: .infinity)
            HStack {
                Metric(label: L("metric_distance"), value: Format.distance(stats.distanceM))
                Metric(label: L("metric_calories"), value: Format.one(stats.calories))
                Metric(label: L("metric_heart_rate"), value: liveHr.map { String($0) })
            }
            HStack {
                Metric(label: L("metric_cadence"), value: liveRpm.map { Format.zero($0) } ?? "0")
                Metric(label: L("metric_speed"), value: Format.one(stats.speedKmh))
                Metric(label: L("metric_burn_rate"), value: Format.one(stats.kcalPerMin))
            }
            Text(averages).font(.caption)
            Hint(text: L("label_sources", L(stats.distanceSource.labelKey), L(stats.calorieSource.labelKey)))
            HStack {
                buttons
            }
            if stats.status == .idle {
                Hint(text: L("hint_auto_start"))
            }
            if let run = state.machineRunState {
                Hint(text: L("label_machine_state", L(run)))
            }
        }
    }

    private var averages: String {
        var parts = [L("stat_avg_rpm", stats.avgRpm), L("stat_avg_resistance", stats.avgResistance)]
        if let hr = stats.avgHeartRate { parts.append(L("stat_avg_hr", hr)) }
        if let hr = stats.maxHeartRate { parts.append(L("stat_max_hr", hr)) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var buttons: some View {
        switch stats.status {
        case .idle:
            wide(L("action_start"), prominent: true) { tracker.start() }
        case .running, .autoPaused:
            wide(L("action_pause"), prominent: false) { tracker.pause() }
            wide(L("action_finish"), prominent: true) { tracker.finish() }
        case .paused:
            wide(L("action_resume"), prominent: true) { tracker.resume() }
            wide(L("action_finish"), prominent: false) { tracker.finish() }
        case .finished:
            wide(L("action_close"), prominent: false) { tracker.reset() }
            wide(L("action_new_workout"), prominent: true) { tracker.start() }
        }
    }

    @ViewBuilder
    private func wide(_ title: String, prominent: Bool, _ action: @escaping () -> Void) -> some View {
        if prominent {
            Button(action: action) { Text(title).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
        } else {
            Button(action: action) { Text(title).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
    }
}

struct DemoCard: View {
    @EnvironmentObject private var controller: EllipticalController

    var body: some View {
        SectionCard(L("card_demo")) {
            Hint(text: L("hint_demo"))
            Toggle(L("label_pedalling"), isOn: $controller.demoPedalling)
                .tint(Theme.primary)
        }
    }
}

struct ResistanceCard: View {
    let state: MachineState
    let onSet: (Int) -> Void

    /// Slider position while the user drags; nil otherwise (then the target level is shown).
    @State private var dragValue: Double? = nil

    var body: some View {
        let minLevel = state.resistanceMin
        let maxLevel = max(state.resistanceMax, minLevel + 1)
        let target = state.targetResistance
        let shown = Int((dragValue ?? Double(target)).rounded())
        SectionCard(L("card_resistance")) {
            HStack {
                stepButton("−", enabled: target > minLevel, filled: false) { onSet(target - 1) }
                VStack(spacing: 0) {
                    Text("\(shown)")
                        .font(.system(size: 64, weight: .bold))
                        .monospacedDigit()
                        .foregroundColor(Theme.primary)
                    Text(L("label_machine_feedback", state.reportedResistance.map { String($0) } ?? "—"))
                        .font(.caption)
                }
                .frame(maxWidth: .infinity)
                stepButton("+", enabled: target < maxLevel, filled: true) { onSet(target + 1) }
            }
            Slider(
                value: Binding(
                    get: { min(max(dragValue ?? Double(target), Double(minLevel)), Double(maxLevel)) },
                    set: { dragValue = $0 }
                ),
                in: Double(minLevel)...Double(maxLevel),
                step: 1,
                onEditingChanged: { editing in
                    if !editing, let v = dragValue {
                        onSet(Int(v.rounded()))
                        dragValue = nil
                    }
                }
            )
            .tint(Theme.primary)
            HStack(spacing: 6) {
                ForEach(presets(minLevel, maxLevel), id: \.self) { p in
                    Button { onSet(p) } label: { Text("\(p)").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                }
            }
            Hint(text: rangeText)
            if !state.controlReady {
                Hint(text: L("hint_setting_up"), color: Theme.warning)
            }
            if state.protocolKind == .huanTong {
                Hint(text: L("hint_huantong"))
            }
            if state.protocolKind == .v1 && state.reportedResistance == nil {
                Hint(text: L("hint_v1"))
            }
        }
    }

    private func presets(_ lo: Int, _ hi: Int) -> [Int] {
        var seen: [Int] = []
        for p in [lo, lo + (hi - lo) / 4, lo + (hi - lo) / 2, lo + (hi - lo) * 3 / 4, hi] where !seen.contains(p) {
            seen.append(p)
        }
        return seen
    }

    private var rangeText: String {
        let range = L("label_range", state.resistanceMin, state.resistanceMax, L(state.resistanceRangeSource))
        guard let mode = state.resistanceMode else { return range }
        return L("label_range_mode", range, L(ResistanceTables.modeText(mode)))
    }

    private func stepButton(_ title: String, enabled: Bool, filled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 40, weight: .medium))
                .frame(width: 84, height: 84)
                .foregroundColor(filled ? .white : Theme.primary)
                .background(filled ? Theme.primary : Theme.primaryContainer)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}
