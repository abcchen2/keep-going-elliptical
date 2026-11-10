import CoreBluetooth
import Foundation

/// Software stand-in for a Mobi elliptical (spec: .kiro/specs/demo-mode). Port of DemoMachine.kt.
///
/// Reports through the same MachineProtocol helpers as the real protocols, so the workout
/// tracker, calorie math and UI run their normal code paths. Never touches the GATT link and
/// never reports machine-side distance/calorie totals, which keeps the formula path in use.
final class DemoMachine: MachineProtocol {
    override var kind: ProtocolKind { .demo }

    var pedalling = true

    private var level: Int
    private var heartRate = 85.0
    private var t = 0
    private var tick: DispatchWorkItem?
    private var released = false

    override init(host: ProtocolHost) {
        level = min(max(host.state.targetResistance, 1), 24)
        super.init(host: host)
    }

    override func start() {
        applyRange(1, 24, "src_demo")
        reportMachineType(ResistanceTables.typeElliptical, 19)
        let current = level
        host.update {
            $0.machineDescription = UiText("demo_desc")
            $0.controlReady = true
            $0.reportedResistance = current
        }
        logRes("log_demo_started")
        schedule()
    }

    private func schedule() {
        if released { return }
        tick = host.postDelayed(1000) { [weak self] in
            guard let self, !self.released else { return }
            self.step()
            self.schedule()
        }
    }

    private func step() {
        t += 1
        let rpm: Double
        if pedalling {
            let base = min(max(68 - 0.9 * Double(level - 1), 40.0), 75.0)
            rpm = base + 6 * sin(Double(t) / 9.0)
        } else {
            rpm = 0
        }
        reportRpm(rpm)

        // Heart rate drifts toward an effort-dependent target, max 2 bpm per second.
        let target = pedalling ? min(max(85 + 2.4 * Double(level) + 0.5 * (rpm - 50), 80.0), 170.0) : 80.0
        heartRate += min(max(target - heartRate, -2.0), 2.0)
        reportHeartRate(Int(heartRate.rounded()))
    }

    override func setResistance(_ level: Int) {
        self.level = level
        // Like a 2.0 box echoing the active level on 0x8812.
        host.postDelayed(300) { [weak self] in
            guard let self, !self.released else { return }
            self.reportResistance(level)
        }
    }

    override func release() {
        released = true
        if let item = tick { host.cancel(item) }
        tick = nil
    }
}
