/*
 * ECGGuard – CSV Replay Firmware for ESP32
 * ==========================================
 * This sketch turns the ESP32 into a Serial-to-BLE relay so that you can
 * replay a pre-recorded ECG CSV from a laptop without any physical ECG leads.
 *
 * Data path:
 *   Laptop (ecg_replay.py)  ──USB/Serial──►  ESP32  ──BLE──►  ECGGuard Android app
 *
 * BLE configuration is IDENTICAL to the real ECGGuard wearable:
 *   • Device name      : ECGGuard_BLE
 *   • Service UUID     : 4fafc201-1fb5-459e-8fcc-c5c9c331914b
 *   • Characteristic   : beb5483e-36e1-4688-b7f5-ea07361b26a8  (NOTIFY | READ)
 *
 * The Android app requires NO changes at all.
 *
 * Instructions:
 *   1. Install "ESP32 Arduino" in Arduino IDE (Board Manager).
 *   2. Open this sketch and flash it to the ESP32.
 *   3. On the laptop:
 *        pip install pyserial
 *        python ecg_replay.py --port COM3 --file your_ecg.csv
 *   4. Open the ECGGuard app, tap Settings → SCAN & CONNECT WEARABLE.
 *
 * IMPORTANT: Match SERIAL_BAUD here and in --baud argument of ecg_replay.py.
 *            Match CHUNK_SAMPLES here and in ecg_replay.py (both default to 20).
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── Configuration – must match ecg_replay.py ──────────────────────────────────
#define SERIAL_BAUD    115200
#define CHUNK_SAMPLES  10                       // int16 samples per BLE notify
#define CHUNK_BYTES    (CHUNK_SAMPLES * 2)      // 2 bytes per int16

// ── BLE identifiers – must match BleProfile.kt ────────────────────────────────
#define DEVICE_NAME  "ECGGuard_BLE"
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_UUID    "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Globals ───────────────────────────────────────────────────────────────────
static BLECharacteristic* pChar        = nullptr;
static bool               bleConnected = false;

// Rolling receive buffer – accumulates raw bytes from Serial
static uint8_t  rxBuf[CHUNK_BYTES];
static uint16_t rxIdx = 0;

// ── BLE server callbacks ──────────────────────────────────────────────────────
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* /*pServer*/) override {
        bleConnected = true;
        Serial.println("[BLE] Phone connected");
    }

    void onDisconnect(BLEServer* pServer) override {
        bleConnected = false;
        Serial.println("[BLE] Phone disconnected – restarting advertising");
        // Restart advertising so the app can reconnect after a screen-off
        BLEDevice::startAdvertising();
    }
};

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(SERIAL_BAUD);
    delay(200);
    Serial.println("[ECGGuard-Replay] Booting …");

    // ── Initialise BLE stack ──────────────────────────────────────────────────
    BLEDevice::init(DEVICE_NAME);

    BLEServer* pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pChar = pService->createCharacteristic(
        CHAR_UUID,
        BLECharacteristic::PROPERTY_READ   |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    // Add the standard Client Characteristic Configuration Descriptor (0x2902)
    // so the Android app can subscribe to notifications
    pChar->addDescriptor(new BLE2902());

    pService->start();

    // ── Advertise with service UUID so the app can find us by UUID too ────────
    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);
    // Preferred connection interval hints (improves iOS/Android connection speed)
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.println("[ECGGuard-Replay] BLE advertising as: " DEVICE_NAME);
    Serial.println("[ECGGuard-Replay] Waiting for data from ecg_replay.py …");
}

// ── Main loop ─────────────────────────────────────────────────────────────────
void loop() {
    /*
     * Drain whatever bytes have arrived on the USB-Serial port into rxBuf.
     * When the buffer is full (CHUNK_BYTES bytes = CHUNK_SAMPLES int16s),
     * fire one BLE notification and reset the pointer.
     *
     * The pacing is entirely controlled by the Python script; the ESP32 just
     * acts as a transparent forwarder.
     */
    while (Serial.available() > 0) {
        rxBuf[rxIdx++] = (uint8_t)Serial.read();

        if (rxIdx >= CHUNK_BYTES) {
            if (bleConnected) {
                pChar->setValue(rxBuf, CHUNK_BYTES);
                pChar->notify();
            }
            rxIdx = 0;   // reset for next chunk
        }
    }
    // No delay() here – keep the loop tight so Serial bytes are not dropped
}
