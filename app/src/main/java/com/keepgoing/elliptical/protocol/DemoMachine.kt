package com.keepgoing.elliptical.protocol

import com.keepgoing.elliptical.model.ProtocolKind
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Software stand-in for a Mobi elliptical (spec: .kiro/specs/demo-mode).
 *
 * It reports through the same MachineProtocol helpers as the real protocols, so the
 * workout tracker, calorie math and UI run their normal code paths. It never touches
 * the GATT link and never reports machine-side distance/calorie totals, which keeps the
 * formula-based path of the tracker in use.
 */
class DemoMachine(host: ProtocolHost) : MachineProtocol(host) {
    override val kind = ProtocolKind.DEMO

    var pedalling = true

    private var level = host.state.targetResistance.coerceIn(1, 24)
    private var heartRate = 85.0
    private var t = 0
    private var tick: Runnable? = null
    private var released = false

    override fun start() {
        applyRange(1, 24, "演示模式")
        reportMachineType(ResistanceTables.TYPE_ELLIPTICAL, 19)
        host.update { it.copy(machineDescription = "模拟椭圆机 (Demo)", controlReady = true, reportedResistance = level) }
        log("演示模式已启动：模拟踏频、心率和阻力回读，不使用蓝牙")
        schedule()
    }

    private fun schedule() {
        if (released) return
        tick = host.postDelayed(1000) {
            step()
            schedule()
        }
    }

    private fun step() {
        t++
        val rpm = if (pedalling) {
            val base = (68 - 0.9 * (level - 1)).coerceIn(40.0, 75.0)
            base + 6 * sin(t / 9.0)
        } else {
            0.0
        }
        reportRpm(rpm)

        // Heart rate drifts toward an effort-dependent target, max 2 bpm per second.
        val target = if (pedalling) (85 + 2.4 * level + 0.5 * (rpm - 50)).coerceIn(80.0, 170.0) else 80.0
        heartRate += (target - heartRate).coerceIn(-2.0, 2.0)
        reportHeartRate(heartRate.roundToInt())
    }

    override fun setResistance(level: Int) {
        this.level = level
        // Like a 2.0 box echoing the active level on 0x8812.
        host.postDelayed(300) { if (!released) reportResistance(level) }
    }

    override fun onNotify(ch: UUID, value: ByteArray) = Unit

    override fun release() {
        released = true
        tick?.let { host.cancel(it) }
        tick = null
    }
}
