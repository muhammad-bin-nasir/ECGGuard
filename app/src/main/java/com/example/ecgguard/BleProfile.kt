package com.example.ecgguard

import java.util.UUID

object BleProfile {
    val serviceUuid: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val characteristicUuid: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val clientConfigDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val knownDeviceNames = setOf(
        "ECGGuard_BLE",
        "ECG_STREAMER",
        "FYP-Test-Heartbeat",
        "ECG_LATENCY_TEST"
    )
}
