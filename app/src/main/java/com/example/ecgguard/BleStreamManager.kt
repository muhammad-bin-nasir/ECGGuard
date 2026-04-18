package com.example.ecgguard

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
    private val TARGET_SERVICE_UUID = BleProfile.serviceUuid
    private val TARGET_CHAR_UUID = BleProfile.characteristicUuid
    private val CONFIG_DESC = BleProfile.clientConfigDescriptorUuid

    private val signalBuffer = ArrayList<Float>()
    private val REQUIRED_SIZE = 2500
    private val MAX_BUFFER_SIZE = 3000

    private var firstPacketTime = 0L
    private var lastPacketTime = 0L
    private var packetCount = 0
    private var discoveryRetried = false
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private fun log(msg: String) {
        Log.d("ECG_DEBUG", msg)
        onLog(msg)
    }

    private fun stopScanSafely() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            log("Cache refresh skipped: ${e.message}")
            false
        }
    }

    fun connect() {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth disabled!")
            return
        }

        cancelScanTimeout()
        stopScanSafely()
        gatt?.close()
        gatt = null
        discoveryRetried = false

        log("Scanning for ECGGuard_BLE...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner.startScan(null, settings, scanCallback)

        scanTimeoutRunnable = Runnable {
            if (gatt == null) {
                stopScanSafely()
                log("Scan timeout. ESP32 not found.")
            }
        }
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable!!, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return

            // Check both standard name and ScanRecord name (Bypasses Android 12 null bugs)
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"

            // LOG EVERY DEVICE WE SEE so you can check Logcat if it fails
            Log.d("ECG_SCAN", "Found: $name | MAC: ${device.address}")

            // Accept the current ESP32 name and legacy names.
            val isNameMatch = BleProfile.knownDeviceNames.contains(name)

            // Case-insensitive UUID match
            val isUuidMatch = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(TARGET_SERVICE_UUID.toString(), ignoreCase = true)
            } == true

            if (isNameMatch || isUuidMatch) {
                cancelScanTimeout()
                stopScanSafely()
                log("TARGET FOUND ($name)! Connecting...")
                gatt = device.connectGatt(context, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("SCAN CRASHED! Error Code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Connection error. Status: $status")
                gatt.close()
                this@BleStreamManager.gatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelScanTimeout()
                this@BleStreamManager.gatt = gatt
                discoveryRetried = false
                log("Connected. Refreshing cache...")
                refreshDeviceCache(gatt)
                Handler(Looper.getMainLooper()).postDelayed({
                    log("Requesting MTU 512...")
                    val requested = gatt.requestMtu(512)
                    if (!requested) {
                        log("MTU request not supported. Discovering services...")
                        gatt.discoverServices()
                    }
                }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelScanTimeout()
                log("Disconnected.")
                gatt.close()
                this@BleStreamManager.gatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("MTU changed to $mtu. Discovering services...")
            } else {
                log("MTU change failed (status $status). Discovering services...")
            }
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed. Status: $status")
                return
            }

            val discoveredServices = gatt.services.joinToString { it.uuid.toString() }
            log("Services discovered: $discoveredServices")

            val service = gatt.getService(TARGET_SERVICE_UUID)
            val charac = service?.getCharacteristic(TARGET_CHAR_UUID)

            if (charac != null) {
                log("Service found. Notifications enabled.")
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
                } else {
                    log("Error: Notification descriptor missing on characteristic.")
                }
                firstPacketTime = 0L
                packetCount = 0
                signalBuffer.clear()
            } else if (!discoveryRetried) {
                discoveryRetried = true
                log("Target service/characteristic not found. Retrying discovery...")
                refreshDeviceCache(gatt)
                Handler(Looper.getMainLooper()).postDelayed({ gatt.discoverServices() }, 800)
            } else {
                val serviceState = if (service == null) "service missing" else "characteristic missing"
                log("Error: BLE target not found after retry ($serviceState).")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processBytes(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processBytes(value)
        }

        private fun processBytes(data: ByteArray) {
            if (data.isEmpty()) return

            val now = System.currentTimeMillis()
            if (firstPacketTime == 0L) firstPacketTime = now
            lastPacketTime = now
            packetCount++

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toFloat()
                signalBuffer.add(sample)
            }

            if (signalBuffer.size >= REQUIRED_SIZE) {
                val inputData = signalBuffer.takeLast(REQUIRED_SIZE).toFloatArray()
                onDataReceived(inputData, "Pkt #$packetCount | Buffer: ${signalBuffer.size}")

                if (signalBuffer.size > MAX_BUFFER_SIZE) {
                    signalBuffer.subList(0, 250).clear()
                }
            }
        }
    }
}