package dev.earshot.protocol.bragi

import dev.earshot.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthMessagesTest {

    @Test fun `client hello wraps the key id in two layers`() {
        val id = ByteArray(8) { it.toByte() }
        // field 1 (len 10) { field 1 (len 8) id }
        assertEquals("0a0a0a080001020304050607", AuthMessages.clientHello(id).toHex())
        assertContentEquals(id, AuthMessages.parseClientHello(AuthMessages.clientHello(id)))
    }

    @Test fun `server hello lists every key id`() {
        val a = ByteArray(8) { 1 }; val b = ByteArray(8) { 2 }
        val parsed = AuthMessages.parseServerHello(AuthMessages.serverHello(listOf(a, b)))
        assertEquals(2, parsed.size)
        assertContentEquals(a, parsed[0]); assertContentEquals(b, parsed[1])
    }

    @Test fun `authenticate request round trips`() {
        val eph = ByteArray(65) { 4 }; val rnd = ByteArray(16) { 9 }; val sig = ByteArray(70) { 7 }
        val p = AuthMessages.parseClientAuthenticate(AuthMessages.clientAuthenticate(eph, rnd, sig))!!
        assertContentEquals(eph, p.ephemeralPoint); assertContentEquals(rnd, p.random); assertContentEquals(sig, p.signature)
    }

    @Test fun `authenticate response carries any subset of fields`() {
        val ch = BragiHandshake.ChannelSpec(id = 1, transport = 1, cipherSuite = 2, keyLength = 16, nonceLength = 12, authTagLength = 16)
        val full = AuthMessages.parseServerAuthenticate(AuthMessages.serverAuthenticate(ByteArray(65) { 4 }, ByteArray(16) { 1 }, ch, ByteArray(72) { 2 }))
        assertEquals(65, full.ephemeralPoint!!.size); assertEquals(16, full.random!!.size)
        assertEquals(ch, full.channel); assertEquals(72, full.signature!!.size)
        val onlyChannel = AuthMessages.parseServerAuthenticate(AuthMessages.serverAuthenticate(channel = ch))
        assertNull(onlyChannel.ephemeralPoint); assertNull(onlyChannel.signature); assertEquals(ch, onlyChannel.channel)
    }

    @Test fun `trust establishment messages round trip`() {
        val pt = ByteArray(65) { 4 }
        val start = AuthMessages.parseTrustStart(AuthMessages.trustStart(pt, 6))!!
        assertContentEquals(pt, start.identityPoint); assertEquals(6, start.passcodeDigits)
        assertEquals(1, AuthMessages.parseTrustStartResponse(AuthMessages.trustStartResponse(listOf(pt))).size)
        val c = ByteArray(16) { 3 }
        assertContentEquals(c, AuthMessages.parseTrustCommit(AuthMessages.trustCommit(c)))
        assertContentEquals(c, AuthMessages.parseNonce(AuthMessages.nonce(c)))
        assertContentEquals(c, AuthMessages.parseConfirmation(AuthMessages.confirmation(c)))
        assertEquals("0808", AuthMessages.trustAbort(AuthMessages.Error.PASSCODE_REJECTED).toHex())
    }

    @Test fun `an empty ack parses to no confirmation`() {
        assertNull(AuthMessages.parseConfirmation(ByteArray(0)))
    }

    @Test fun `commitment and confirmation are 16 bytes and depend on their inputs`() {
        val a = ByteArray(16) { 1 }; val x = ByteArray(32) { 2 }; val y = ByteArray(32) { 3 }
        val c1 = BragiHandshake.commitment(a, x, y)
        assertEquals(16, c1.size)
        assertTrue(!c1.contentEquals(BragiHandshake.commitment(a, y, x)))
        val dh = ByteArray(32) { 5 }
        val k1 = BragiHandshake.confirmation(dh, a, ByteArray(16) { 2 })
        val k2 = BragiHandshake.confirmation(dh, ByteArray(16) { 2 }, a)
        assertEquals(16, k1.size)
        assertTrue(!k1.contentEquals(k2), "the nonce order matters: each side puts its own first")
    }

    @Test fun `an all-zero shared key is refused`() {
        val e = runCatching { BragiHandshake.confirmation(ByteArray(32), ByteArray(16), ByteArray(16)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun `passcode digits are bounded and zero means none`() {
        val x = ByteArray(32) { 1 }; val n = ByteArray(16) { 2 }
        assertEquals(0, BragiHandshake.passcode(x, x, n, n, 0))
        assertTrue(BragiHandshake.passcode(x, x, n, n, 4) in 0..9_999)
        assertTrue(runCatching { BragiHandshake.passcode(x, x, n, n, 7) }.isFailure)
    }

    @Test fun `the model catalogue recognises the crusher by name and sku`() {
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromBluetoothName("Skullcandy CRUSHER 1080 ANC"))
        assertEquals(BragiModel.SKULLCANDY_CRUSHER_1080, BragiModel.fromModelNumber("s6cnw"))
        assertNull(BragiModel.fromBluetoothName("Bose QC45"))
        assertNull(BragiModel.fromModelNumber("S6CAW")) // the Crusher ANC 2, a different product
        assertTrue(BragiModel.isBragiName("crusher 1080"))
    }
}
