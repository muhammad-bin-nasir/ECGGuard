package com.example.ecgguard

import java.util.UUID

/**
 * BleProfile.kt
 * =============
 * Central registry of every BLE constant the app uses.
 * Both BleStreamManager and BleLatencyManager import from here,
 * so there is exactly ONE place to update if the ESP32 firmware changes.
 *
 * HOW BLE IDENTIFICATION WORKS
 * ─────────────────────────────
 * A BLE peripheral (the ESP32) advertises:
 *   1. A device NAME       – human-readable string in the scan record
 *   2. A SERVICE UUID      – identifies the "type" of application
 *   3. CHARACTERISTICS     – the individual data channels inside the service
 *
 * The Android app uses either the name OR the service UUID to decide
 * "this is our device". Using both gives redundancy against Android BLE
 * caching bugs that occasionally null-out the device name.
 *
 * MATCHING REQUIREMENT
 * ─────────────────────
 * Every UUID and the DEVICE_NAME string here MUST match exactly what is
 * defined in the ESP32 Arduino sketch (ecg_standalone.ino or ecg_replay_esp32.ino):
 *
 *   #define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
 *   #define CHAR_UUID    "beb5483e-36e1-4688-b7f5-ea07361b26a8"
 *   #define DEVICE_NAME  "ECGGuard_BLE"
 *
 * If you change a UUID here, change it in the ESP32 sketch too, or the
 * app will never find the device.
 */
object BleProfile {

    /**
     * The GATT Service UUID advertised by the ESP32.
     *
     * A GATT Service groups related characteristics together.
     * This UUID was chosen arbitrarily (you can use any valid UUID).
     *
     * HOW TO CHANGE: Generate a new random UUID (uuidgenerator.net),
     * update both this line AND SERVICE_UUID in the ESP32 sketch.
     * After changing, uninstall and reinstall the app to clear Android's
     * BLE cache, otherwise the old service UUID may be remembered.
     */
    val serviceUuid: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    /**
     * The GATT Characteristic UUID where ECG data is notified.
     *
     * A Characteristic is the actual data channel. The ESP32 writes
     * ECG samples here and notifies the phone via BLE NOTIFY property.
     * The phone reads incoming notifications in BleStreamManager.
     *
     * HOW TO CHANGE: Same as serviceUuid — generate a new UUID and
     * update CHAR_UUID in the ESP32 sketch to match.
     */
    val characteristicUuid: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    /**
     * Client Characteristic Configuration Descriptor (CCCD) UUID.
     *
     * This UUID is FIXED by the BLE specification (0x2902) — do NOT change it.
     * Every BLE device in the world uses this same UUID for the CCCD.
     * Writing ENABLE_NOTIFICATION_VALUE to this descriptor tells the ESP32
     * "start sending NOTIFY events to me". Without this write, the phone
     * receives no data even if the ESP32 is sending it.
     */
    val clientConfigDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Set of BLE device names the scanner will accept as "our device".
     *
     * The scanner checks: does the advertising device name appear in this set?
     * If yes, connect. Using a Set (rather than a single string) lets us
     * support multiple firmware versions or test devices simultaneously.
     *
     * HOW TO CHANGE:
     * - Add a new string to support a new firmware version or a renamed device.
     * - Remove strings to restrict which devices can connect (e.g. remove
     *   "FYP-Test-Heartbeat" before production to reject old test hardware).
     * - Change "ECGGuard_BLE" here if you rename the device in the ESP32 sketch
     *   (#define DEVICE_NAME in ecg_standalone.ino).
     *
     * NOTE: The scanner also matches by service UUID as a fallback, so the
     * device will still connect even if its name is null in the scan record
     * (an Android 12 BLE caching bug where device.name returns null).
     */
    val knownDeviceNames = setOf(
        "ECGGuard_BLE",       // primary production name
        "ECG_STREAMER",       // legacy name from earlier firmware versions
        "FYP-Test-Heartbeat", // name used during early FYP development
        "ECG_LATENCY_TEST"    // name used by BleLatencyManager's test firmware
    )
}
