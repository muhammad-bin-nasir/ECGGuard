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

/**
 * BleLatencyManager.kt
 * =====================
 * A diagnostic-only class for measuring BLE round-trip latency (ping/pong)
 * between the phone and the ESP32.
 *
 * THIS CLASS IS NOT USED IN NORMAL ECG MONITORING.
 * It was used during development to verify that BLE communication was fast
 * enough (< 50ms RTT) for real-time ECG streaming.
 *
 * HOW IT WORKS
 * ────────────
 * 1. Scan for the ESP32 by name or UUID.
 * 2. Connect and subscribe to BLE notifications.
 * 3. Write "PING" to the characteristic and record the send time.
 * 4. The ESP32 firmware (must be specifically written to echo back "PONG")
 *    sends a notification in response.
 * 5. The app calculates RTT = receive time − send time.
 * 6. Repeat every 1 second.
 *
 * NOTE: The ECG streaming firmware (ecg_standalone.ino) does NOT echo PONG.
 * To use this, you need a separate test firmware that echoes the characteristic write.
 *
 * HOW TO ENABLE IN THE UI
 * ────────────────────────
 * In SettingsScreen in MainActivity.kt, add a button:
 *   Button(onClick = { bleLatencyManager.connectAndTest() }) { Text("Latency Test") }
 * And create the manager in MainActivity:
 *   val bleLatencyManager = BleLatencyManager(this) { msg -> bleLogs += "\n$msg" }
 *
 * HOW TO CHANGE PING INTERVAL
 * ────────────────────────────
 * In processPong(), change the 1000ms delay to your desired interval:
 *   Handler(Looper.getMainLooper()).postDelayed({ sendPing() }, 500)  // 500ms interval
 */
@SuppressLint("MissingPermission")
class BleLatencyManager(private val context: Context, private val onLog: (String) -> Unit) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter          = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    // These must match BleProfile.kt and the ESP32 firmware
    private val SERVICE_UUID = BleProfile.serviceUuid
    private val CHAR_UUID    = BleProfile.characteristicUuid
    private val CONFIG_DESC  = BleProfile.clientConfigDescriptorUuid

    /** Timestamp of the most recent PING write — used to calculate RTT */
    private var startTime = 0L

    private val scanTimeoutHandler  = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private fun log(msg: String) {
        Log.d("ECG_DEBUG", msg)
        onLog(msg)
    }

    /**
     * Starts BLE scan and initiates the latency test.
     * Stops scanning after 10 seconds if the device is not found.
     *
     * HOW TO CHANGE SCAN TIMEOUT: change 10000 below to desired milliseconds.
     */
    fun connectAndTest() {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth disabled!")
            return
        }

        log("Scanning for ECGGuard_BLE...")
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

    /** BLE scanner callback — finds our device and initiates connection */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name   = device.name ?: "NULL"

            // Match by name (fast path)
            val isNameMatch = BleProfile.knownDeviceNames.contains(name)

            // Match by service UUID prefix as fallback (handles stale name cache)
            val records     = result.scanRecord?.serviceUuids
            val isUuidMatch = records?.any {
                it.uuid.toString().startsWith("4fafc201")
            } == true

            if (isNameMatch || isUuidMatch) {
                log("TARGET FOUND: $name")

                scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
                adapter.bluetoothLeScanner.stopScan(this)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, gattCallback)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * On connection: refresh cache, wait 1 second, then discover services.
         * The 1 second delay is longer than BleStreamManager's 600ms because
         * test firmware may need more boot time.
         * HOW TO CHANGE: reduce to 200ms if the ESP32 test firmware boots fast.
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected! Refreshing Cache...")
                refreshDeviceCache(gatt)
                Handler(Looper.getMainLooper()).postDelayed({
                    log("Discovering Services...")
                    gatt.discoverServices()
                }, 1000)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected. Status: $status")
                this@BleLatencyManager.gatt = null
            }
        }

        /**
         * After services are discovered, log ALL found services (for debugging)
         * and enable notifications on the target characteristic.
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("--- SERVICE DISCOVERY COMPLETE ---")

            var targetServiceFound = false
            var targetCharFound    = false

            gatt.services.forEach { service ->
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
                    // Log other services so we can spot stale cache entries
                    log("⚠️ Ignored Service: ...${service.uuid.toString().takeLast(4)}")
                }
            }

            if (!targetServiceFound) log("❌ ERROR: Target Service NOT found!")
            if (targetServiceFound && !targetCharFound) log("❌ ERROR: Service found, but Char missing!")
        }

        /** Enable BLE notifications by writing to the CCCD descriptor */
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

        /**
         * After CCCD write succeeds, wait 500ms then send the first PING.
         * HOW TO CHANGE: reduce 500ms for a faster first ping.
         */
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications Active. Pinging in 500ms...")
                Handler(Looper.getMainLooper()).postDelayed({ sendPing() }, 500)
            } else {
                log("Descriptor Write Failed: $status")
            }
        }

        // Pre-Android 13: characteristic value accessed from the object directly
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processPong(characteristic.value)
        }

        // Android 13+: value passed directly to avoid race condition
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processPong(value)
        }

        /**
         * Handles the PONG response from the ESP32.
         * Calculates RTT and schedules the next PING 1 second later.
         *
         * HOW TO CHANGE PING RATE: change 1000 to your desired interval in ms.
         * 1000ms → 1 ping/second. 100ms → 10 pings/second (more stress on BLE stack).
         */
        private fun processPong(value: ByteArray) {
            val str     = String(value)
            val endTime = System.currentTimeMillis()
            val latency = endTime - startTime
            log("RECV: '$str' | RTT: ${latency}ms")

            Handler(Looper.getMainLooper()).postDelayed({ sendPing() }, 1000)
        }

        /**
         * Invokes the hidden BluetoothGatt.refresh() method via Java reflection.
         * This clears Android's internal GATT service cache, forcing fresh discovery.
         *
         * WHY HIDDEN API: Google chose not to expose this method publicly, but it is
         * available on virtually all Android devices. The alternative is uninstalling
         * and reinstalling the app to clear the cache — impractical for field use.
         *
         * HOW TO REMOVE: Delete this call. Discovery may fail on devices with a
         * stale cache, showing old services from a different firmware version.
         */
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

    /**
     * Writes "PING" to the BLE characteristic and records the send timestamp.
     * The ESP32 test firmware must echo back a response to trigger processPong().
     *
     * HOW TO CHANGE PAYLOAD: replace "PING".toByteArray() with any byte pattern,
     * as long as the ESP32 firmware echoes it back as a notification.
     * WRITE_TYPE_DEFAULT = BLE "Write With Response" (ACK from ESP32 before notify).
     * Change to WRITE_TYPE_NO_RESPONSE for lower latency (no ACK, less reliable).
     */
    fun sendPing() {
        val charac = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
        if (charac == null) {
            log("Ping Failed: Char is null")
            return
        }

        val payload = "PING".toByteArray()
        startTime   = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(charac, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            charac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            charac.value     = payload
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(charac)
        }
    }
}
