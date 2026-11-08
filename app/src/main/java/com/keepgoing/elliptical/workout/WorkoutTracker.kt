package com.keepgoing.elliptical.workout

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import com.keepgoing.elliptical.R
import com.keepgoing.elliptical.model.AppSettings
import com.keepgoing.elliptical.model.ConnectionPhase
import com.keepgoing.elliptical.model.DataSource
import com.keepgoing.elliptical.model.MachineState
import com.keepgoing.elliptical.model.WorkoutCommand
import com.keepgoing.elliptical.model.WorkoutRecord
import com.keepgoing.elliptical.model.WorkoutStats
import com.keepgoing.elliptical.model.WorkoutStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One workout session: time, distance, calories, cadence and heart-rate averages.
 *
 * Rules that keep the totals honest:
 *  - Accumulation happens in a single 1 Hz loop driven by the real elapsed time
 *    (SystemClock), never inside BLE notification callbacks. Several notifications per
 *    second therefore cannot multiply the totals.
 *  - Cadence older than [STALE_MS] counts as 0 (official app: zeroMovementData after 5 s),
 *    so a stale value cannot keep adding distance/calories after the user stops.
 *  - Machine-reported totals (V2 0x8813, FTMS) are running counters. Only their increase
 *    since the previous tick is added; resets and implausible jumps are ignored.
 *  - Time only counts while RUNNING; manual pause, auto pause and disconnects stop it.
 *  - The formula-based burn rate is capped at [CalorieModel.MAX_KCAL_PER_MIN].
 */
class WorkoutTracker(
    private val context: Context,
    private val settings: AppSettings,
    private val machine: () -> MachineState,
    private val sendToMachine: (WorkoutCommand) -> Unit,
    private val log: (String) -> Unit,
) {
    private val _stats = MutableStateFlow(WorkoutStats())
    val stats: StateFlow<WorkoutStats> = _stats.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<WorkoutRecord>> = _history.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var running = false

    private var activeMs = 0L
    private var idleMs = 0L
    private var distanceM = 0.0
    private var calories = 0.0
    private var distanceSource = DataSource.NONE
    private var calorieSource = DataSource.NONE
    private var rpmWeighted = 0.0
    private var rpmMs = 0L
    private var resWeighted = 0.0
    private var hrWeighted = 0.0
    private var hrMs = 0L
    private var hrMax = 0
    private var kcalPerMin = 0.0
    private var machineDistanceSeen = false
    private var machineCaloriesSeen = false

    private val distanceCounter = DeviceCounter(maxStepPerSecond = 15.0)
    private val calorieCounter = DeviceCounter(maxStepPerSecond = 0.5)

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun begin() {
        if (running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        main.postDelayed(tickRunnable, TICK_MS)
    }

    private fun logRes(@StringRes id: Int, vararg args: Any) = log(context.getString(id, *args))

    // ------------------------------------------------------------------ user actions

    fun start() {
        val st = _stats.value.status
        if (st == WorkoutStatus.PAUSED || st == WorkoutStatus.AUTO_PAUSED) return resume()
        if (st == WorkoutStatus.RUNNING) return
        resetAccumulators()
        _stats.value = WorkoutStats(status = WorkoutStatus.RUNNING, startedAt = System.currentTimeMillis())
        logRes(R.string.log_workout_started)
        sendToMachine(WorkoutCommand.START)
    }

    fun pause() {
        val st = _stats.value.status
        if (st != WorkoutStatus.RUNNING && st != WorkoutStatus.AUTO_PAUSED) return
        setStatus(WorkoutStatus.PAUSED)
        logRes(R.string.log_workout_paused)
        sendToMachine(WorkoutCommand.PAUSE)
    }

    fun resume() {
        val st = _stats.value.status
        if (st != WorkoutStatus.PAUSED && st != WorkoutStatus.AUTO_PAUSED) return
        idleMs = 0
        setStatus(WorkoutStatus.RUNNING)
        logRes(R.string.log_workout_resumed)
        sendToMachine(WorkoutCommand.START)
    }

    fun finish() {
        val st = _stats.value.status
        if (st == WorkoutStatus.IDLE || st == WorkoutStatus.FINISHED) return
        publish(WorkoutStatus.FINISHED, machine())
        val s = _stats.value
        logRes(R.string.log_workout_finished, s.activeSec, s.distanceM, s.calories)
        sendToMachine(WorkoutCommand.STOP)
        if (s.activeSec >= MIN_RECORD_SEC) saveRecord(s)
    }

    /** From FINISHED back to IDLE (pedalling will then start a new workout automatically). */
    fun reset() {
        if (_stats.value.status != WorkoutStatus.FINISHED) return
        resetAccumulators()
        _stats.value = WorkoutStats()
    }

    fun clearHistory() {
        _history.value = emptyList()
        settings.workoutHistoryJson = null
    }

    // ------------------------------------------------------------------ 1 Hz loop

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // If the app was frozen for a while, do not extrapolate the gap.
        val dt = (now - lastTick).coerceIn(0, MAX_DT_MS)
        lastTick = now

        val m = machine()
        val connected = m.phase == ConnectionPhase.READY
        val rpmFresh = connected && (m.rpm ?: 0.0) > 0 && now - m.rpmUpdatedAt <= STALE_MS
        val rpm = if (rpmFresh) m.rpm ?: 0.0 else 0.0
        val status = _stats.value.status

        // Counters always track the latest machine value so a pause does not create a jump later.
        val counting = status == WorkoutStatus.RUNNING && connected
        val dDist = distanceCounter.step(m.deviceDistanceM, counting, dt)
        val dCal = calorieCounter.step(m.deviceCalories, counting, dt)

        when (status) {
            WorkoutStatus.IDLE -> if (rpmFresh) {
                logRes(R.string.log_auto_start)
                start()
            }
            WorkoutStatus.AUTO_PAUSED -> if (rpmFresh) {
                logRes(R.string.log_auto_resume)
                idleMs = 0
                setStatus(WorkoutStatus.RUNNING)
            }
            WorkoutStatus.RUNNING -> {
                if (!connected) {
                    logRes(R.string.log_disconnect_pause)
                    publish(WorkoutStatus.AUTO_PAUSED, m)
                    return
                }
                accumulate(m, dt, rpm, rpmFresh, dDist, dCal, now)
                idleMs = if (rpmFresh) 0 else idleMs + dt
                val limit = settings.autoPauseSec
                if (limit > 0 && idleMs >= limit * 1000L) {
                    logRes(R.string.log_idle_pause, limit)
                    publish(WorkoutStatus.AUTO_PAUSED, m)
                    return
                }
            }
            WorkoutStatus.PAUSED, WorkoutStatus.FINISHED -> Unit
        }
        if (_stats.value.status == status) publish(status, m)
    }

    private fun accumulate(m: MachineState, dt: Long, rpm: Double, rpmFresh: Boolean, dDist: Double, dCal: Double, now: Long) {
        activeMs += dt
        val sec = dt / 1000.0
        val resistance = m.reportedResistance ?: m.targetResistance

        // Use a machine counter only once it has actually moved in this session: some boxes
        // send the field but leave it at 0, which would otherwise freeze the total.
        if (dDist > 0) machineDistanceSeen = true
        if (dCal > 0) machineCaloriesSeen = true

        if (m.deviceDistanceM != null && machineDistanceSeen) {
            distanceM += dDist
            distanceSource = DataSource.MACHINE
        } else {
            distanceM += CalorieModel.speedKmh(rpm, m.machineType) / 3.6 * sec
            distanceSource = DataSource.FORMULA
        }

        if (m.deviceCalories != null && machineCaloriesSeen) {
            calories += dCal
            calorieSource = DataSource.MACHINE
            // Machine counters arrive in steps; smooth the displayed rate.
            val instant = if (sec > 0) dCal / sec * 60 else 0.0
            kcalPerMin = kcalPerMin * 0.8 + instant * 0.2
        } else {
            val rate = CalorieModel.kcalPerSecond(
                settings.calorieAlgorithm, rpm, resistance, m.resistanceMax,
                settings.userWeightKg, m.machineType, m.subType,
            ) * settings.calorieFactorPercent / 100.0
            calories += rate * sec
            calorieSource = DataSource.FORMULA
            kcalPerMin = rate * 60
        }

        if (rpmFresh) {
            rpmWeighted += rpm * dt
            resWeighted += resistance * dt.toDouble()
            rpmMs += dt
        }
        val hr = m.heartRate
        if (hr != null && hr > 0 && now - m.heartRateUpdatedAt <= STALE_MS) {
            hrWeighted += hr * dt.toDouble()
            hrMs += dt
            if (hr > hrMax) hrMax = hr
        }
    }

    private fun publish(status: WorkoutStatus, m: MachineState) {
        val now = SystemClock.elapsedRealtime()
        val rpmFresh = (m.rpm ?: 0.0) > 0 && now - m.rpmUpdatedAt <= STALE_MS
        val rpm = if (rpmFresh) m.rpm ?: 0.0 else 0.0
        val speed = if (status == WorkoutStatus.RUNNING) {
            m.deviceSpeedKmh?.takeIf { rpmFresh } ?: CalorieModel.speedKmh(rpm, m.machineType)
        } else {
            0.0
        }
        _stats.value = _stats.value.copy(
            status = status,
            activeSec = (activeMs / 1000).toInt(),
            distanceM = distanceM,
            calories = calories,
            distanceSource = distanceSource,
            calorieSource = calorieSource,
            speedKmh = speed,
            avgRpm = if (rpmMs > 0) rpmWeighted / rpmMs else 0.0,
            avgResistance = if (rpmMs > 0) resWeighted / rpmMs else 0.0,
            avgHeartRate = if (hrMs > 0) (hrWeighted / hrMs).toInt() else null,
            maxHeartRate = hrMax.takeIf { it > 0 },
            kcalPerMin = if (status == WorkoutStatus.RUNNING) kcalPerMin else 0.0,
        )
    }

    private fun setStatus(status: WorkoutStatus) = publish(status, machine())

    private fun resetAccumulators() {
        activeMs = 0
        idleMs = 0
        distanceM = 0.0
        calories = 0.0
        distanceSource = DataSource.NONE
        calorieSource = DataSource.NONE
        rpmWeighted = 0.0
        rpmMs = 0
        resWeighted = 0.0
        hrWeighted = 0.0
        hrMs = 0
        hrMax = 0
        kcalPerMin = 0.0
        machineDistanceSeen = false
        machineCaloriesSeen = false
    }

    // ------------------------------------------------------------------ history

    private fun saveRecord(s: WorkoutStats) {
        val record = WorkoutRecord(
            startedAt = s.startedAt,
            activeSec = s.activeSec,
            distanceM = s.distanceM,
            calories = s.calories,
            avgRpm = s.avgRpm,
            avgResistance = s.avgResistance,
            avgHeartRate = s.avgHeartRate,
            maxHeartRate = s.maxHeartRate,
        )
        val list = (listOf(record) + _history.value).take(MAX_HISTORY)
        _history.value = list
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("t", r.startedAt)
                    .put("sec", r.activeSec)
                    .put("m", r.distanceM)
                    .put("kcal", r.calories)
                    .put("rpm", r.avgRpm)
                    .put("res", r.avgResistance)
                    .put("hr", r.avgHeartRate ?: 0)
                    .put("hrMax", r.maxHeartRate ?: 0),
            )
        }
        settings.workoutHistoryJson = arr.toString()
    }

    private fun loadHistory(): List<WorkoutRecord> {
        val json = settings.workoutHistoryJson ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WorkoutRecord(
                    startedAt = o.getLong("t"),
                    activeSec = o.getInt("sec"),
                    distanceM = o.getDouble("m"),
                    calories = o.getDouble("kcal"),
                    avgRpm = o.optDouble("rpm", 0.0),
                    avgResistance = o.optDouble("res", 0.0),
                    avgHeartRate = o.optInt("hr", 0).takeIf { it > 0 },
                    maxHeartRate = o.optInt("hrMax", 0).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val MAX_DT_MS = 2000L
        const val STALE_MS = 5000L
        const val MIN_RECORD_SEC = 30
        const val MAX_HISTORY = 30
    }
}

/**
 * Converts a machine's running total into per-tick increments.
 * Handles counters that start at an arbitrary value, reset to 0, or glitch.
 */
internal class DeviceCounter(private val maxStepPerSecond: Double) {
    private var last: Double? = null
    private var rejected = 0

    fun step(value: Double?, counting: Boolean, dtMs: Long): Double {
        if (value == null) return 0.0
        val prev = last
        if (prev == null || !counting) {
            last = value
            rejected = 0
            return 0.0
        }
        val delta = if (value >= prev) value - prev else value // counter reset: count from 0
        // Allow a few seconds of backlog (notifications can be bursty), reject absurd jumps.
        val limit = maxStepPerSecond * (dtMs / 1000.0).coerceAtLeast(1.0) * 3
        if (delta <= limit) {
            last = value
            rejected = 0
            return delta
        }
        // A single garbage frame is skipped (keep the old baseline). If the jump persists,
        // the counter really moved (e.g. after a reconnect): adopt it without counting it.
        if (++rejected >= 3) {
            last = value
            rejected = 0
        }
        return 0.0
    }
}
