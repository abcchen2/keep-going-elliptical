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
            log("扫描失败 code=$errorCode")
            scanning = false
            setPhase(ConnectionPhase.IDLE)
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log("蓝牙未开启")
            return
        }
        if (scanning) return
        _devices.value = emptyList()
        scanning = true
        if (_state.value.phase == ConnectionPhase.IDLE) setPhase(ConnectionPhase.SCANNING)
        log("开始扫描（12 秒）")
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
        log("扫描结束，找到 ${_devices.value.size} 个设备")
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
            log("无效的设备地址 $addr")
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
        log("已建立连接，正在发现服务")
        setPhase(ConnectionPhase.SETTING_UP)
    }

    override fun onLinkReady() {
        val auto = ProtocolDetector.detect(link, targetName)
        val forced = settings.protocolChoice.kind
        val kind = forced ?: auto
        if (kind == null) {
            log("未识别的蓝牙协议：没有 8800 / FFE0 / FFF0 / FFC0 / 1826 服务")
            _state.update { it.copy(lastError = "这不是已知的莫比设备协议") }
            link.disconnect()
            return
        }
        log("协议: ${kind.label}" + if (forced != null) "（手动指定，自动识别结果=${auto?.label ?: "无"}）" else "")
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
            log("重连后恢复阻力 $level")
            sendResistanceNow(level)
        }
    }

    override fun onLinkDisconnected(status: Int, userRequested: Boolean) {
        if (protocol is DemoMachine) {
            // A real link closed after the user switched to demo mode; leave the demo running.
            log("蓝牙连接已关闭（演示模式继续运行）")
            return
        }
        protocol?.release()
        protocol = null
        val wasReady = _state.value.phase == ConnectionPhase.READY
        log("连接断开 status=$status" + if (userRequested) "（主动断开）" else "")
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
            log("${delay / 1000.0} 秒后第 $reconnectAttempts 次重连")
            setPhase(ConnectionPhase.RECONNECTING)
            main.postAtTime({ openLink() }, RECONNECT_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
        } else {
            _state.update { it.copy(phase = ConnectionPhase.IDLE, lastError = "连接已断开") }
        }
    }

    override fun onNotify(characteristic: UUID, value: ByteArray) {
        if (verboseNotify) log("通知 ${characteristic.shortName()}: ${value.toHex()}")
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
            log("设备未连接，阻力 $level 将在连接后发送")
            restoreResistanceAfterConnect = true
            return
        }
        // Protocols queue their setup first (FIFO GATT queue) and buffer the level if
        // they still need data from the machine, so it is safe to forward right away.
        log("设置阻力 -> $level")
        p.setResistance(level)
    }

    /** Per-workout time / distance / calories / heart rate. Starts by itself when pedalling begins. */
    val workout = WorkoutTracker(
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
            return "UUID 格式不对"
        }
        val clean = hex.replace(" ", "").replace(",", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return "十六进制长度不对"
        val data = try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            return "包含非十六进制字符"
        }
        val ch = link.findCharacteristic(id) ?: return "设备上没有特征 $charShortUuid"
        link.write(ch, data) { ok -> log("原始写入 ${id.shortName()} [${data.toHex()}] ${if (ok) "成功" else "失败"}") }
        return null
    }

    fun refreshRange() {
        // Called when the user changes the max-resistance override.
        val override = settings.maxResistanceOverride
        if (override > 0) {
            _state.update { it.copy(resistanceMax = override, resistanceRangeSource = "手动设置") }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------------ host for protocols

    private val host = object : ProtocolHost {
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
