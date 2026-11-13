package com.funnyass.test.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.funnyass.test.Logger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生 BLE 管理：扫描 → 连接 → 打开 FF01 通知 → FF02 写。
 * 线上帧 = '#' + 二进制帧HEX(ASCII) + 0x0A（见 FrameUtils）。
 *
 * 稳定性要点（相比 0.1919）：
 *  - 每次连接前必定先 disconnect/close 旧 GATT，避免客户端槽位泄漏（「点几次就再也连不上」的根因）
 *  - 连接 15s 超时 + 自动重试 1 次；失败时把 GATT status 原样带出来
 *  - 扫描结果带上广播名（scanRecord.deviceName），不再只依赖 BluetoothDevice.name
 */
@SuppressLint("MissingPermission")
class BleManager(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 状态码（BathActivity 按这些值渲染六态）
        const val S_CONNECTING = 1     // 正在连接
        const val S_LINKED = 2         // 链路已通，发现服务中
        const val S_READY = 3          // 通知已开，可以收发
        const val S_DISCONNECTED = 4   // 已断开
        const val S_ERROR = 5          // 本次会话错误（服务/描述符/参数）
        const val S_FAILED = 6         // 重试后仍失败（附 status）
        const val S_RETRYING = 7       // 正在退避重试
        const val S_SCANNING = 10
        const val S_SCAN_DONE = 11

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1_500L
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    interface Listener {
        fun onScanResult(device: BluetoothDevice, rssi: Int, name: String?)
        fun onStateChanged(state: Int, msg: String)
        fun onFrame(cmd: Int, payload: ByteArray)   // 收到设备回帧（二进制帧已拆好）
        fun onWriteDone(cmd: Int, success: Boolean)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val rxBuffer = java.io.ByteArrayOutputStream()
    private val writeLock = Any()
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var lastWriteStatus = BluetoothGatt.GATT_FAILURE

    // 连接状态机
    @Volatile private var connecting = false
    @Volatile private var attempt = 0
    @Volatile private var lastMac: String? = null

    val isEnabled: Boolean get() = adapter?.isEnabled == true
    val isConnected: Boolean get() = gatt != null && !connecting

    private fun emit(state: Int, msg: String) {
        Logger.log("BLE 状态 " + state + ": " + msg)
        main.post { listener?.onStateChanged(state, msg) }
    }

    // ---------------- 扫描 ----------------

    private val scanTimeout = Runnable {
        stopScan()
        emit(S_SCAN_DONE, "扫描完成")
    }

    fun startScan() {
        val a = adapter
        val scanner = a?.bluetoothLeScanner
        if (a == null || scanner == null) {
            emit(S_ERROR, "蓝牙未开启")
            return
        }
        stopScan()
        try {
            scanner.startScan(scanCallback)
            Logger.log("BLE 开始扫描（" + (SCAN_TIMEOUT_MS / 1000) + "s）")
            emit(S_SCANNING, "扫描中…")
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Logger.log("扫描启动失败: " + e.message)
            emit(S_ERROR, "扫描启动失败")
        }
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            // 广播里的名字优先：BluetoothDevice.name 只是系统缓存，未连接时经常为空
            val advertised = try {
                result.scanRecord?.deviceName
            } catch (_: Exception) {
                null
            }
            val cached = try {
                dev.name
            } catch (_: Exception) {
                null
            }
            val name = advertised?.takeIf { it.isNotBlank() }
                ?: cached?.takeIf { it.isNotBlank() }
            main.post { listener?.onScanResult(dev, result.rssi, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.log("扫描失败 errorCode=" + errorCode)
            emit(S_ERROR, "扫描失败 " + errorCode)
        }
    }

    // ---------------- 连接 ----------------

    fun connect(mac: String) {
        val a = adapter ?: return
        val target = mac.trim()
        if (target.isEmpty()) {
            emit(S_ERROR, "无效 MAC")
            return
        }
        stopScan()
        lastMac = target
        attempt = 0
        connecting = true
        val dev = try {
            a.getRemoteDevice(target)
        } catch (e: Exception) {
            null
        }
        if (dev == null) {
            connecting = false
            emit(S_ERROR, "无效 MAC")
            return
        }
        startAttempt(dev)
    }

    private fun startAttempt(dev: BluetoothDevice) {
        closeGatt()
        attempt++
        val n = attempt
        Logger.log("BLE 连接尝试 " + n + "/" + MAX_ATTEMPTS + " mac=" + dev.address)
        emit(if (n == 1) S_CONNECTING else S_RETRYING, "连接中…（第 " + n + " 次）")
        try {
            gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Logger.log("connectGatt 异常: " + e.message)
            gatt = null
            retryOrFail("connectGatt 异常")
            return
        }
        if (gatt == null) {
            retryOrFail("connectGatt 返回 null")
            return
        }
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (connecting) {
            Logger.log("BLE 连接超时（" + CONNECT_TIMEOUT_MS + "ms）")
            closeGatt()
            retryOrFail("连接超时")
        }
    }

    private fun retryOrFail(reason: String) {
        if (attempt < MAX_ATTEMPTS) {
            val next = attempt + 1
            emit(S_RETRYING, "第 " + attempt + " 次失败（" + reason + "），准备第 " + next + " 次")
            main.postDelayed({
                if (!connecting) return@postDelayed
                val dev = try {
                    lastMac?.let { adapter?.getRemoteDevice(it) }
                } catch (_: Exception) {
                    null
                }
                if (dev == null) {
                    connecting = false
                    emit(S_FAILED, "连接失败：" + reason)
                } else {
                    startAttempt(dev)
                }
            }, RETRY_DELAY_MS)
        } else {
            connecting = false
            emit(S_FAILED, "连接失败：" + reason)
        }
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        val g = gatt ?: run {
            writeChar = null
            return
        }
        gatt = null
        writeChar = null
        try {
            g.disconnect()
        } catch (_: Exception) {
        }
        try {
            g.close()
        } catch (_: Exception) {
        }
        Logger.log("BLE 旧连接已关闭")
    }

    fun disconnect() {
        connecting = false
        attempt = 0
        closeGatt()
        rxBuffer.reset()
    }

    // ---------------- 收发 ----------------

    /** 发送命令：cmd + data，自动组帧并拆 20B/片写入 */
    fun send(cmd: Int, data: ByteArray = ByteArray(0)) {
        val wire = FrameUtils.cmdToWire(cmd, data)
        writeWire(cmd, wire)
    }

    fun sendRawHex(cmd: Int, downDataHex: String) {
        val data = FrameUtils.fromHex(downDataHex) ?: ByteArray(0)
        send(cmd, data)
    }

    private fun writeWire(cmd: Int, wire: ByteArray) {
        val c = writeChar
        val g = gatt
        if (c == null || g == null) {
            Logger.log("写特征为空，丢帧 cmd=0x" + Integer.toHexString(cmd))
            main.post { listener?.onWriteDone(cmd, false) }
            return
        }
        // 按 20B/片写，片间 20ms
        Thread {
            synchronized(writeLock) {
                Logger.log("BLE TX wire=" + FrameUtils.toHex(wire))
                var off = 0
                while (off < wire.size) {
                    val len = minOf(20, wire.size - off)
                    val chunk = wire.copyOfRange(off, off + len)
                    val latch = CountDownLatch(1)
                    writeLatch = latch
                    lastWriteStatus = BluetoothGatt.GATT_FAILURE
                    c.value = chunk
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val accepted = g.writeCharacteristic(c)
                    Logger.log("BLE TX chunk=" + FrameUtils.toHex(chunk) + " accepted=" + accepted)
                    if (!accepted || !latch.await(3, TimeUnit.SECONDS) || lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
                        writeLatch = null
                        main.post { listener?.onWriteDone(cmd, false) }
                        return@synchronized
                    }
                    writeLatch = null
                    off += len
                }
                main.post { listener?.onWriteDone(cmd, true) }
            }
        }.start()
    }

    // ---------------- GATT 回调 ----------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.removeCallbacks(connectTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.log("GATT 失败 status=" + status + " newState=" + newState)
                closeGatt()
                if (connecting) retryOrFail("status " + status)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Logger.log("GATT 链路已连接，开始发现服务")
                    emit(S_LINKED, "已连接，发现服务…")
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Exception) {
                    }
                    try {
                        g.discoverServices()
                    } catch (e: Exception) {
                        Logger.log("discoverServices 异常: " + e.message)
                        closeGatt()
                        emit(S_ERROR, "发现服务失败")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Logger.log("GATT 已断开 status=" + status)
                    closeGatt()
                    connecting = false
                    emit(S_DISCONNECTED, "已断开")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.log("发现服务失败 status=" + status)
                closeGatt()
                emit(S_ERROR, "发现服务失败 " + status)
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val notify = svc?.getCharacteristic(NOTIFY_UUID)
            writeChar = svc?.getCharacteristic(WRITE_UUID)
            if (svc == null || notify == null || writeChar == null) {
                Logger.log("未找到 FF00/FF01/FF02 服务")
                closeGatt()
                emit(S_ERROR, "未找到 FF00/FF01/FF02 服务")
                return
            }
            g.setCharacteristicNotification(notify, true)
            val desc = notify.getDescriptor(CCCD_UUID)
            if (desc == null) {
                Logger.log("未找到 CCCD 描述符")
                closeGatt()
                emit(S_ERROR, "未找到 CCCD 描述符")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
            // 等 onDescriptorWrite 成功后再发 state 3
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                attempt = 0
                Logger.log("通知已开启，链路可用")
                emit(S_READY, "通知已开启，等待设备回帧")
            } else {
                Logger.log("通知开启失败 status=" + status)
                closeGatt()
                emit(S_ERROR, "通知开启失败 status=" + status)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            Logger.log("BLE RX raw=" + FrameUtils.toHex(bytes))
            rxBuffer.write(bytes)
            drainRx()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Logger.log("BLE TX result status=" + status)
            lastWriteStatus = status
            writeLatch?.countDown()
        }
    }

    private fun drainRx() {
        val all = rxBuffer.toByteArray()
        val idx = all.indexOfLast { it.toInt() == 0x0A }
        if (idx < 0) return
        val frameBytes = all.copyOfRange(0, idx + 1)
        rxBuffer.reset()
        rxBuffer.write(all, idx + 1, all.size - idx - 1)
        val frame = FrameUtils.parseWire(frameBytes)
        if (frame != null) {
            val cmd = frame[4].toInt() and 0xFF
            val payload = FrameUtils.parsePayload(frame) ?: ByteArray(0)
            main.post { listener?.onFrame(cmd, payload) }
        } else {
            Logger.log("帧解析失败 raw=" + FrameUtils.toHex(frameBytes))
        }
    }
}