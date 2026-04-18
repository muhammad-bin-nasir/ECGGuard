package com.example.ecgguard

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun bleProfile_matchesEsp32Firmware() {
        assertEquals("4fafc201-1fb5-459e-8fcc-c5c9c331914b", BleProfile.serviceUuid.toString())
        assertEquals("beb5483e-36e1-4688-b7f5-ea07361b26a8", BleProfile.characteristicUuid.toString())
        assertTrue(BleProfile.knownDeviceNames.contains("ECGGuard_BLE"))
    }
}