#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define WIFI_MIRROR_ENABLED  true
#define WIFI_SSID            "momi"
#define WIFI_PASSWORD        "mbn7272727"
#define LAPTOP_IP            "192.168.43.47"
#define LAPTOP_PORT          9998

#if WIFI_MIRROR_ENABLED
  #include <WiFi.h>
  #include <WiFiClient.h>
#endif

#define ECG_PIN              34
#define LO_PLUS_PIN          32
#define LO_MINUS_PIN         33
#define USE_LEAD_OFF_DETECT  true

#define SAMPLE_RATE_HZ     250
#define CHUNK_SAMPLES      10
#define CHUNK_BYTES        (CHUNK_SAMPLES * 2)
#define SAMPLE_INTERVAL_US (1000000UL / SAMPLE_RATE_HZ)

#define DEVICE_NAME  "ECGGuard_BLE"
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_UUID    "beb5483e-36e1-4688-b7f5-ea07361b26a8"

#define WIFI_RECONNECT_MS  3000

static BLECharacteristic* pChar        = nullptr;
static volatile bool      bleConnected = false;

static uint8_t   txBuf[CHUNK_BYTES];
static uint8_t   bufIdx       = 0;
static uint32_t  lastSampleUs = 0;

#if WIFI_MIRROR_ENABLED
static WiFiClient  tcpClient;
static uint32_t    lastReconnectMs = 0;

static void ensureTcpConnected() {
    if (tcpClient.connected()) return;
    uint32_t now = millis();
    if ((now - lastReconnectMs) < WIFI_RECONNECT_MS) return;
    lastReconnectMs = now;
    if (WiFi.status() != WL_CONNECTED) return;
    tcpClient.connect(LAPTOP_IP, LAPTOP_PORT);
}
#endif

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override    { bleConnected = true; }
    void onDisconnect(BLEServer*) override { bleConnected = false; BLEDevice::startAdvertising(); }
};

void setup() {
    Serial.begin(115200);
    delay(200);

    pinMode(ECG_PIN, INPUT);
    if (USE_LEAD_OFF_DETECT) {
        pinMode(LO_PLUS_PIN,  INPUT);
        pinMode(LO_MINUS_PIN, INPUT);
    }

    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

#if WIFI_MIRROR_ENABLED
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    uint32_t wifiDeadline = millis() + 10000;
    while (WiFi.status() != WL_CONNECTED && millis() < wifiDeadline) {
        delay(250);
    }
#endif

    BLEDevice::init(DEVICE_NAME);
    BLEServer* pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());
    BLEService* pService = pServer->createService(SERVICE_UUID);
    pChar = pService->createCharacteristic(
        CHAR_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pChar->addDescriptor(new BLE2902());
    pService->start();

    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();

    lastSampleUs = micros();
}

void loop() {
    uint32_t now = micros();
    if ((now - lastSampleUs) < SAMPLE_INTERVAL_US) return;
    lastSampleUs += SAMPLE_INTERVAL_US;

    int16_t sample = 0;
    if (USE_LEAD_OFF_DETECT) {
        bool leadsOff = (digitalRead(LO_PLUS_PIN) == HIGH) ||
                        (digitalRead(LO_MINUS_PIN) == HIGH);
        sample = leadsOff ? 0 : (int16_t)analogRead(ECG_PIN);
    } else {
        sample = (int16_t)analogRead(ECG_PIN);
    }

    txBuf[bufIdx++] = (uint8_t)( sample       & 0xFF);
    txBuf[bufIdx++] = (uint8_t)((sample >> 8) & 0xFF);

    if (bufIdx >= CHUNK_BYTES) {
        if (bleConnected) {
            pChar->setValue(txBuf, CHUNK_BYTES);
            pChar->notify();
        }
#if WIFI_MIRROR_ENABLED
        ensureTcpConnected();
        if (tcpClient.connected()) {
            tcpClient.write(txBuf, CHUNK_BYTES);
        }
#endif
        bufIdx = 0;
    }
}
