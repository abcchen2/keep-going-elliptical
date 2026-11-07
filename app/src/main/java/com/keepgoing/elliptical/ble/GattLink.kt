package com.keepgoing.elliptical.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Thin wrapper around [BluetoothGatt] with a strictly serialized operation queue.
 *
 * Android only allows one outstanding GATT operation at a time; a second
 * writeCharacteristic() issued before the first callback returns `false` and the
 * command is silently lost. The official Mobi app serializes everything through
 * `BleGattMangerQueue`; this class does the same (one op in flight, per-op timeout).
 *
 * All public methods and all [Listener] callbacks run on the main thread.
 */
@SuppressLint("MissingPermission")
class GattLink(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLinkConnected()
        /** Services discovered and MTU negotiated; protocol setup can start. */
        fun onLinkReady()
        fun onLinkDisconnected(status: Int, userRequested: Boolean)
        fun onNotify(characteristic: UUID, value: ByteArray)
        fun onLog(line: String)
    }

    private sealed class Op(val label: String) {
        class Mtu(val mtu: Int, val done: (Boolean) -> Unit) : Op("MTU $mtu")
        class Read(val ch: BluetoothGattCharacteristic, val done: (ByteArray?) -> Unit) :
            Op("READ ${ch.uuid.shortName()}")
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray, val done: ((Boolean) -> Unit)?) :
            Op("WRITE ${ch.uuid.shortName()} [${data.toHex()}]")
        class Cccd(val ch: BluetoothGattCharacteristic, val desc: BluetoothGattDescriptor, val value: ByteArray, val done: ((Boolean) -> Unit)?) :
            Op("CCCD ${ch.uuid.shortName()}")
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var gatt: BluetoothGatt? = null
    private var userDisconnect = false
    private var ready = false

    var mtu: Int = 23
        private set

    val isConnected: Boolean get() = gatt != null && ready

    private val timeoutRunnable = Runnable {
        val op = current ?: return@Runnable
        log("超时: ${op.label}")
        finish(op, success = false, value = null)
    }

    // ---------------------------------------------------------------- connection

    fun connect(device: BluetoothDevice) {
        closeGatt()
        userDisconnect = false
        ready = false
        log("连接 ${device.address} ...")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        userDisconnect = true
        clearQueue()
        g.disconnect()
        // Some stacks never deliver the disconnect callback; make sure we clean up.
        main.postDelayed({
            if (gatt === g) handleDisconnected(BluetoothGatt.GATT_SUCCESS)
        }, 1500)
    }

    private fun closeGatt() {
        clearQueue()
        gatt?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        ready = false
    }

    private fun handleDisconnected(status: Int) {
        val wasUser = userDisconnect
        closeGatt()
        listener.onLinkDisconnected(status, wasUser)
    }

    // ---------------------------------------------------------------- lookup

    val services: List<BluetoothGattService> get() = gatt?.services.orEmpty()

    fun hasService(uuid: UUID): Boolean = gatt?.getService(uuid) != null

    fun characteristic(service: UUID, ch: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(ch)

    /** First characteristic with this UUID in any service. */
    fun findCharacteristic(ch: UUID): BluetoothGattCharacteristic? =
        services.firstNotNullOfOrNull { it.getCharacteristic(ch) }

    // ---------------------------------------------------------------- operations

    fun requestMtu(value: Int, done: (Boolean) -> Unit = {}) = enqueue(Op.Mtu(value, done))

    fun read(ch: BluetoothGattCharacteristic, done: (ByteArray?) -> Unit) = enqueue(Op.Read(ch, done))

    fun write(ch: BluetoothGattCharacteristic, data: ByteArray, done: ((Boolean) -> Unit)? = null) =
        enqueue(Op.Write(ch, data.copyOf(), done))

    /** Number of writes for [ch] waiting in the queue (used to avoid piling up heartbeat frames). */
    fun pendingWrites(ch: UUID): Int =
        queue.count { it is Op.Write && it.ch.uuid == ch } + if ((current as? Op.Write)?.ch?.uuid == ch) 1 else 0

    /**
     * Enables notifications, or indications if the characteristic only supports indicate.
     * [done] receives `true` once the CCCD write succeeded.
     */
    fun enableNotify(ch: BluetoothGattCharacteristic, done: ((Boolean) -> Unit)? = null) {
        val g = gatt ?: run { done?.invoke(false); return }
        val props = ch.properties
        val useIndicate = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!g.setCharacteristicNotification(ch, true)) {
            log("setCharacteristicNotification 失败: ${ch.uuid.shortName()}")
        }
        val desc = ch.getDescriptor(BleUuids.CCCD)
        if (desc == null) {
            log("${ch.uuid.shortName()} 没有 CCCD，直接视为已订阅")
            main.post { done?.invoke(true) }
            return
        }
        val value = if (useIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        enqueue(Op.Cccd(ch, desc, value, done))
    }

    private fun enqueue(op: Op) {
        if (gatt == null) {
            log("未连接，丢弃: ${op.label}")
            fail(op)
            return
        }
        queue.addLast(op)
        pump()
    }

    private fun clearQueue() {
        main.removeCallbacks(timeoutRunnable)
        current = null
        queue.clear()
    }

    private fun pump() {
        if (current != null) return
        val op = queue.removeFirstOrNull() ?: return
        val g = gatt ?: run { fail(op); queue.clear(); return }
        current = op
        val started = try {
            when (op) {
                is Op.Mtu -> g.requestMtu(op.mtu)
                is Op.Read -> g.readCharacteristic(op.ch)
                is Op.Write -> writeCharacteristic(g, op.ch, op.data)
                is Op.Cccd -> writeDescriptor(g, op.desc, op.value)
            }
        } catch (e: SecurityException) {
            log("权限不足: ${e.message}")
            false
        }
        if (!started) {
            log("发起失败: ${op.label}")
            main.post { finish(op, success = false, value = null) }
            return
        }
        main.postDelayed(timeoutRunnable, OP_TIMEOUT_MS)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        // Keep the write type the stack picked from the characteristic properties
        // (write-without-response if supported), exactly like FastBle in the official app.
        val type = ch.writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = data
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, desc: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
        } else {
            desc.value = value
            g.writeDescriptor(desc)
        }

    private fun finish(op: Op, success: Boolean, value: ByteArray?) {
        if (current !== op) return
        main.removeCallbacks(timeoutRunnable)
        current = null
        when (op) {
            is Op.Mtu -> op.done(success)
            is Op.Read -> op.done(if (success) value else null)
            is Op.Write -> {
                if (!success) log("写入失败: ${op.label}")
                op.done?.invoke(success)
            }
            is Op.Cccd -> {
                if (!success) log("订阅失败: ${op.label}")
                op.done?.invoke(success)
            }
        }
        pump()
    }

    private fun fail(op: Op) {
        when (op) {
            is Op.Mtu -> op.done(false)
            is Op.Read -> op.done(null)
            is Op.Write -> op.done?.invoke(false)
            is Op.Cccd -> op.done?.invoke(false)
        }
    }

    private fun log(line: String) = listener.onLog(line)

    // ---------------------------------------------------------------- callbacks (binder thread)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            log("连接异常 status=$status")
                            handleDisconnected(status)
                            return@post
                        }
                        listener.onLinkConnected()
                        // A short delay before discovery avoids GATT 133 on several Chinese BLE modules.
                        main.postDelayed({
                            if (g === gatt && !g.discoverServices()) {
                                log("discoverServices 发起失败")
                                g.disconnect()
                            }
                        }, 400)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("服务发现失败 status=$status")
                    g.disconnect()
                    return@post
                }
                for (s in g.services) {
                    log("服务 ${s.uuid.shortName()}: " + s.characteristics.joinToString { c ->
                        c.uuid.shortName() + "(" + propsText(c.properties) + ")"
                    })
                }
                // The new official app (4.5.x) negotiates MTU 128 before talking to the machine.
                requestMtu(128) { ok ->
                    log(if (ok) "MTU=$mtu" else "MTU 协商失败，继续使用默认值")
                    ready = true
                    listener.onLinkReady()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
                (current as? Op.Mtu)?.let { finish(it, status == BluetoothGatt.GATT_SUCCESS, null) }
            }
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.copyOf()
            main.post { completeRead(ch, copy, status) }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val copy = ch.value?.copyOf() ?: ByteArray(0)
            main.post { completeRead(ch, copy, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                val op = current as? Op.Write ?: return@post
                if (op.ch.uuid == ch.uuid) finish(op, status == BluetoothGatt.GATT_SUCCESS, null)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                val op = current as? Op.Cccd ?: return@post
                if (op.desc.uuid == d.uuid && op.ch.uuid == d.characteristic.uuid) {
                    finish(op, status == BluetoothGatt.GATT_SUCCESS, null)
                }
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            val id = ch.uuid
            main.post { if (g === gatt) listener.onNotify(id, copy) }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val copy = ch.value?.copyOf() ?: return
            val id = ch.uuid
            main.post { if (g === gatt) listener.onNotify(id, copy) }
        }
    }

    private fun completeRead(ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val op = current as? Op.Read ?: return
        if (op.ch.uuid == ch.uuid) finish(op, status == BluetoothGatt.GATT_SUCCESS, value)
    }

    companion object {
        private const val OP_TIMEOUT_MS = 3000L

        fun propsText(p: Int): String = buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WnR")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
        }.joinToString("/")
    }
}
