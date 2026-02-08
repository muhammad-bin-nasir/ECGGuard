package com.example.ecgguard

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("MissingPermission")
class BleStreamManager(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onDataReceived: (FloatArray, String) -> Unit
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    // --- CONFIGURATION ---
    private val TARGET_SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914a")
    private val TARGET_CHAR_UUID    = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a5")
    private val CONFIG_DESC         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- STREAMING VARIABLES ---
    private val signalBuffer = ArrayList<Float>()

    // CRITICAL FIX: Model requires 2500 samples (10 seconds @ 250Hz)
    private val REQUIRED_SIZE = 2500

    // Keep a slightly larger buffer to allow for sliding window (e.g., 12 seconds)
    private val MAX_BUFFER_SIZE = 3000

    // --- LATENCY METRICS ---
    private var firstPacketTime = 0L
    private var lastPacketTime = 0L
    private var packetCount = 0

    private fun log(msg: String) {
        Log.d("ECG_DEBUG", msg)
        onLog(msg)
    }

    fun connect() {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth disabled!")
            return
        }
        log("Scanning for Streamer...")
        adapter.bluetoothLeScanner.startScan(scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            if (gatt == null) {
                try {
                    adapter.bluetoothLeScanner.stopScan(scanCallback)
                    log("Scan timeout. ESP32 not found.")
                } catch (e: Exception) {}
            }
        }, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: "NULL"

            val isNameMatch = name == "ECG_STREAMER"
            val isUuidMatch = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString() == TARGET_SERVICE_UUID.toString()
            } == true

            if (isNameMatch || isUuidMatch) {
                log("TARGET FOUND ($name)! Connecting...")
                adapter.bluetoothLeScanner.stopScan(this)
                device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected. Requesting MTU 512...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected.")
                this@BleStreamManager.gatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            log("MTU Changed to: $mtu. Discovering Services...")
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val charac = gatt.getService(TARGET_SERVICE_UUID)?.getCharacteristic(TARGET_CHAR_UUID)
            if (charac != null) {
                log("Service Found! Stream Starting (Wait 10s)...")
                this@BleStreamManager.gatt = gatt

                gatt.setCharacteristicNotification(charac, true)
                val desc = charac.getDescriptor(CONFIG_DESC)
                if (desc != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                }

                firstPacketTime = 0L
                packetCount = 0
                signalBuffer.clear()
            } else {
                log("Error: Target Characteristic not found!")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processBytes(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processBytes(value)
        }

        private fun processBytes(data: ByteArray) {
            val now = System.currentTimeMillis()
            if (firstPacketTime == 0L) firstPacketTime = now
            lastPacketTime = now
            packetCount++

            // Convert Bytes -> Floats
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toFloat()
                signalBuffer.add(sample)
            }

            // CHECK BUFFER SIZE
            // FIX: Wait for 2500 samples (10 seconds) because the Model demands it
            if (signalBuffer.size >= REQUIRED_SIZE) {

                // Grab the LAST 2500 samples
                val inputData = signalBuffer.takeLast(REQUIRED_SIZE).toFloatArray()

                val msg = "Pkt #$packetCount | Buffer: ${signalBuffer.size}"
                onDataReceived(inputData, msg)

                // Keep buffer size managed
                if (signalBuffer.size > MAX_BUFFER_SIZE) {
                    // Remove old data to slide the window (Remove 1 sec = 250 samples)
                    signalBuffer.subList(0, 250).clear()
                }
            }
        }
    }
}