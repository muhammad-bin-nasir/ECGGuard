#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERIAL_BAUD    115200
#define CHUNK_SAMPLES  10
#define CHUNK_BYTES    (CHUNK_SAMPLES * 2)

#define DEVICE_NAME  "ECGGuard_BLE"
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_UUID    "beb5483e-36e1-4688-b7f5-ea07361b26a8"

static BLECharacteristic* pChar = nullptr;
static bool bleConnected = false;
static uint8_t rxBuf[CHUNK_BYTES];
static uint16_t rxIdx = 0;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override {
        bleConnected = true;
        Serial.println("[BLE] Phone connected");
    }
    void onDisconnect(BLEServer*) override {
        bleConnected = false;
        Serial.println("[BLE] Phone disconnected – restarting advertising");
        BLEDevice::startAdvertising();
    }
};

void setup() {
    Serial.begin(SERIAL_BAUD);
    delay(200);
    BLEDevice::init(DEVICE_NAME);
    BLEServer* pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());
    BLEService* pService = pServer->createService(SERVICE_UUID);
    pChar = pService->createCharacteristic(CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    pChar->addDescriptor(new BLE2902());
    pService->start();
    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();
    Serial.println("[ECGGuard-Replay] Ready. Waiting for ecg_replay.py ...");
}

void loop() {
    while (Serial.available() > 0) {
        rxBuf[rxIdx++] = (uint8_t)Serial.read();
        if (rxIdx >= CHUNK_BYTES) {
            if (bleConnected) {
                pChar->setValue(rxBuf, CHUNK_BYTES);
                pChar->notify();
            }
            rxIdx = 0;
        }
    }
}