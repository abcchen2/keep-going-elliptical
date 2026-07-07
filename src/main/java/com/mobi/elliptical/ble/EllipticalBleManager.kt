package com.mobi.elliptical.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.mobi.elliptical.data.ConnectionState
import com.mobi.elliptical.data.ExerciseData
import com.mobi.elliptical.data.MobiBleProfile
import com.mobi.elliptical.data.MovementDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 莫比椭圆机 Bluetooth Low Energy 管理器
 *
 * 基于 nRF Connect 抓包结果实现的完整协议适配：
 * - 设备: MB-MEH-3202G-0001C3 (HUAWEI 制造)
 * - 主协议: 标准 FTMS (Fitness Machine Service, 0x1826)
 * - 数据特征: Cross Trainer Data (0x2ACE)
 * - 控制特征: Fitness Machine Control Point (0x2AD9)
 * - 辅助: 莫比自定义服务 (0x8800) + 心率服务 (0x180D)
 */
@Singleton
class EllipticalBleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // 连接状态
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 实时运动数据
    private val _exerciseData = MutableStateFlow(ExerciseData())
    val exerciseData: StateFlow<ExerciseData> = _exerciseData.asStateFlow()

    // 阻力范围（供 UI 使用）
    private val _resistanceRange = MutableStateFlow(Pair(MobiBleProfile.MIN_RESISTANCE, MobiBleProfile.MAX_RESISTANCE))
    val resistanceRange: StateFlow<Pair<Int, Int>> = _resistanceRange.asStateFlow()

    // Bluetooth Gatt 对象
    private var bluetoothGatt: BluetoothGatt? = null

    // 特征缓存（连接后初始化）
    private var crossTrainerDataChar: BluetoothGattCharacteristic? = null   // 0x2ACE
    private var controlPointChar: BluetoothGattCharacteristic? = null       // 0x2AD9
    private var heartRateChar: BluetoothGattCharacteristic? = null          // 0x2A37
    private var machineStatusChar: BluetoothGattCharacteristic? = null      // 0x2ADA
    private var trainingStatusChar: BluetoothGattCharacteristic? = null     // 0x2AD3
    private var supportedResistanceRangeChar: BluetoothGattCharacteristic? = null  // 0x2AD6
    private var mobiRealtimeChar: BluetoothGattCharacteristic? = null      // 0x8811
    private var mobiUnlockChar: BluetoothGattCharacteristic? = null        // 0x88ff

    // 阻力范围 — 固定使用 1-24，不依赖 0x2AD6 读取（避免错误值导致截断）
    // 参考：ResistanceConstant.java 中 resistanceMin=1, resistanceMax=24
    private var minResistance: Int = 1
    private var maxResistance: Int = 24

    // 运动开始时间（本地计算用）
    private var localExerciseStartTime: Long = 0L
    private var isLocalExerciseRunning: Boolean = false
    private var lastUpdateTime: Long = 0L

    // 累加数据基准点（用于计算单次会话的数据）
    private var initialEnergyKcal: Float? = null
    private var initialDistanceMeters: Float? = null
    private var initialStrideCount: Int? = null

    // ==================== BLE 操作队列 ====================
    // Android BLE 同一时间只能执行一个 GATT 操作（writeDescriptor / writeCharacteristic / readCharacteristic）
    // 必须串行化所有操作，否则除第一个外全部返回 false
    private data class BleOperation(val description: String, val action: () -> Unit)
    private val operationQueue = java.util.concurrent.ConcurrentLinkedQueue<BleOperation>()
    @Volatile private var currentOperation: BleOperation? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val operationTimeoutRunnable = Runnable {
        android.util.Log.w("EllipticalBle", "⏰ BLE operation timeout: ${currentOperation?.description}, advancing queue")
        completeCurrentOperation()
    }

    private fun enqueueOperation(description: String, action: () -> Unit) {
        operationQueue.add(BleOperation(description, action))
        processNextOperation()
    }

    @Synchronized
    private fun processNextOperation() {
        if (currentOperation != null) return
        currentOperation = operationQueue.poll()
        if (currentOperation != null) {
            android.util.Log.d("EllipticalBle", "▶️ BLE OP: ${currentOperation!!.description}")
            mainHandler.postDelayed(operationTimeoutRunnable, 5000)
            try {
                currentOperation!!.action()
            } catch (e: Exception) {
                android.util.Log.e("EllipticalBle", "BLE OP exception: ${e.message}", e)
                completeCurrentOperation()
            }
        }
    }

    @Synchronized
    private fun completeCurrentOperation() {
        mainHandler.removeCallbacks(operationTimeoutRunnable)
        val desc = currentOperation?.description
        currentOperation = null
        if (desc != null) {
            android.util.Log.d("EllipticalBle", "✅ BLE OP done: $desc")
        }
        processNextOperation()
    }

    /**
     * 兼容 Android 12+ (API 31+) 的 writeCharacteristic 封装
     * - Android 12+ : gatt.writeCharacteristic(char, value, writeType) -> int (GATT_SUCCESS=0)
     * - Android <12 : char.value=value; char.writeType=writeType; gatt.writeCharacteristic(char) -> boolean
     *
     * 使用成员变量 bluetoothGatt 和 controlPointChar，避免参数不一致。
     */
    @SuppressLint("MissingPermission", "NewApi")
    private fun writeCharacteristicCompat(char: BluetoothGattCharacteristic, command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            android.util.Log.e("EllipticalBle", "writeCharacteristicCompat: bluetoothGatt is null!")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ : 新 API，直接传入 value 和 writeType
                val result = gatt.writeCharacteristic(char, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                android.util.Log.d("EllipticalBle", "writeCharacteristicCompat(new API) -> result=$result (GATT_SUCCESS=0)")
                result == 0
            } else {
                // Android <12 : 旧 API
                char.value = command
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val result = gatt.writeCharacteristic(char)
                android.util.Log.d("EllipticalBle", "writeCharacteristicCompat(old API) -> result=$result")
                result
            }
        } catch (e: Exception) {
            android.util.Log.e("EllipticalBle", "writeCharacteristicCompat exception: ${e.message}", e)
            false
        }
    }

    // ==================== 设备扫描 ====================

    /**
     * 扫描莫比椭圆机设备
     * 过滤规则：设备名以 MB-MEH / MOBI / Mobi 开头，或包含 Elliptical
     */
    @SuppressLint("MissingPermission")
    fun scanForDevices(): Flow<List<BluetoothDevice>> = callbackFlow {
        val foundDevices = mutableListOf<BluetoothDevice>()

        val scanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
            val deviceName = device.name ?: return@LeScanCallback

            val isMobiDevice = MobiBleProfile.DEVICE_NAME_PREFIXES.any { prefix ->
                deviceName.startsWith(prefix, ignoreCase = true)
            }

            if (isMobiDevice && !foundDevices.any { it.address == device.address }) {
                foundDevices.add(device)
                trySend(foundDevices.toList())
            }
        }

        _connectionState.value = ConnectionState.Scanning
        bluetoothAdapter?.startLeScan(scanCallback)

        awaitClose { bluetoothAdapter?.stopLeScan(scanCallback) }
    }

    // ==================== 连接管理 ====================

    /**
     * 连接到指定设备
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * 断开连接并清理资源
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopLocalExercise()
        // 清空操作队列
        operationQueue.clear()
        currentOperation = null
        mainHandler.removeCallbacks(operationTimeoutRunnable)

        initialEnergyKcal = null
        initialDistanceMeters = null
        initialStrideCount = null

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        crossTrainerDataChar = null
        controlPointChar = null
        heartRateChar = null
        _connectionState.value = ConnectionState.Disconnected
        _exerciseData.value = ExerciseData()
    }

    // ==================== 阻力控制 ====================

    /**
     * 设置阻力等级（通过 FTMS 标准控制点 0x2AD9）
     *
     * ⚠️ 关键修复：完全复制官方 App（mbjs_52901.apk）的行为：
     * 1. 不使用 coerceIn 截断 level — 固定范围 1-24，直接用
     * 2. 发送 2 字节 {0x04, (byte)(level*10)} — 与官方完全一致
     * 3. 不依赖 0x2AD6 读取的 maxResistance（可能返回错误值导致截断）
     */
    @SuppressLint("MissingPermission")
    fun setResistance(level: Int) {
        if (controlPointChar == null || bluetoothGatt == null) {
            android.util.Log.w("EllipticalBle", "setResistance failed: controlPointChar or gatt is null")
            return
        }

        android.util.Log.d("EllipticalBle", "🔧 setResistance(input=$level, minRes=$minResistance, maxRes=$maxResistance)")

        enqueueOperation("SetResistance($level)") {
            sendResistanceCommand(level)
        }
    }

    /**
     * 实际发送阻力命令到设备（通过队列调用）
     * 完全复制官方 App: writeFtmsDevice(new byte[]{4, (byte)(level * 10)})
     */
    @SuppressLint("MissingPermission")
    private fun sendResistanceCommand(level: Int) {
        if (controlPointChar == null || bluetoothGatt == null) {
            android.util.Log.w("EllipticalBle", "sendResistanceCommand($level) failed: controlPointChar or gatt is null")
            return
        }

        // 官方 App 发送格式: {0x04, (byte)(level * 10)} — 2 字节
        val command = MobiBleProfile.Commands.setResistance(level, 24)
        val result = writeCharacteristicCompat(controlPointChar!!, command)
        android.util.Log.d("EllipticalBle", "sendResistanceCommand($level) -> writeResult=$result, bytes=[${command.joinToString { "%02X".format(it) }}] (${command.size} bytes)")
        if (!result) {
            android.util.Log.w("EllipticalBle", "sendResistanceCommand($level) write failed locally")
        }
    }

    /**
     * 开始运动
     */
    @SuppressLint("MissingPermission")
    fun startExercise() {
        enqueueOperation("StartExercise") {
            val command = MobiBleProfile.Commands.startExercise()
            val result = writeCharacteristicCompat(controlPointChar!!, command)
            android.util.Log.d("EllipticalBle", "startExercise -> writeResult=$result")
        }
        startLocalExercise()
    }

    /**
     * 停止运动
     */
    @SuppressLint("MissingPermission")
    fun stopExercise() {
        enqueueOperation("StopExercise") {
            val command = MobiBleProfile.Commands.stopExercise()
            val result = writeCharacteristicCompat(controlPointChar!!, command)
            android.util.Log.d("EllipticalBle", "stopExercise -> writeResult=$result")
        }
        stopLocalExercise()
    }

    private fun startLocalExercise() {
        localExerciseStartTime = System.currentTimeMillis()
        lastUpdateTime = localExerciseStartTime
        isLocalExerciseRunning = true
        _exerciseData.value = _exerciseData.value.copy(isRunning = true)
    }

    private fun stopLocalExercise() {
        isLocalExerciseRunning = false
        _exerciseData.value = _exerciseData.value.copy(isRunning = false)
    }

    fun resetExerciseData() {
        initialEnergyKcal = null
        initialDistanceMeters = null
        initialStrideCount = null
        stopLocalExercise()
        _exerciseData.value = ExerciseData()
    }

    // ==================== GATT 回调 ====================

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connected(
                        gatt.device.name ?: "Unknown",
                        gatt.device.address
                    )
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != 0) {
                android.util.Log.e("EllipticalBle", "onServicesDiscovered FAILED: status=$status")
                return
            }

            android.util.Log.d("EllipticalBle", "onServicesDiscovered: finding characteristics...")

            // 1. 先找到所有特征（不执行任何操作）
            val ftmsService = gatt.services.find {
                it.uuid == MobiBleProfile.FITNESS_MACHINE_SERVICE
            }
            ftmsService?.characteristics?.forEach { char ->
                when (char.uuid) {
                    MobiBleProfile.CROSS_TRAINER_DATA -> crossTrainerDataChar = char
                    MobiBleProfile.FITNESS_MACHINE_CONTROL_POINT -> controlPointChar = char
                    MobiBleProfile.FITNESS_MACHINE_STATUS -> machineStatusChar = char
                    MobiBleProfile.TRAINING_STATUS -> trainingStatusChar = char
                    MobiBleProfile.SUPPORTED_RESISTANCE_RANGE -> supportedResistanceRangeChar = char
                }
            }

            val hrService = gatt.services.find {
                it.uuid == MobiBleProfile.HEART_RATE_SERVICE
            }
            hrService?.characteristics?.forEach { char ->
                if (char.uuid == MobiBleProfile.HEART_RATE_MEASUREMENT) {
                    heartRateChar = char
                }
            }

            val mobiService = gatt.services.find {
                it.uuid == MobiBleProfile.MOBI_CUSTOM_SERVICE
            }
            mobiService?.characteristics?.forEach { char ->
                if (char.uuid == MobiBleProfile.MOBI_REALTIME_DATA) {
                    mobiRealtimeChar = char
                }
                if (char.uuid == MobiBleProfile.MOBI_UNLOCK) {
                    mobiUnlockChar = char
                }
            }

            // 2. 用队列串行化所有 BLE 操作（关键！Android BLE 不能并发写入）

            // 官方 App 对 FTMS 设备不发送 0x88FF 解锁指令
            // 且不对 Control Point (0x2AD9) 启用 Indication，直接写入

            // 2c. 发送 FTMS Request Control (0x00) — 官方 App 在连接后立即发送
            enqueueOperation("Request Control (0x00)") {
                requestControl(gatt)
            }

            // 2c. 启用 Cross Trainer Data (0x2ACE) 通知
            crossTrainerDataChar?.let { char ->
                enqueueOperation("Enable Notification on CrossTrainerData(0x2ACE)") {
                    enableNotification(gatt, char)
                }
            }

            // 2d. 启用 Machine Status (0x2ADA) 通知
            machineStatusChar?.let { char ->
                enqueueOperation("Enable Notification on MachineStatus(0x2ADA)") {
                    enableNotification(gatt, char)
                }
            }

            // 2e. 启用 Training Status (0x2AD3) 通知
            trainingStatusChar?.let { char ->
                enqueueOperation("Enable Notification on TrainingStatus(0x2AD3)") {
                    enableNotification(gatt, char)
                }
            }

            // 2f. 读取 Supported Resistance Range (0x2AD6) — 已禁用
            // 不读取 0x2AD6，固定使用 1-24 范围（避免设备返回错误值导致阻力截断）
            // supportedResistanceRangeChar?.let { char ->
            //     enqueueOperation("Read SupportedResistanceRange(0x2AD6)") {
            //         gatt.readCharacteristic(char)
            //     }
            // }

            // 2g. 启用心率通知
            heartRateChar?.let { char ->
                enqueueOperation("Enable Notification on HeartRate(0x2A37)") {
                    enableNotification(gatt, char)
                }
            }

            // 2h. 启用莫比自定义实时数据通知（如果存在）
            mobiRealtimeChar?.let { char ->
                enqueueOperation("Enable Notification on MobiRealtime") {
                    enableNotification(gatt, char)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            android.util.Log.d("EllipticalBle", "onCharacteristicRead: ${characteristic.uuid}, status=$status")
            if (status == 0) {
                when (characteristic.uuid) {
                    MobiBleProfile.SUPPORTED_RESISTANCE_RANGE -> {
                        // 解析阻力范围 (FTMS 0x2AD6)
                        // 格式: Minimum(sint16, 0.1单位) + Maximum(sint16, 0.1单位) + Increment(sint16, 0.1单位)
                        val value = characteristic.value ?: return
                        var parsedMin: Int
                        var parsedMax: Int
                        if (value.size >= 4) {
                            val rawMin = ((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)).toShort().toInt()
                            val rawMax = ((value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)).toShort().toInt()
                            // FTMS 规范: 0.1 单位，除以 10 得到实际等级
                            parsedMin = rawMin / 10
                            parsedMax = rawMax / 10
                            android.util.Log.d("EllipticalBle", "SupportedResistanceRange raw: min=$rawMin max=$rawMax -> parsed: min=$parsedMin max=$parsedMax")
                        } else {
                            parsedMin = MobiBleProfile.MIN_RESISTANCE
                            parsedMax = MobiBleProfile.MAX_RESISTANCE
                        }

                        // 严格校验：莫比椭圆机阻力范围必须在 1-32 之间，且 max > min
                        // 不合理的值（如 0、2、100）会导致反向映射错乱
                        val validRange = parsedMin in 1..16 && parsedMax in 2..32 && parsedMax > parsedMin
                        if (validRange) {
                            minResistance = parsedMin
                            maxResistance = parsedMax
                        } else {
                            // 校验失败，使用默认值
                            android.util.Log.w("EllipticalBle", "⚠️ Invalid resistance range $parsedMin-$parsedMax, using defaults ${MobiBleProfile.MIN_RESISTANCE}-${MobiBleProfile.MAX_RESISTANCE}")
                            minResistance = MobiBleProfile.MIN_RESISTANCE
                            maxResistance = MobiBleProfile.MAX_RESISTANCE
                        }
                        _resistanceRange.value = Pair(minResistance, maxResistance)
                        android.util.Log.d("EllipticalBle", "Supported Resistance Range final: $minResistance - $maxResistance")
                    }
                }
            }
            // 推进队列
            completeCurrentOperation()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            when (characteristic.uuid) {
                MobiBleProfile.CROSS_TRAINER_DATA -> parseCrossTrainerData(data)
                MobiBleProfile.HEART_RATE_MEASUREMENT -> parseHeartRateData(data)
                MobiBleProfile.MOBI_REALTIME_DATA -> {} // 可选的莫比额外数据
                MobiBleProfile.FITNESS_MACHINE_STATUS -> {}
                MobiBleProfile.TRAINING_STATUS -> {}
                MobiBleProfile.FITNESS_MACHINE_CONTROL_POINT -> {
                    // 控制点响应（如操作结果确认）
                    handleControlPointResponse(data)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            android.util.Log.d("EllipticalBle", "onCharacteristicWrite: ${characteristic.uuid}, status=$status")
            if (characteristic.uuid == MobiBleProfile.FITNESS_MACHINE_CONTROL_POINT) {
                if (status == 0) {
                    android.util.Log.d("EllipticalBle", "✅ ControlPoint write sent successfully, waiting for indication response...")
                } else {
                    android.util.Log.e("EllipticalBle", "❌ ControlPoint write FAILED: status=$status")
                }
            }
            // 推进队列
            completeCurrentOperation()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            android.util.Log.d("EllipticalBle", "onDescriptorWrite: status=$status, uuid=${descriptor.characteristic?.uuid}")
            if (status != 0) {
                android.util.Log.w("EllipticalBle", "⚠️ Descriptor write failed: status=$status")
            }
            // 推进队列
            completeCurrentOperation()
        }
    }

    /**
     * 启用通知或指示
     */
    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val charUuid = characteristic.uuid
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(MobiBleProfile.CCCD)

        if (descriptor == null) {
            android.util.Log.w("EllipticalBle", "enableNotification: CCCD descriptor not found for $charUuid!")
            completeCurrentOperation()
            return
        }

        // 判断是使用 Notification 还是 Indication
        val isIndication = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val cccdValue = if (isIndication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        android.util.Log.d("EllipticalBle", "enableNotification: $charUuid, type=${if (isIndication) "INDICATION" else "NOTIFICATION"}")

        descriptor.value = cccdValue
        val result = gatt.writeDescriptor(descriptor)
        if (!result) {
            android.util.Log.w("EllipticalBle", "enableNotification: writeDescriptor returned false for $charUuid!")
            completeCurrentOperation()
        }
    }

    /**
     * 请求 FTMS 控制权
     */
    @SuppressLint("MissingPermission")
    private fun requestControl(gatt: BluetoothGatt) {
        controlPointChar?.let { char ->
            val command = MobiBleProfile.Commands.requestControl()
            val result = writeCharacteristicCompat(char, command)
            android.util.Log.d("EllipticalBle", "requestControl -> writeResult=$result")
        } ?: run {
            android.util.Log.e("EllipticalBle", "requestControl: controlPointChar is null!")
        }
    }

    /**
     * 处理控制点响应
     * FTMS 控制点响应格式: [responseOpcode(0x80+requestOp), requestOp, resultCode]
     * resultCode: 0x00=成功, 0x01=不支持/拒绝, 0x02=无效参数, 0x03=操作失败
     */
    private fun handleControlPointResponse(data: ByteArray) {
        if (data.isEmpty()) return
        val hexResp = data.joinToString("-") { "%02X".format(it) }
        val responseOp = data[0].toInt() and 0xFF

        if (data.size >= 3) {
            val requestOp = data[1].toInt() and 0xFF
            val resultCode = data[2].toInt() and 0xFF

            android.util.Log.d("EllipticalBle", "ControlPointResponse: $hexResp reqOp=0x${"%02X".format(requestOp)} result=$resultCode")
        } else {
            android.util.Log.d("EllipticalBle", "ControlPointResponse (short): $hexResp")
        }
    }

    private fun crossTrainerExpectedLength(rawData: ByteArray, flagSize: Int): Int? {
        if (rawData.size < flagSize || flagSize < 2) return null

        val flags = (rawData[0].toInt() and 0xFF) or
                ((rawData[1].toInt() and 0xFF) shl 8)
        var length = flagSize

        // FTMS Cross Trainer bit 0 is "More Data"; instantaneous speed is present when it is 0.
        if ((flags and 0x0001) == 0) length += 2
        if ((flags and 0x0002) != 0) length += 2
        if ((flags and 0x0004) != 0) length += 3
        if ((flags and 0x0008) != 0) length += 4
        if ((flags and 0x0010) != 0) length += 2
        if ((flags and 0x0020) != 0) length += 4
        if ((flags and 0x0040) != 0) length += 4
        if ((flags and 0x0080) != 0) length += 2
        if ((flags and 0x0100) != 0) length += 2
        if ((flags and 0x0200) != 0) length += 2
        if ((flags and 0x0400) != 0) length += 5
        if ((flags and 0x0800) != 0) length += 1
        if ((flags and 0x1000) != 0) length += 1
        if ((flags and 0x2000) != 0) length += 2
        if ((flags and 0x4000) != 0) length += 2

        return length
    }

    private fun detectCrossTrainerFlagSize(rawData: ByteArray): Int {
        for (flagSize in intArrayOf(2, 3)) {
            if (crossTrainerExpectedLength(rawData, flagSize) == rawData.size) {
                return flagSize
            }
        }

        val len2 = crossTrainerExpectedLength(rawData, 2) ?: Int.MAX_VALUE
        val len3 = crossTrainerExpectedLength(rawData, 3) ?: Int.MAX_VALUE
        return if (kotlin.math.abs(rawData.size - len3) < kotlin.math.abs(rawData.size - len2)) 3 else 2
    }

    // ============================================================
    // FTMS Cross Trainer 数据解析 (莫比椭圆机专用)
    // 特征 UUID: 0x2ACE (Cross Trainer Data under Fitness Machine Service 0x1826)
    //
    // ⚠️ 关键：莫比椭圆机使用 3 字节 Flags（非标准），而非 FTMS 规范的 2 字节
    //   官方 App (mbjs_52901) 的 FLAG_SUPPORT_SIZES = [2, 3]，支持两种 flag size
    //   莫比 MB-MEH-3202G 实际发送 3 字节 flags（第 3 字节为 0x00）
    //   数据从 offset 3 开始，而非 offset 2
    //
    // 数据格式:
    // Byte 0-2: Flags (3 bytes, 莫比扩展格式)
    // Byte 3+:  各字段按 Flags 中置位的顺序依次出现 (little-endian)
    // ============================================================

    private fun parseCrossTrainerData(rawData: ByteArray) {
        try {
            if (rawData.size < 3) return

            // 调试日志：打印原始数据（完整 hex dump）
            val hexData = rawData.joinToString("-") { "%02X".format(it) }
            android.util.Log.d("EllipticalBle", "CrossTrainerData RAW: $hexData (${rawData.size} bytes)")

            val currentData = _exerciseData.value
            val currentTime = System.currentTimeMillis()
            val timeDelta = if (lastUpdateTime > 0) (currentTime - lastUpdateTime) / 1000f else 0f
            lastUpdateTime = currentTime

            // 解析 Flags — 莫比使用 3 字节 flags（第 3 字节通常为 0x00）
            val flagSize = detectCrossTrainerFlagSize(rawData)
            val flags0 = rawData[0].toInt() and 0xFF
            val flags1 = rawData[1].toInt() and 0xFF
            val flagsValue = flags0 or (flags1 shl 8)
            var offset = flagSize
            android.util.Log.d("EllipticalBle", "Flags: 0x${"%04X".format(flagsValue)} flagSize=$flagSize expected=${crossTrainerExpectedLength(rawData, flagSize)} (f0=0x${"%02X".format(flags0)}, f1=0x${"%02X".format(flags1)})")

            var instantaneousSpeed = 0f
            var averageSpeed = 0f
            var totalDistanceMeters = 0f
            var stepPerMinute = 0
            var averageStepRate = 0
            var strideCount = 0
            var resistanceLevel = currentData.resistance
            var instantaneousPower = 0f
            var averagePower = 0f
            var totalEnergyKcal = 0f
            var energyPerHour = 0f
            var energyPerMinute = 0f
            var heartRate = currentData.heartRate
            var elapsedSeconds = 0
            var movementDirection = MovementDirection.FORWARD

            // Bit 0 is "More Data"; instantaneous speed is present when the bit is 0.
            if ((flags0 and 0x01) == 0 && offset + 1 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                instantaneousSpeed = rawVal * 0.01f
                android.util.Log.d("EllipticalBle", "  [$offset] InstSpeed: raw=$rawVal -> ${instantaneousSpeed} km/h [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 1: Average Speed present (uint16, 0.01 km/h resolution, LITTLE-ENDIAN)
            if ((flags0 and 0x02) != 0 && offset + 1 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                averageSpeed = rawVal * 0.01f
                android.util.Log.d("EllipticalBle", "  [$offset] AvgSpeed: raw=$rawVal -> ${averageSpeed} km/h [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 2: Total Distance present (uint24, 1 meter resolution, LITTLE-ENDIAN)
            if ((flags0 and 0x04) != 0 && offset + 2 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8) or
                        ((rawData[offset + 2].toInt() and 0xFF) shl 16)
                totalDistanceMeters = rawVal.toFloat()
                android.util.Log.d("EllipticalBle", "  [$offset] TotalDist: raw=$rawVal -> ${totalDistanceMeters}m [${"%02X %02X %02X".format(rawData[offset], rawData[offset+1], rawData[offset+2])}]")
                offset += 3
            }

            // Bit 3: Step Per Minute present (uint16, 1 steps/min resolution) AND Average Step Rate (uint16)
            if ((flags0 and 0x08) != 0 && offset + 3 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                stepPerMinute = rawVal
                offset += 2
                
                val rawAvg = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                averageStepRate = rawAvg
                android.util.Log.d("EllipticalBle", "  [${offset-2}] Cadence: $stepPerMinute spm, Avg: $averageStepRate spm")
                offset += 2
            }

            // Bit 4: Stride Count present (uint16, LITTLE-ENDIAN)
            if ((flags0 and 0x10) != 0 && offset + 1 < rawData.size) {
                strideCount = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                android.util.Log.d("EllipticalBle", "  [$offset] StrideCount: $strideCount [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 5: Positive Elevation Gain / Negative Elevation Gain present (uint16 + uint16)
            if ((flags0 and 0x20) != 0 && offset + 3 < rawData.size) {
                android.util.Log.d("EllipticalBle", "  [$offset] Elevation Gain present (skipping 4 bytes)")
                offset += 4
            }

            // Bit 6: Incline and Ramp Angle Setting present (sint16 + sint16)
            if ((flags0 and 0x40) != 0 && offset + 3 < rawData.size) {
                android.util.Log.d("EllipticalBle", "  [$offset] Incline & Ramp Angle present (skipping 4 bytes)")
                offset += 4
            }

            // Bit 7: Resistance Level present (sint16, 0.1 resolution)
            // 参考官方 App（mbjs_52901.apk）反编译：CrossTrainerDataFlags.convertBytesToData()
            //   value = round(rawShort * 0.1 * 10) / 10.0 = rawShort / 10
            // 官方实现没有任何反向映射，直接除以 10
            // 设备返回 sint16=10 → level=1 (最轻), sint16=240 → level=24 (最重)
            if ((flags0 and 0x80) != 0 && offset + 1 < rawData.size) {
                val rawVal = ((rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                val rawLevel = (rawVal / 10).coerceIn(minResistance, maxResistance)
                resistanceLevel = rawLevel
                android.util.Log.d("EllipticalBle", "  [$offset] Resistance: raw=$rawVal -> level=$resistanceLevel (range $minResistance-$maxResistance) [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 8: Instantaneous Power present (sint16, 1 Watt resolution, LITTLE-ENDIAN)
            if ((flags1 and 0x01) != 0 && offset + 1 < rawData.size) {
                val rawVal = ((rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                instantaneousPower = rawVal.toFloat()
                android.util.Log.d("EllipticalBle", "  [$offset] InstPower: raw=$rawVal -> ${instantaneousPower}W [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 9: Average Power present (sint16, 1 Watt resolution, LITTLE-ENDIAN)
            if ((flags1 and 0x02) != 0 && offset + 1 < rawData.size) {
                val rawVal = ((rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                averagePower = rawVal.toFloat()
                android.util.Log.d("EllipticalBle", "  [$offset] AvgPower: raw=$rawVal -> ${averagePower}W [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 10: Expended Energy (Total Energy (uint16) + Energy Per Hour (uint16) + Energy Per Minute (uint8))
            if ((flags1 and 0x04) != 0 && offset + 4 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                totalEnergyKcal = if (rawVal == 0xFFFF) 0f else rawVal.toFloat()
                
                val hrVal = (rawData[offset + 2].toInt() and 0xFF) or
                        ((rawData[offset + 3].toInt() and 0xFF) shl 8)
                energyPerHour = if (hrVal == 0xFFFF) 0f else hrVal.toFloat()
                
                val minVal = rawData[offset + 4].toInt() and 0xFF
                energyPerMinute = if (minVal == 0xFF) 0f else minVal.toFloat()
                
                android.util.Log.d("EllipticalBle", "  [$offset] Energy: total=$rawVal, hr=$hrVal, min=$minVal")
                offset += 5
            }

            // Bit 11: Heart Rate present (uint8, bpm)
            if ((flags1 and 0x08) != 0 && offset < rawData.size) {
                val hr = rawData[offset].toInt() and 0xFF
                if (hr > 0) heartRate = hr
                android.util.Log.d("EllipticalBle", "  [$offset] HeartRate: $hr bpm (kept $heartRate) [${"%02X".format(rawData[offset])}]")
                offset += 1
            }

            // Bit 12: Metabolic Equivalent present (uint8, 0.1)
            if ((flags1 and 0x10) != 0 && offset < rawData.size) {
                android.util.Log.d("EllipticalBle", "  [$offset] Metabolic Equivalent present (skipping 1 byte)")
                offset += 1
            }

            // Bit 13: Elapsed Time present (uint16, seconds, LITTLE-ENDIAN)
            if ((flags1 and 0x20) != 0 && offset + 1 < rawData.size) {
                val rawVal = (rawData[offset].toInt() and 0xFF) or
                        ((rawData[offset + 1].toInt() and 0xFF) shl 8)
                elapsedSeconds = rawVal
                android.util.Log.d("EllipticalBle", "  [$offset] ElapsedTime: raw=$rawVal -> ${elapsedSeconds}s [${"%02X %02X".format(rawData[offset], rawData[offset+1])}]")
                offset += 2
            }

            // Bit 14: Remaining Time present (uint16, seconds, LITTLE-ENDIAN)
            if ((flags1 and 0x40) != 0 && offset + 1 < rawData.size) {
                android.util.Log.d("EllipticalBle", "  [$offset] Remaining Time present (skipping 2 bytes)")
                offset += 2
            }

            // Bit 15: Movement Direction (if present in extended format)

            // 记录解析后的剩余字节（莫比设备可能有自定义扩展字段）
            if (offset < rawData.size) {
                val remaining = rawData.drop(offset).joinToString("-") { "%02X".format(it) }
                android.util.Log.d("EllipticalBle", "  ⚠️ Remaining bytes at [$offset]: $remaining (${rawData.size - offset} bytes)")
            }

            // ========== 数据合理性过滤与会话累加 ==========
            // 功率合理范围: 0-2000W（椭圆机一般不会超过）
            if (instantaneousPower > 2000f || instantaneousPower < 0f) {
                android.util.Log.w("EllipticalBle", "⚠️ InstPower ${instantaneousPower}W out of [0,2000], clamping to prev: ${currentData.power}")
                instantaneousPower = currentData.power
            }
            if (averagePower > 2000f || averagePower < 0f) {
                averagePower = currentData.avgPower
            }

            // 会话累加逻辑：热量
            var sessionCalories = 0f
            if (totalEnergyKcal > 0) {
                if (initialEnergyKcal == null) initialEnergyKcal = totalEnergyKcal
                sessionCalories = totalEnergyKcal - (initialEnergyKcal ?: 0f)
            } else if (isLocalExerciseRunning && stepPerMinute > 0) {
                // 如果设备不支持热量或发0，本地按步数简单估算
                sessionCalories = currentData.calories + (stepPerMinute * timeDelta * 0.002f)
            } else {
                sessionCalories = currentData.calories
            }

            // 会话累加逻辑：距离
            var sessionDistance = 0f
            if (totalDistanceMeters > 0) {
                if (initialDistanceMeters == null) initialDistanceMeters = totalDistanceMeters
                sessionDistance = totalDistanceMeters - (initialDistanceMeters ?: 0f)
            } else {
                sessionDistance = currentData.distance * 1000f
            }

            // 会话累加逻辑：步数
            var sessionStride = 0
            if (strideCount > 0) {
                if (initialStrideCount == null) initialStrideCount = strideCount
                sessionStride = strideCount - (initialStrideCount ?: 0)
            } else {
                sessionStride = currentData.strideCount
            }

            // 计算本地运动时长（如果设备没有发送 elapsed time）
            val durationSeconds = if (elapsedSeconds > 0) {
                elapsedSeconds.toLong()
            } else if (isLocalExerciseRunning) {
                (currentTime - localExerciseStartTime) / 1000
            } else {
                currentData.duration / 1000
            }

            // 判断是否正在运动（基于踏频或速度）
            val isActive = stepPerMinute > 0 || instantaneousSpeed > 0.5f
            if (isActive && !currentData.isRunning) {
                // 自动检测到开始运动
                if (!isLocalExerciseRunning) {
                    localExerciseStartTime = currentTime - durationSeconds * 1000
                    isLocalExerciseRunning = true
                }
            }

            _exerciseData.value = ExerciseData(
                cadence = stepPerMinute,
                avgCadence = if (averageStepRate > 0) averageStepRate else currentData.avgCadence,
                resistance = resistanceLevel,
                calories = sessionCalories,
                duration = durationSeconds * 1000,
                distance = sessionDistance / 1000f,  // 米转公里
                power = instantaneousPower,
                avgPower = if (averagePower > 0) averagePower else currentData.avgPower,
                speed = instantaneousSpeed,
                avgSpeed = if (averageSpeed > 0) averageSpeed else currentData.avgSpeed,
                heartRate = heartRate,
                strideCount = sessionStride,
                isRunning = isActive || isLocalExerciseRunning,
                movementDirection = movementDirection,
                timestamp = currentTime
            )

        } catch (e: Exception) {
            android.util.Log.e("EllipticalBle", "❌ parseCrossTrainerData error: ${e.message}", e)
        }
    }

    /**
     * 解析心率数据 (标准 HRP 格式, 0x2A37)
     */
    private fun parseHeartRateData(rawData: ByteArray) {
        try {
            if (rawData.isEmpty()) return

            // Heart Rate Measurement Format
            // Byte 0: flags
            // bit 0: HR format (0=uint8, 1=uint16)
            // bit 3: sensor contact
            // bit 4: contact supported
            // bit 5: energy expended present
            // bit 6: RR-Interval present
            val flags = rawData[0].toInt() and 0xFF
            val is16Bit = (flags and 0x01) != 0
            val hrOffset = 1
            var hrValue = 0

            if (is16Bit && rawData.size >= hrOffset + 2) {
                hrValue = rawData[hrOffset].toInt() and 0xFF or
                        ((rawData[hrOffset + 1].toInt() and 0xFF) shl 8)
            } else if (!is16Bit && rawData.size > hrOffset) {
                hrValue = rawData[hrOffset].toInt() and 0xFF
            }

            if (hrValue > 0) {
                _exerciseData.value = _exerciseData.value.copy(heartRate = hrValue)
            }
        } catch (e: Exception) {
            // 忽略心率解析错误
        }
    }

    /**
     * 清理资源
     */
    fun release() {
        disconnect()
    }
}
