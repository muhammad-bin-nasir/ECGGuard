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

/**
 * BleStreamManager.kt
 * ====================
 * Manages the full BLE lifecycle: scan → connect → subscribe → receive ECG data.
 * This is the PRIMARY data source when using the ESP32 wearable hardware.
 *
 * DATA FLOW
 * ─────────
 * ESP32 ADC reads sensor → packs as little-endian int16 → BLE NOTIFY
 *   → onCharacteristicChanged → processBytes → signalBuffer
 *   → onDataReceived (fired when buffer ≥ 2500 samples)
 *   → ecgDataCallback in MainActivity (signal processing + AI inference)
 *
 * BLE CONNECTION SEQUENCE
 * ───────────────────────
 * 1. connect()          → startScan (LOW_LATENCY mode)
 * 2. scanCallback       → device found by name or UUID → stopScan → connectGatt
 * 3. gattCallback       → CONNECTED → requestMtu(512) → discoverServices
 * 4. onServicesDiscovered → getCharacteristic → enable NOTIFY (write CCCD descriptor)
 * 5. onCharacteristicChanged fires for every BLE notification from the ESP32
 *
 * WHY MTU 512?
 * ─────────────
 * Default BLE MTU is 23 bytes → only 20 bytes of payload.
 * At 250 Hz with 2-byte samples: 5 samples per notification.
 * MTU 512 → 509 bytes payload → up to 254 samples per notification.
 * Larger packets mean fewer BLE transactions and lower overhead.
 *
 * HOW TO CHANGE THE SCAN TIMEOUT
 * ───────────────────────────────
 * Change the 10000 ms value in connect() → scanTimeoutHandler.postDelayed(..., 10000)
 * Shorter = faster failure feedback. Longer = more time to find weak-signal devices.
 *
 * HOW TO ADD AUTO-RECONNECT
 * ──────────────────────────
 * In onConnectionStateChange → DISCONNECTED, call connect() after a short delay:
 *   Handler(Looper.getMainLooper()).postDelayed({ connect() }, 3000)
 * Be careful: this can create infinite reconnect loops if the device is truly gone.
 */
@SuppressLint("MissingPermission")   // permissions are checked at runtime in MainActivity
class BleStreamManager(
    private val context: Context,
    /** Called for status/debug messages. Always called from a background thread or handler. */
    private val onLog: (String) -> Unit,
    /**
     * Called every time a fresh 2500-sample window is ready for inference.
     * Runs on a BLE callback thread — call runOnUiThread() before touching Compose state.
     * First param: the 2500-sample FloatArray. Second param: debug label string.
     */
    private val onDataReceived: (FloatArray, String) -> Unit
) {

    // ── Bluetooth system services ──────────────────────────────────────────────
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter          = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null   // null when disconnected

    // ── BLE profile constants (from BleProfile.kt) ────────────────────────────
    private val TARGET_SERVICE_UUID = BleProfile.serviceUuid
    private val TARGET_CHAR_UUID    = BleProfile.characteristicUuid
    private val CONFIG_DESC         = BleProfile.clientConfigDescriptorUuid

    // ── Signal buffer ──────────────────────────────────────────────────────────

    /**
     * Rolling buffer of decoded float samples.
     * Grows until REQUIRED_SIZE is reached, then onDataReceived fires on every
     * new packet (sliding window). Trimmed to MAX_BUFFER_SIZE to cap memory use.
     * Cleared on disconnect so stale data never bleeds into the next session.
     */
    private val signalBuffer = ArrayList<Float>()

    /**
     * 2500 = 10 seconds × 250 Hz. This is the exact input shape of the ONNX model.
     * HOW TO CHANGE: update this AND the shape in ECGModel.kt AND SAMPLE_INTERVAL_US
     * in ecg_standalone.ino (to maintain the same sample rate).
     */
    private val REQUIRED_SIZE = 2500

    /**
     * Buffer trim threshold. Once exceeded, 250 oldest samples are removed.
     * 3000 = REQUIRED_SIZE + 500 (2 seconds of overlap headroom).
     * HOW TO CHANGE: Increase for more overlap between inference windows.
     * Minimum safe value: REQUIRED_SIZE + 1.
     */
    private val MAX_BUFFER_SIZE = 3000

    // ── Diagnostics ────────────────────────────────────────────────────────────
    private var firstPacketTime  = 0L   // timestamp of first BLE packet in session
    private var lastPacketTime   = 0L   // timestamp of most recent BLE packet
    private var packetCount      = 0    // total packets received since connect
    private var discoveryRetried = false // guards against infinite retry loops

    // ── Scan timeout handler ───────────────────────────────────────────────────
    private val scanTimeoutHandler  = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private fun log(msg: String) {
        Log.d("ECG_DEBUG", msg)
        onLog(msg)
    }

    // ── Safe helpers ───────────────────────────────────────────────────────────

    /** Stops the BLE scanner without crashing if it was already stopped. */
    private fun stopScanSafely() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    /** Cancels the pending scan-timeout Runnable. */
    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    /**
     * Clears Android's GATT cache for this device.
     * Android sometimes caches the service/characteristic list from a previous
     * connection. If the ESP32 firmware was updated with new UUIDs, the cached
     * services won't match. Calling refresh() forces re-discovery from the device.
     *
     * This uses a hidden Android API (BluetoothGatt.refresh) via reflection.
     * It works on all common Android versions but is not officially documented.
     * HOW TO REMOVE: Delete this call — service discovery may fail on devices
     * with stale caches, but it will work fine on fresh connections.
     */
    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            log("Cache refresh skipped: ${e.message}")
            false
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Starts BLE scanning for the ECG device.
     * Closes any existing connection first, then starts a 10-second scan.
     * Call this when the user taps "SCAN & CONNECT" in SettingsScreen.
     *
     * SCAN MODE LOW_LATENCY: fastest scan, highest battery use.
     * HOW TO CHANGE: Use SCAN_MODE_BALANCED (lower battery, ~5 second scan interval).
     * Only matters if scanning runs continuously; here it stops as soon as device is found.
     */
    fun connect() {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth disabled!")
            return
        }

        // Clean up any previous session before starting fresh
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

        // Auto-stop scanning after 10 seconds if no device found
        // HOW TO CHANGE TIMEOUT: change 10000 to milliseconds of your choice
        scanTimeoutRunnable = Runnable {
            if (gatt == null) {
                stopScanSafely()
                log("Scan timeout. ESP32 not found.")
            }
        }
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable!!, 10000)
    }

    /**
     * Disconnects BLE, stops scanning, and clears the signal buffer.
     * Call this when navigating away, or when switching to USB mode.
     */
    fun disconnect() {
        cancelScanTimeout()
        stopScanSafely()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close()      } catch (_: Exception) {}
        gatt = null
        signalBuffer.clear()   // discard partial data
        packetCount = 0
    }

    // ── BLE Scan Callback ──────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return

            // Prefer device.name, fall back to scanRecord.deviceName.
            // Android 12 has a bug where device.name can be null for bonded devices.
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"

            // Log every seen device to Logcat for debugging (not shown in UI)
            Log.d("ECG_SCAN", "Found: $name | MAC: ${device.address}")

            // Match by name (fast) OR by service UUID (fallback for stale name cache)
            val isNameMatch = BleProfile.knownDeviceNames.contains(name)
            val isUuidMatch = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(TARGET_SERVICE_UUID.toString(), ignoreCase = true)
            } == true

            if (isNameMatch || isUuidMatch) {
                cancelScanTimeout()
                stopScanSafely()
                log("TARGET FOUND ($name)! Connecting...")

                // TRANSPORT_LE: force BLE (not Bluetooth Classic) on dual-mode devices
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, gattCallback)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Error 2 = SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (restart BT to fix)
            // Error 6 = SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (too many simultaneous scans)
            log("SCAN CRASHED! Error Code: $errorCode")
        }
    }

    // ── GATT Callback (connection + data) ─────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * Fires when the BLE connection state changes: connecting → connected → disconnecting → disconnected.
         * Also fires on unexpected disconnects (device powered off, out of range).
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Non-zero status = error (e.g. 133 = GATT_ERROR, common BLE bug on Android)
                // HOW TO ADD AUTO-RETRY: call connect() here after a short delay
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

                // Clear stale cache so fresh service discovery always reflects the actual firmware
                refreshDeviceCache(gatt)

                // Small delay (600ms) after cache refresh before requesting MTU.
                // Without the delay, MTU request fails on some devices.
                // HOW TO CHANGE: reduce to 200ms for faster connection; increase to 1000ms if MTU fails
                Handler(Looper.getMainLooper()).postDelayed({
                    log("Requesting MTU 512...")
                    val requested = gatt.requestMtu(512)
                    if (!requested) {
                        // Some older devices don't support MTU negotiation — proceed with default MTU
                        log("MTU request not supported. Discovering services...")
                        gatt.discoverServices()
                    }
                }, 600)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelScanTimeout()
                log("Disconnected.")
                gatt.close()
                this@BleStreamManager.gatt = null

                // Flush the buffer so old samples don't mix with new data on reconnect.
                // Without this, the sliding window produces ghost signals from stale data
                // when the first new packets arrive after reconnecting.
                signalBuffer.clear()
                packetCount = 0
            }
        }

        /**
         * Fires after requestMtu() completes.
         * status == GATT_SUCCESS means the ESP32 accepted the requested MTU.
         * Regardless of success or failure, we proceed to service discovery.
         *
         * HOW TO USE THE MTU VALUE: the usable payload = mtu - 3 bytes (ATT header).
         * MTU 512 → 509 bytes payload → 254 int16 samples per notification.
         */
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("MTU changed to $mtu. Discovering services...")
            } else {
                log("MTU change failed (status $status). Discovering services...")
            }
            gatt?.discoverServices()
        }

        /**
         * Fires after discoverServices() completes.
         * We find our target service + characteristic and enable BLE notifications.
         *
         * If the target is not found on first try, we attempt one cache refresh + retry.
         * This handles the case where Android's GATT cache shows a stale service list.
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed. Status: $status")
                return
            }

            val discoveredServices = gatt.services.joinToString { it.uuid.toString() }
            log("Services discovered: $discoveredServices")

            val service = gatt.getService(TARGET_SERVICE_UUID)
            val charac  = service?.getCharacteristic(TARGET_CHAR_UUID)

            if (charac != null) {
                // Step 1: Tell Android's BLE stack to forward notifications to our callback
                gatt.setCharacteristicNotification(charac, true)

                // Step 2: Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor.
                // This tells the ESP32 to actually start sending NOTIFY events.
                // Without this write, the ESP32 sends nothing.
                val desc = charac.getDescriptor(CONFIG_DESC)
                if (desc != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ API
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        // Older API (deprecated but required pre-Android 13)
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                    this@BleStreamManager.gatt = gatt
                    log("Service found. Notifications enabled.")
                    // Reset timing counters for a fresh session
                    firstPacketTime = 0L
                    packetCount = 0
                    signalBuffer.clear()
                } else {
                    log("Error: Notification descriptor missing on characteristic.")
                }

            } else if (!discoveryRetried) {
                // First attempt failed — try cache refresh + rediscover once
                discoveryRetried = true
                log("Target service/characteristic not found. Retrying discovery...")
                refreshDeviceCache(gatt)
                Handler(Looper.getMainLooper()).postDelayed({ gatt.discoverServices() }, 800)
            } else {
                val serviceState = if (service == null) "service missing" else "characteristic missing"
                log("Error: BLE target not found after retry ($serviceState).")
            }
        }

        /**
         * Fires for each BLE notification from the ESP32 (pre-Android 13 devices).
         * Delegates to processBytes() for decoding.
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            processBytes(characteristic.value)
        }

        /**
         * Fires for each BLE notification from the ESP32 (Android 13+ devices).
         * Android 13 added a value parameter to avoid a race condition that existed
         * in the older API where characteristic.value could be overwritten mid-read.
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processBytes(value)
        }

        /**
         * Decodes incoming BLE bytes as little-endian int16 samples and fires
         * onDataReceived when the sliding window reaches REQUIRED_SIZE samples.
         *
         * SLIDING WINDOW:
         * After the buffer is full, every new packet shifts the window forward.
         * Each shift triggers one inference cycle in MainActivity's ecgDataCallback.
         *
         * HOW TO REDUCE CPU USAGE: Add a counter and only fire onDataReceived
         * every N packets instead of every packet:
         *   if (packetCount % 5 == 0) onDataReceived(...)
         */
        private fun processBytes(data: ByteArray) {
            if (data.isEmpty()) return

            val now = System.currentTimeMillis()
            if (firstPacketTime == 0L) firstPacketTime = now
            lastPacketTime = now
            packetCount++

            // Decode each 2-byte pair as a signed 16-bit integer (little-endian)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toFloat()
                signalBuffer.add(sample)
            }

            if (signalBuffer.size >= REQUIRED_SIZE) {
                // Snapshot the most recent REQUIRED_SIZE samples
                val inputData = signalBuffer.takeLast(REQUIRED_SIZE).toFloatArray()
                onDataReceived(inputData, "Pkt #$packetCount | Buffer: ${signalBuffer.size}")

                // Trim the oldest 250 samples (1 second) once the buffer exceeds MAX_BUFFER_SIZE
                if (signalBuffer.size > MAX_BUFFER_SIZE) {
                    signalBuffer.subList(0, 250).clear()
                }
            }
        }
    }
}
