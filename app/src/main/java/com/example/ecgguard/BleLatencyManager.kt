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

@SuppressLint("MissingPermission")
class BleLatencyManager(private val context: Context, private val onLog: (String) -> Unit) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    // --- CONFIGURATION (MUST MATCH ESP32) ---
    // ESP32: ...14a
    private val TARGET_SERVICE_UUID_STR = "4fafc201-1fb5-459e-8fcc-c5c9c331914a"
    // ESP32: ...6a5
    private val TARGET_CHAR_UUID_STR    = "beb5483e-36e1-4688-b7f5-ea07361b26a5"

    private val SERVICE_UUID = UUID.fromString(TARGET_SERVICE_UUID_STR)
    private val CHAR_UUID    = UUID.fromString(TARGET_CHAR_UUID_STR)
    private val CONFIG_DESC  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var startTime = 0L
    private var scanTimeoutHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private fun log(msg: String) {
        Log.d("ECG_DEBUG", msg)
        onLog(msg)
    }

    fun connectAndTest() {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth disabled!")
            return
        }

        log("Scanning for ESP32 (looking for ID ending in ...4a)...")
        adapter.bluetoothLeScanner.startScan(scanCallback)

        scanTimeoutRunnable = Runnable {
            if (gatt == null) {
                try {
                    adapter.bluetoothLeScanner.stopScan(scanCallback)
                    log("Scan timeout. Device not found. (Check power/GPS)")
                } catch (e: Exception) { }
            }
        }
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable!!, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: "NULL"

            val isNameMatch = name == "ECG_LATENCY_TEST" || name == "FYP-Test-Heartbeat"

            // Loose UUID match to find it even if cache is stale
            val records = result.scanRecord?.serviceUuids
            val isUuidMatch = records?.any {
                it.uuid.toString().startsWith("4fafc201")
            } == true

            if (isNameMatch || isUuidMatch) {
                log("TARGET FOUND: $name")

                scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
                adapter.bluetoothLeScanner.stopScan(this)
                device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected! Refreshing Cache...")

                // --- THE CACHE BUSTER ---
                // Force Android to forget the old services
                refreshDeviceCache(gatt)

                // Small delay to let the refresh happen
                Handler(Looper.getMainLooper()).postDelayed({
                    log("Discovering Services...")
                    gatt.discoverServices()
                }, 1000) // Wait 1 second before discovering

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected. Status: $status")
                this@BleLatencyManager.gatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("--- SERVICE DISCOVERY COMPLETE ---")

            var targetServiceFound = false
            var targetCharFound = false

            gatt.services.forEach { service ->
                // LOG ALL SERVICES FOUND (So we can see if it's finding the old one)
                if (service.uuid == SERVICE_UUID) {
                    targetServiceFound = true
                    log("✅ FOUND TARGET SERVICE: ...${service.uuid.toString().takeLast(4)}")

                    service.characteristics.forEach { char ->
                        if (char.uuid == CHAR_UUID) {
                            targetCharFound = true
                            log("  ✅ FOUND TARGET CHAR: ...${char.uuid.toString().takeLast(4)}")
                            enableNotifications(gatt, char)
                        } else {
                            log("  - Found other char: ${char.uuid}")
                        }
                    }
                } else {
                    // PRINT THE IGNORED ONES (Debugging)
                    log("⚠️ Ignored Service: ...${service.uuid.toString().takeLast(4)}")
                }
            }

            if (!targetServiceFound) log("❌ ERROR: Target Service (...4a) NOT found!")
            if (targetServiceFound && !targetCharFound) log("❌ ERROR: Service found, but Char (...a5) missing!")
        }

        private fun enableNotifications(gatt: BluetoothGatt, charac: BluetoothGattCharacteristic) {
            log("Subscribing to notifications...")
            this@BleLatencyManager.gatt = gatt
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
                log("Error: Config Descriptor missing on ESP32!")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications Active. Pinging in 500ms...")
                Handler(Looper.getMainLooper()).postDelayed({ sendPing() }, 500)
            } else {
                log("Descriptor Write Failed: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processPong(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processPong(value)
        }

        private fun processPong(value: ByteArray) {
            val str = String(value)
            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime
            log("RECV: '$str' | RTT: ${latency}ms")

            Handler(Looper.getMainLooper()).postDelayed({ sendPing() }, 1000)
        }

        // --- HIDDEN API MAGIC ---
        private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
            try {
                val localMethod = gatt.javaClass.getMethod("refresh")
                if (localMethod != null) {
                    return localMethod.invoke(gatt) as Boolean
                }
            } catch (localException: Exception) {
                log("Cache refresh failed: $localException")
            }
            return false
        }
    }

    fun sendPing() {
        val charac = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
        if (charac == null) {
            log("Ping Failed: Char is null")
            return
        }

        val payload = "PING".toByteArray()
        startTime = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(charac, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            charac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            charac.value = payload
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(charac)
        }
    }
}