package dev.earshot.bt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GattBatteryReaderTest {
    @Test
    fun `Battery Level is the first byte as a percentage`() {
        assertEquals(87, GattBatteryReader.parse(byteArrayOf(87)))
        assertEquals(0, GattBatteryReader.parse(byteArrayOf(0)))
        assertEquals(100, GattBatteryReader.parse(byteArrayOf(100, 5)))
    }

    @Test
    fun `out of range or empty values are not readings`() {
        assertNull(GattBatteryReader.parse(byteArrayOf(101)))
        assertNull(GattBatteryReader.parse(byteArrayOf(0xFF.toByte())))
        assertNull(GattBatteryReader.parse(byteArrayOf()))
        assertNull(GattBatteryReader.parse(null))
    }

    @Test
    fun `the standard Battery Service and Battery Level UUIDs`() {
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", GattBatteryReader.BATTERY_SERVICE.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", GattBatteryReader.BATTERY_LEVEL.toString())
    }
}
