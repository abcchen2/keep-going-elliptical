package com.keepgoing.elliptical

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import com.keepgoing.elliptical.ble.BleUuids
import com.keepgoing.elliptical.ble.GattLink
import com.keepgoing.elliptical.ble.shortName
import com.keepgoing.elliptical.ble.toHex
import com.keepgoing.elliptical.ble.uuid16
import com.keepgoing.elliptical.model.AppSettings
import com.keepgoing.elliptical.model.ConnectionPhase
import com.keepgoing.elliptical.model.FoundDevice
import com.keepgoing.elliptical.model.MachineState
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.protocol.DemoMachine
import com.keepgoing.elliptical.protocol.MachineProtocol
import com.keepgoing.elliptical.protocol.ProtocolDetector
import com.keepgoing.elliptical.protocol.ProtocolHost
import com.keepgoing.elliptical.workout.WorkoutTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * App-wide owner of the BLE session: scanning, connecting, picking the protocol,
 * reconnecting and forwarding resistance commands. Lives in [KeepGoingApp] so a
 * configuration change does not drop the connection. Main thread only.
 */
@SuppressLint("MissingPermission")
class EllipticalController(private val context: Context) : GattLink.Listener {

    val settings = AppSettings(context)

    private val _state = MutableStateFlow(MachineState(targetResistance = 1))
    val state: StateFlow<MachineState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val link = GattLink(context, this)
    private var protocol: MachineProtocol? = null

    private var targetAddress: String? = null
    private var targetName: String = ""
    private var reconnectAttempts = 0
    private var restoreResistanceAfterConnect = false
    private var pendingResistanceSend: Runnable? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ------------------------------------------------------------------ scanning

    private var scanning = false
    private val stopScanRunnable = Runnable { stopScan() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScan(it) }
        override fun onScanFailed(errorCode: Int) {
            logRes(R.string.log_scan_failed, errorCode)
            scanning = false
            setPhase(ConnectionPhase.IDLE)
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            logRes(R.string.log_bt_off)
            return
        }
        if (scanning) return
        _devices.value = emptyList()
        scanning = true
        if (_state.value.phase == ConnectionPhase.IDLE) setPhase(ConnectionPhase.SCANNING)
        logRes(R.string.log_scan_start)
        // No service filter: several Mobi boxes advertise only their name.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed(stopScanRunnable, 12_000)
    }

    fun stopScan() {
        main.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (_state.value.phase == ConnectionPhase.SCANNING) setPhase(ConnectionPhase.IDLE)
        logRes(R.string.log_scan_done, _devices.value.size)
    }

    private fun onScan(r: ScanResult) {
        val name = r.scanRecord?.deviceName ?: try {
            r.device.name
        } catch (_: SecurityException) {
            null
        }
        val hints = r.scanRecord?.serviceUuids.orEmpty().map { it.uuid.shortName() }
        if (name.isNullOrBlank() && hints.isEmpty()) return
        val found = FoundDevice(r.device.address, name, r.rssi, hints)
        _devices.update { list ->
            (list.filterNot { it.address == found.address } + found)
                .sortedWith(compareByDescending<FoundDevice> { likelyMachine(it) }.thenByDescending { it.rssi })
        }
    }

    fun likelyMachine(d: FoundDevice): Boolean {
        val n = d.name.orEmpty()
        val known = BleUuids.SCAN_SERVICES.map { it.shortName() }
        return n.startsWith("MOBI", ignoreCase = true) || n.startsWith("MB-", ignoreCase = true) ||
            d.serviceHints.any { it in known }
    }

    // ------------------------------------------------------------------ connection

    fun connect(address: String, name: String?) {
        stopScan()
        protocol?.release()
        protocol = null
        targetAddress = address
        targetName = name.orEmpty()
        reconnectAttempts = 0
        restoreResistanceAfterConnect = false
        settings.lastDeviceAddress = address
        settings.lastDeviceName = name
        _state.value = MachineState(
            phase = ConnectionPhase.CONNECTING,
            deviceName = name,
            deviceAddress = address,
            targetResistance = 1,
        )
        openLink()
    }

    /**
     * Simulated elliptical for people without a Mobi machine (spec: .kiro/specs/demo-mode).
     * Needs no Bluetooth permission and drives the real workout tracker.
     */
    fun startDemo() {
        stopScan()
        main.removeCallbacksAndMessages(RECONNECT_TOKEN)
        protocol?.release()
        protocol = null
        targetAddress = null
        if (link.isConnected) link.disconnect()
        targetName = "Demo"
        _state.value = MachineState(
            phase = ConnectionPhase.READY,
            deviceName = "Demo Elliptical",
            protocol = ProtocolKind.DEMO,
            targetResistance = 8,
        )
        val demo = DemoMachine(host)
        protocol = demo
        demo.start()
    }

    val isDemo: Boolean get() = protocol is DemoMachine

    var demoPedalling: Boolean
        get() = (protocol as? DemoMachine)?.pedalling ?: false
        set(value) {
            (protocol as? DemoMachine)?.pedalling = value
        }

    fun connectLast(): Boolean {
        val addr = settings.lastDeviceAddress ?: return false
        connect(addr, settings.lastDeviceName)
        return true
    }

    private fun openLink() {
        val addr = targetAddress ?: return
        val device = try {
            adapter?.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            null
        } ?: run {
            logRes(R.string.log_invalid_address, addr)
            setPhase(ConnectionPhase.IDLE)
            return
        }
        if (targetName.isEmpty()) targetName = try {
            device.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
        link.connect(device)
    }

    fun disconnect() {
        main.removeCallbacksAndMessages(RECONNECT_TOKEN)
        targetAddress = null
        protocol?.release()
        protocol = null
        if (link.isConnected || _state.value.phase != ConnectionPhase.IDLE) {
            link.disconnect()
        }
        _state.update { it.copy(phase = ConnectionPhase.IDLE, controlReady = false) }
    }

    override fun onLinkConnected() {
        logRes(R.string.log_link_connected)
        setPhase(ConnectionPhase.SETTING_UP)
    }

    override fun onLinkReady() {
        val auto = ProtocolDetector.detect(link, targetName)
        val forced = settings.protocolChoice.kind
        val kind = forced ?: auto
        if (kind == null) {
            logRes(R.string.log_unknown_protocol)
            _state.update { it.copy(lastError = R.string.err_unknown_protocol) }
            link.disconnect()
            return
        }
        if (forced != null) {
            logRes(R.string.log_protocol_forced, s(kind.labelRes), auto?.let { s(it.labelRes) } ?: s(R.string.none))
        } else {
            logRes(R.string.log_protocol, s(kind.labelRes))
        }
        reconnectAttempts = 0
        _state.update {
            it.copy(phase = ConnectionPhase.READY, protocol = kind, lastError = null, controlReady = false)
        }
        val p = ProtocolDetector.create(kind, host)
        protocol = p
        p.start()
        if (restoreResistanceAfterConnect) {
            restoreResistanceAfterConnect = false
            val level = _state.value.targetResistance
            logRes(R.string.log_restore_level, level)
            sendResistanceNow(level)
        }
    }

    override fun onLinkDisconnected(status: Int, userRequested: Boolean) {
        if (protocol is DemoMachine) {
            // A real link closed after the user switched to demo mode; leave the demo running.
            logRes(R.string.log_demo_link_closed)
            return
        }
        protocol?.release()
        protocol = null
        val wasReady = _state.value.phase == ConnectionPhase.READY
        logRes(if (userRequested) R.string.log_disconnected_user else R.string.log_disconnected, status)
        _state.update { it.copy(controlReady = false) }
        val addr = targetAddress
        if (userRequested || addr == null) {
            setPhase(ConnectionPhase.IDLE)
            return
        }
        // Retry: GATT 133 on first attempts is common; also reconnect during a workout.
        val allowed = if (wasReady) settings.autoReconnect else true
        if (allowed && reconnectAttempts < MAX_RECONNECT) {
            reconnectAttempts++
            restoreResistanceAfterConnect = wasReady || restoreResistanceAfterConnect
            val delay = 1500L * reconnectAttempts
            logRes(R.string.log_reconnect, delay / 1000.0, reconnectAttempts)
            setPhase(ConnectionPhase.RECONNECTING)
            main.postAtTime({ openLink() }, RECONNECT_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
        } else {
            _state.update { it.copy(phase = ConnectionPhase.IDLE, lastError = R.string.err_disconnected) }
        }
    }

    override fun onNotify(characteristic: UUID, value: ByteArray) {
        if (verboseNotify) logRes(R.string.log_notify, characteristic.shortName(), value.toHex())
        protocol?.onNotify(characteristic, value)
    }

    override fun onLog(line: String) = log(line)

    /** Log every notification frame (noisy, for protocol debugging). */
    var verboseNotify = false

    // ------------------------------------------------------------------ control

    fun setResistance(level: Int) {
        val s = _state.value
        val clamped = level.coerceIn(s.resistanceMin, s.resistanceMax)
        _state.update { it.copy(targetResistance = clamped) }
        settings.lastResistance = clamped
        // Coalesce fast +/- taps into one command (the last value wins).
        pendingResistanceSend?.let { main.removeCallbacks(it) }
        val r = Runnable { sendResistanceNow(clamped) }
        pendingResistanceSend = r
        main.postDelayed(r, 120)
    }

    private fun sendResistanceNow(level: Int) {
        val p = protocol
        if (p == null) {
            logRes(R.string.log_level_pending, level)
            restoreResistanceAfterConnect = true
            return
        }
        // Protocols queue their setup first (FIFO GATT queue) and buffer the level if
        // they still need data from the machine, so it is safe to forward right away.
        logRes(R.string.log_set_level, level)
        p.setResistance(level)
    }

    /** Per-workout time / distance / calories / heart rate. Starts by itself when pedalling begins. */
    val workout = WorkoutTracker(
        context = context,
        settings = settings,
        machine = { _state.value },
        sendToMachine = { cmd -> protocol?.sendWorkout(cmd) },
        log = { log(it) },
    ).also { it.begin() }

    /** Debug helper: write raw bytes to any characteristic, e.g. "880f" + "02 01 05". */
    fun sendRaw(charShortUuid: String, hex: String): String? {
        val id = try {
            if (charShortUuid.length == 4) uuid16(charShortUuid.lowercase()) else UUID.fromString(charShortUuid)
        } catch (e: IllegalArgumentException) {
            return s(R.string.raw_bad_uuid)
        }
        val clean = hex.replace(" ", "").replace(",", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return s(R.string.raw_bad_length)
        val data = try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            return s(R.string.raw_bad_char)
        }
        val ch = link.findCharacteristic(id) ?: return s(R.string.raw_no_char, charShortUuid)
        link.write(ch, data) { ok -> logRes(R.string.log_raw_write, id.shortName(), data.toHex(), s(if (ok) R.string.ok else R.string.failed)) }
        return null
    }

    fun refreshRange() {
        // Called when the user changes the max-resistance override.
        val override = settings.maxResistanceOverride
        if (override > 0) {
            _state.update { it.copy(resistanceMax = override, resistanceRangeSource = R.string.range_manual) }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------------ host for protocols

    private val host = object : ProtocolHost {
        override val context: Context get() = this@EllipticalController.context
        override val link: GattLink get() = this@EllipticalController.link
        override val deviceName: String get() = targetName
        override val settings: AppSettings get() = this@EllipticalController.settings
        override val state: MachineState get() = _state.value
        override fun update(transform: (MachineState) -> MachineState) = _state.update(transform)
        override fun log(line: String) = this@EllipticalController.log(line)
        override fun postDelayed(delayMs: Long, block: () -> Unit): Runnable {
            val r = Runnable(block)
            main.postDelayed(r, delayMs)
            return r
        }
        override fun cancel(r: Runnable) = main.removeCallbacks(r)
    }

    private fun setPhase(phase: ConnectionPhase) = _state.update { it.copy(phase = phase) }

    /** Localised string; follows the system language. */
    private fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun logRes(@StringRes id: Int, vararg args: Any) = log(s(id, *args))

    private fun log(line: String) {
        val entry = timeFormat.format(Date()) + "  " + line
        android.util.Log.d("KeepGoing", line)
        _logs.update { (it + entry).takeLast(MAX_LOG_LINES) }
    }

    private companion object {
        const val MAX_RECONNECT = 5
        const val MAX_LOG_LINES = 400
        val RECONNECT_TOKEN = Any()
    }
}
