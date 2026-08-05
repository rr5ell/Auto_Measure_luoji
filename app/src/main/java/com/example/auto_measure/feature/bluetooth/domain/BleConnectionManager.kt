package com.example.auto_measure.feature.bluetooth.domain

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.example.auto_measure.feature.bluetooth.data.BleCommand
import com.example.auto_measure.feature.bluetooth.data.BleDevice
import com.example.auto_measure.feature.bluetooth.data.DistanceData
import java.util.UUID

/**
 * BLE 连接管理器
 *
 * 负责扫描、连接、AT 命令收发
 * 设备：SWR005 "Mate X" 激光测距仪
 */
class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConn"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val DEVICE_PREFIX = "Mate X"
    }

    enum class State {
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
    }

    var state: State by mutableStateOf(State.IDLE)
        private set
    var connectedDevice: BleDevice? by mutableStateOf(null)
        private set
    var lastDistance: DistanceData? by mutableStateOf(null, neverEqualPolicy())
        private set
    var batteryLevel: Int by mutableIntStateOf(-1)
        private set

    var onStateChanged: ((State) -> Unit)? = null
    var onDeviceFound: ((BleDevice) -> Unit)? = null
    var onDistanceReceived: ((DistanceData) -> Unit)? = null
    var onDataReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private val discoveredDevices = mutableListOf<BleDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith(DEVICE_PREFIX)) return
            val addr = result.device.address
            if (discoveredDevices.any { it.address == addr }) return
            val device = BleDevice(name, addr, result.rssi)
            discoveredDevices.add(device)
            onDeviceFound?.invoke(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败: $errorCode")
            updateState(State.IDLE)
            onError?.invoke("扫描失败: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "已连接, 正在发现服务...")
                updateState(State.CONNECTED)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "已断开")
                cleanup()
                updateState(State.IDLE)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "服务发现失败: $status")
                onError?.invoke("服务发现失败")
                return
            }
            findCharacteristics(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            val text = String(data, Charsets.UTF_8)
            Log.d(TAG, "接收: $text")
            onDataReceived?.invoke(text)
            parseResponse(text)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "写入失败: $status, 尝试无响应写入")
                tryWriteWithoutResponse(characteristic)
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (state == State.SCANNING) return
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) { onError?.invoke("蓝牙不可用"); return }
        discoveredDevices.clear()
        updateState(State.SCANNING)
        val filters = listOf(ScanFilter.Builder().build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(filters, settings, scanCallback)
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
        Log.d(TAG, "开始扫描")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (state != State.SCANNING) return
        scanner?.stopScan(scanCallback)
        updateState(State.IDLE)
        Log.d(TAG, "停止扫描")
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BleDevice) {
        stopScan()
        updateState(State.CONNECTING)
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        if (btDevice == null) { onError?.invoke("设备地址无效"); updateState(State.IDLE); return }
        connectedDevice = device
        gatt = btDevice.connectGatt(context, false, gattCallback)
        Log.d(TAG, "正在连接: ${device.name} (${device.address})")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        updateState(State.DISCONNECTING)
        gatt?.disconnect()
        cleanup()
        updateState(State.IDLE)
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: String) {
        val char = writeCharacteristic
        if (char == null) { Log.w(TAG, "没有写入特征值"); return }
        Log.d(TAG, "发送: $command")
        val bytes = command.toByteArray(Charsets.UTF_8)
        char.value = bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt?.writeCharacteristic(char)
    }

    fun measure() = sendCommand(BleCommand.MEASURE)
    fun stopMeasure() = sendCommand(BleCommand.STOP)
    fun laserOn() = sendCommand(BleCommand.LASER_ON)
    fun laserOff() = sendCommand(BleCommand.LASER_OFF)
    fun queryBattery() = sendCommand(BleCommand.BATTERY)

    @SuppressLint("MissingPermission")
    private fun findCharacteristics(gatt: BluetoothGatt) {
        writeCharacteristic = null
        val notifyChars = mutableListOf<BluetoothGattCharacteristic>()

        for (service in gatt.services) {
            for (char in service.characteristics) {
                val props = char.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    writeCharacteristic = char
                }
                if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    notifyChars.add(char)
                }
            }
        }

        Log.d(TAG, "写入特征值: ${writeCharacteristic?.uuid}")
        Log.d(TAG, "通知特征值: ${notifyChars.map { it.uuid }}")

        for (char in notifyChars) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun tryWriteWithoutResponse(char: BluetoothGattCharacteristic) {
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(char)
    }

    private fun parseResponse(text: String) {
        when {
            text.startsWith("AT+Distance=") -> {
                val content = text.removePrefix("AT+Distance=").removeSuffix("##")
                val parts = content.split(",")
                if (parts.size >= 4) {
                    val data = DistanceData(
                        line = parts[0].toDoubleOrNull() ?: 0.0,
                        comp = parts[1].toDoubleOrNull() ?: 0.0,
                        height = parts[2].toDoubleOrNull() ?: 0.0,
                        flag = parts[3].toIntOrNull() ?: 0,
                    )
                    lastDistance = data
                    onDistanceReceived?.invoke(data)
                }
            }
            text.startsWith("AT+Batt=") -> {
                val content = text.removePrefix("AT+Batt=").removeSuffix("##")
                batteryLevel = content.toIntOrNull() ?: -1
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        writeCharacteristic = null
        handler.post { connectedDevice = null }
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun updateState(newState: State) {
        handler.post {
            state = newState
            onStateChanged?.invoke(newState)
        }
    }
}
