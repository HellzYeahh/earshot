package dev.earshot.bt

import dev.earshot.protocol.hexToBytes
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The greeting classifier that decides whether an SDP strategy landed on the BMAP channel. */
class RfcommTransportTest {

    private fun bmap(hex: String) = RfcommTransport.looksLikeBmap(hex.replace(" ", "").hexToBytes())

    @Test
    fun `a greeting of device-side frames on known blocks is BMAP`() {
        assertTrue(bmap("02020304 50ffff00"))                       // battery STATUS
        assertTrue(bmap("02020304 50ffff00 1f030301 00 1f060700"))  // STATUS, STATUS, PROCESSING
        assertTrue(bmap("01050401 05"))                             // ERROR counts too
        assertTrue(bmap("02020304 50ffff00 1f06"))                  // trailing partial frame is fine
    }

    @Test
    fun `the channel 14 beacon is rejected`() {
        // `ff 55 02 …` happens to decode as a SET_GET on block 0xFF: wrong operator, unknown block.
        assertFalse(bmap("ff550203 010203"))
        assertFalse(bmap("ff5502"))
    }

    @Test
    fun `anything that decodes to no valid frame is the wrong channel`() {
        assertFalse(bmap(""))
        assertFalse(bmap("0202"))                        // too short to be a frame
        assertFalse(bmap("48656c6c6f20776f726c64"))      // "Hello world" (channel 22 log stream)
        assertFalse(bmap("02020100"))                    // a GET: phones send those, headphones do not
        assertFalse(bmap("02020304 50ffff00 63000300")) // one good frame, one on an unknown block
    }
}
