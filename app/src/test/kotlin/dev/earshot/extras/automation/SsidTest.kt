package dev.earshot.extras.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsidTest {
    @Test
    fun `strips the quotes WifiInfo adds`() {
        assertEquals("Home", Ssid.normalize("\"Home\""))
        assertEquals("Home", Ssid.normalize("Home"))
        assertEquals("Café 5G", Ssid.normalize("  \"Café 5G\" "))
    }

    @Test
    fun `platform placeholders become null`() {
        assertNull(Ssid.normalize(null))
        assertNull(Ssid.normalize(""))
        assertNull(Ssid.normalize("\"\""))
        assertNull(Ssid.normalize("<unknown ssid>"))
        assertNull(Ssid.normalize("0x"))
    }

    @Test
    fun `a lone quote is kept as a name`() {
        assertEquals("\"", Ssid.normalize("\""))
    }

    @Test
    fun `matching is quote and case tolerant`() {
        assertTrue(Ssid.matches("Home", "\"Home\""))
        assertTrue(Ssid.matches("\"home\"", "HOME"))
        assertFalse(Ssid.matches("Home", "Home 5G"))
        assertFalse(Ssid.matches("Home", null))
        assertFalse(Ssid.matches("", "\"\""))
    }
}
