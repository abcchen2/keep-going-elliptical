import Foundation

/// One workout session: time, distance, calories, cadence and heart-rate averages.
/// Port of workout/WorkoutTracker.kt; rules in .kiro/steering/workout-metrics.md:
///  - Accumulation happens only in the 1 Hz tick, driven by real (monotonic) elapsed time,
///    at most 2 s per tick; never inside BLE callbacks.
///  - Cadence older than 5 s counts as 0.
///  - Machine totals are counters: only their increase is added (DeviceCounter).
///  - Time only counts while RUNNING; manual pause, auto pause and disconnects stop it.
///  - The formula-based burn rate is capped at CalorieModel.maxKcalPerMin.
final class WorkoutTracker: ObservableObject {
    @Published private(set) var stats = WorkoutStats()
    @Published private(set) var history: [WorkoutRecord] = []

    private let settings: AppSettings
    private let machine: () -> MachineState
    private let sendToMachine: (WorkoutCommand) -> Void
    private let log: (String) -> Void

    private var lastTick: Int64 = 0
    private var running = false

    private var activeMs: Int64 = 0
    private var idleMs: Int64 = 0
    private var distanceM = 0.0
    private var calories = 0.0
    private var distanceSource: DataSource = .none
    private var calorieSource: DataSource = .none
    private var rpmWeighted = 0.0
    private var rpmMs: Int64 = 0
    private var resWeighted = 0.0
    private var hrWeighted = 0.0
    private var hrMs: Int64 = 0
    private var hrMax = 0
    private var kcalPerMin = 0.0
    private var machineDistanceSeen = false
    private var machineCaloriesSeen = false

    private let distanceCounter = DeviceCounter(maxStepPerSecond: 15.0)
    private let calorieCounter = DeviceCounter(maxStepPerSecond: 0.5)

    private static let tickMs: Int64 = 1000
    private static let maxDtMs: Int64 = 2000
    private static let staleMs: Int64 = 5000
    private static let minRecordSec = 30
    private static let maxHistory = 30

    init(settings: AppSettings,
         machine: @escaping () -> MachineState,
         sendToMachine: @escaping (WorkoutCommand) -> Void,
         log: @escaping (String) -> Void) {
        self.settings = settings
        self.machine = machine
        self.sendToMachine = sendToMachine
        self.log = log
        history = loadHistory()
    }

    func begin() {
        if running { return }
        running = true
        lastTick = monotonicMs()
        scheduleTick()
    }

    private func scheduleTick() {
        // Main-queue blocks also run while the user scrolls or drags the slider.
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(Self.tickMs) / 1000.0) { [weak self] in
            guard let self else { return }
            self.tick()
            self.scheduleTick()
        }
    }

    private func logRes(_ key: String, _ args: Any...) { log(Lf(key, args)) }

    // ------------------------------------------------------------------ user actions

    func start() {
        let st = stats.status
        if st == .paused || st == .autoPaused { resume(); return }
        if st == .running { return }
        resetAccumulators()
        stats = WorkoutStats(status: .running, startedAt: wallClockMs())
        logRes("log_workout_started")
        sendToMachine(.start)
    }

    func pause() {
        let st = stats.status
        guard st == .running || st == .autoPaused else { return }
        setStatus(.paused)
        logRes("log_workout_paused")
        sendToMachine(.pause)
    }

    func resume() {
        let st = stats.status
        guard st == .paused || st == .autoPaused else { return }
        idleMs = 0
        setStatus(.running)
        logRes("log_workout_resumed")
        sendToMachine(.start)
    }

    func finish() {
        let st = stats.status
        if st == .idle || st == .finished { return }
        publish(.finished, machine())
        let s = stats
        logRes("log_workout_finished", s.activeSec, s.distanceM, s.calories)
        sendToMachine(.stop)
        if s.activeSec >= Self.minRecordSec { saveRecord(s) }
    }

    /// From FINISHED back to IDLE (pedalling will then start a new workout automatically).
    func reset() {
        guard stats.status == .finished else { return }
        resetAccumulators()
        stats = WorkoutStats()
    }

    func clearHistory() {
        history = []
        settings.workoutHistoryJson = nil
    }

    // ------------------------------------------------------------------ 1 Hz loop

    private func tick() {
        let now = monotonicMs()
        // If the app was suspended for a while, do not extrapolate the gap.
        let dt = min(max(now - lastTick, 0), Self.maxDtMs)
        lastTick = now

        let m = machine()
        let connected = m.phase == .ready
        let rpmFresh = connected && (m.rpm ?? 0) > 0 && now - m.rpmUpdatedAt <= Self.staleMs
        let rpm = rpmFresh ? (m.rpm ?? 0) : 0
        let status = stats.status

        // Counters always track the latest machine value so a pause does not create a jump later.
        let counting = status == .running && connected
        let dDist = distanceCounter.step(m.deviceDistanceM, counting, dt)
        let dCal = calorieCounter.step(m.deviceCalories, counting, dt)

        switch status {
        case .idle:
            if rpmFresh {
                logRes("log_auto_start")
                start()
            }
        case .autoPaused:
            if rpmFresh {
                logRes("log_auto_resume")
                idleMs = 0
                setStatus(.running)
            }
        case .running:
            if !connected {
                logRes("log_disconnect_pause")
                publish(.autoPaused, m)
                return
            }
            accumulate(m, dt, rpm, rpmFresh, dDist, dCal, now)
            idleMs = rpmFresh ? 0 : idleMs + dt
            let limit = settings.autoPauseSec
            if limit > 0 && idleMs >= Int64(limit) * 1000 {
                logRes("log_idle_pause", limit)
                publish(.autoPaused, m)
                return
            }
        case .paused, .finished:
            break
        }
        if stats.status == status { publish(status, m) }
    }

    private func accumulate(_ m: MachineState, _ dt: Int64, _ rpm: Double, _ rpmFresh: Bool,
                            _ dDist: Double, _ dCal: Double, _ now: Int64) {
        activeMs += dt
        let sec = Double(dt) / 1000.0
        let resistance = m.reportedResistance ?? m.targetResistance

        // Use a machine counter only once it has actually moved in this session: some boxes
        // send the field but leave it at 0, which would otherwise freeze the total.
        if dDist > 0 { machineDistanceSeen = true }
        if dCal > 0 { machineCaloriesSeen = true }

        if m.deviceDistanceM != nil && machineDistanceSeen {
            distanceM += dDist
            distanceSource = .machine
        } else {
            distanceM += CalorieModel.speedKmh(rpm, m.machineType) / 3.6 * sec
            distanceSource = .formula
        }

        if m.deviceCalories != nil && machineCaloriesSeen {
            calories += dCal
            calorieSource = .machine
            // Machine counters arrive in steps; smooth the displayed rate.
            let instant = sec > 0 ? dCal / sec * 60 : 0
            kcalPerMin = kcalPerMin * 0.8 + instant * 0.2
        } else {
            let rate = CalorieModel.kcalPerSecond(
                algorithm: settings.calorieAlgorithm, rpm: rpm, resistance: resistance,
                resistanceMax: m.resistanceMax, weightKg: settings.userWeightKg,
                machineType: m.machineType, subType: m.subType
            ) * Double(settings.calorieFactorPercent) / 100.0
            calories += rate * sec
            calorieSource = .formula
            kcalPerMin = rate * 60
        }

        if rpmFresh {
            rpmWeighted += rpm * Double(dt)
            resWeighted += Double(resistance) * Double(dt)
            rpmMs += dt
        }
        if let hr = m.heartRate, hr > 0, now - m.heartRateUpdatedAt <= Self.staleMs {
            hrWeighted += Double(hr) * Double(dt)
            hrMs += dt
            if hr > hrMax { hrMax = hr }
        }
    }

    private func publish(_ status: WorkoutStatus, _ m: MachineState) {
        let now = monotonicMs()
        let rpmFresh = (m.rpm ?? 0) > 0 && now - m.rpmUpdatedAt <= Self.staleMs
        let rpm = rpmFresh ? (m.rpm ?? 0) : 0
        let speed: Double
        if status == .running {
            if rpmFresh, let device = m.deviceSpeedKmh {
                speed = device
            } else {
                speed = CalorieModel.speedKmh(rpm, m.machineType)
            }
        } else {
            speed = 0
        }
        var s = stats
        s.status = status
        s.activeSec = Int(activeMs / 1000)
        s.distanceM = distanceM
        s.calories = calories
        s.distanceSource = distanceSource
        s.calorieSource = calorieSource
        s.speedKmh = speed
        s.avgRpm = rpmMs > 0 ? rpmWeighted / Double(rpmMs) : 0
        s.avgResistance = rpmMs > 0 ? resWeighted / Double(rpmMs) : 0
        s.avgHeartRate = hrMs > 0 ? Int(hrWeighted / Double(hrMs)) : nil
        s.maxHeartRate = hrMax > 0 ? hrMax : nil
        s.kcalPerMin = status == .running ? kcalPerMin : 0
        stats = s
    }

    private func setStatus(_ status: WorkoutStatus) { publish(status, machine()) }

    private func resetAccumulators() {
        activeMs = 0
        idleMs = 0
        distanceM = 0
        calories = 0
        distanceSource = .none
        calorieSource = .none
        rpmWeighted = 0
        rpmMs = 0
        resWeighted = 0
        hrWeighted = 0
        hrMs = 0
        hrMax = 0
        kcalPerMin = 0
        machineDistanceSeen = false
        machineCaloriesSeen = false
    }

    // ------------------------------------------------------------------ history

    private func saveRecord(_ s: WorkoutStats) {
        let record = WorkoutRecord(
            startedAt: s.startedAt, activeSec: s.activeSec, distanceM: s.distanceM, calories: s.calories,
            avgRpm: s.avgRpm, avgResistance: s.avgResistance, avgHeartRate: s.avgHeartRate, maxHeartRate: s.maxHeartRate
        )
        let list = Array(([record] + history).prefix(Self.maxHistory))
        history = list
        // Same JSON shape as the Android app.
        let arr: [[String: Any]] = list.map { r in
            [
                "t": r.startedAt,
                "sec": r.activeSec,
                "m": r.distanceM,
                "kcal": r.calories,
                "rpm": r.avgRpm,
                "res": r.avgResistance,
                "hr": r.avgHeartRate ?? 0,
                "hrMax": r.maxHeartRate ?? 0,
            ]
        }
        if let data = try? JSONSerialization.data(withJSONObject: arr) {
            settings.workoutHistoryJson = String(data: data, encoding: .utf8)
        }
    }

    private func loadHistory() -> [WorkoutRecord] {
        guard let json = settings.workoutHistoryJson, let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        func num(_ o: [String: Any], _ key: String) -> Double? { (o[key] as? NSNumber)?.doubleValue }
        var out: [WorkoutRecord] = []
        for o in arr {
            guard let t = num(o, "t"), let sec = num(o, "sec"), let m = num(o, "m"), let kcal = num(o, "kcal") else {
                return []
            }
            let hr = Int(num(o, "hr") ?? 0)
            let hrMaxValue = Int(num(o, "hrMax") ?? 0)
            out.append(WorkoutRecord(
                startedAt: Int64(t), activeSec: Int(sec), distanceM: m, calories: kcal,
                avgRpm: num(o, "rpm") ?? 0, avgResistance: num(o, "res") ?? 0,
                avgHeartRate: hr > 0 ? hr : nil, maxHeartRate: hrMaxValue > 0 ? hrMaxValue : nil
            ))
        }
        return out
    }
}

/// Converts a machine's running total into per-tick increments.
/// Handles counters that start at an arbitrary value, reset to 0, or glitch.
final class DeviceCounter {
    private let maxStepPerSecond: Double
    private var last: Double?
    private var rejected = 0

    init(maxStepPerSecond: Double) {
        self.maxStepPerSecond = maxStepPerSecond
    }

    func step(_ value: Double?, _ counting: Bool, _ dtMs: Int64) -> Double {
        guard let value else { return 0 }
        guard let prev = last, counting else {
            last = value
            rejected = 0
            return 0
        }
        let delta = value >= prev ? value - prev : value // counter reset: count from 0
        // Allow a few seconds of backlog (notifications can be bursty), reject absurd jumps.
        let limit = maxStepPerSecond * max(Double(dtMs) / 1000.0, 1.0) * 3
        if delta <= limit {
            last = value
            rejected = 0
            return delta
        }
        // A single garbage frame is skipped (keep the old baseline). If the jump persists,
        // the counter really moved (e.g. after a reconnect): adopt it without counting it.
        rejected += 1
        if rejected >= 3 {
            last = value
            rejected = 0
        }
        return 0
    }
}
