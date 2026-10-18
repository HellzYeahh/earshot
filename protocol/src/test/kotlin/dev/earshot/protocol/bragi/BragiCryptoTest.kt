package dev.earshot.protocol.bragi

import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BragiCryptoTest {

    // ── HKDF-SHA256 known-answer (RFC 5869 Test Case 1) ──────────────────────────────────

    @Test fun `hkdf sha256 matches RFC 5869 test case 1`() {
        val ikm = "0b".repeat(22).hexToBytes()
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
        val okm = Hkdf.expand(prk, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    // ── AEAD round-trip + direction nonce ────────────────────────────────────────────────

    @Test fun `aes gcm seal and open round-trips`() {
        val key = ByteArray(16) { it.toByte() }
        val salt = ByteArray(8) { (0x10 + it).toByte() }
        val c = ChannelCipher(key, salt, ChannelCipher.Suite.AES_GCM, authTagBytes = 16)
        val device = ChannelCipher(key, salt, ChannelCipher.Suite.AES_GCM, authTagBytes = 16, asDevice = true)
        val packet = BosRpcPacket.request(BragiServices.MODE_CONFIGURE, 3, Hearing.modeConfigure(HearingMode.ANC, true)).encode()
        val sealed = c.seal(packet, seq = 0)
        // header 06 05, len = plaintext + 16, seq 0
        assertEquals("0605", sealed.copyOfRange(0, 2).toHex())
        assertEquals(packet.size + 16, ((sealed[2].toInt() and 0xFF) shl 8) or (sealed[3].toInt() and 0xFF))
        assertEquals(8 + packet.size + 16, sealed.size)
        // The headphones open what the phone sealed, and the phone opens what they seal back.
        assertEquals(packet.toHex(), device.open(sealed).toHex())
        assertEquals(packet.toHex(), c.open(device.seal(packet, seq = 0)).toHex())
        // A cipher cannot open its own output: the direction bit differs.
        assertFailsWith<Exception> { c.open(sealed) }
    }

    @Test fun `a tampered tag fails to open`() {
        val c = ChannelCipher(ByteArray(16), ByteArray(4), ChannelCipher.Suite.AES_GCM)
        val sealed = c.seal(byteArrayOf(0x10, 0x02, 0x01, 0x00), seq = 1)
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<Exception> { c.open(tampered) }
    }

    @Test fun `chacha20 poly1305 round-trips when available`() {
        val available = runCatching { javax.crypto.Cipher.getInstance("ChaCha20-Poly1305") }.isSuccess
        if (!available) return // JDK/Android too old; AES suites are the fallback
        val key = ByteArray(32) { it.toByte() }
        val c = ChannelCipher(key, ByteArray(8), ChannelCipher.Suite.CHACHA20_POLY1305)
        val device = ChannelCipher(key, ByteArray(8), ChannelCipher.Suite.CHACHA20_POLY1305, asDevice = true)
        val pt = byteArrayOf(0x10, 0x17, 0x02, 0x00)
        assertEquals(pt.toHex(), device.open(c.seal(pt, seq = 2)).toHex())
    }

    // ── AES-CCM: Android has none, so ours must match an independent implementation ──────

    @Test fun `ccm matches RFC 3610 packet vector 1`() {
        val key = "C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF".hexToBytes()
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val packet = ByteArray(0x1f) { it.toByte() }
        val aad = packet.copyOfRange(0, 8)
        val out = AesCcm.seal(key, nonce, aad, packet.copyOfRange(8, packet.size), tagLength = 8)
        assertEquals("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0", out.toHex())
        assertEquals(packet.copyOfRange(8, packet.size).toHex(), AesCcm.open(key, nonce, aad, out, tagLength = 8).toHex())
    }

    @Test fun `ccm matches independent vectors for the nonce and tag sizes the session uses`() {
        val key = ByteArray(16) { it.toByte() }
        val aad = "0605001500000000".hexToBytes().let { it[3] = 0x15; it }
        val msg = "1004020008021001".hexToBytes()
        assertEquals("33b1bba04af63db37ad7902e12ac87a60b853677dcfd740e", AesCcm.seal(key, ByteArray(12) { (0x10 + it).toByte() }, "0605001500000000".hexToBytes(), msg, 16).toHex())
        assertEquals("0060646bbd1684884c121f17f5fe008e", AesCcm.seal(key, ByteArray(8) { (0x10 + it).toByte() }, "0605001500000000".hexToBytes(), msg, 8).toHex().let { it })
        assertEquals(aad.size, 8)
    }

    @Test fun `ccm rejects a tampered message and bad parameters`() {
        val key = ByteArray(16)
        val sealed = AesCcm.seal(key, ByteArray(12), ByteArray(8), byteArrayOf(1, 2, 3), 8)
        assertFailsWith<Exception> { AesCcm.open(key, ByteArray(12), ByteArray(8), sealed.also { it[0] = (it[0] + 1).toByte() }, 8) }
        assertFailsWith<Exception> { AesCcm.seal(key, ByteArray(5), ByteArray(0), ByteArray(1), 8) } // nonce too short
        assertFailsWith<Exception> { AesCcm.seal(key, ByteArray(12), ByteArray(0), ByteArray(1), 7) } // odd tag
    }

    @Test fun `a ccm channel produces the independent sealed message and the peer opens it`() {
        val key = ByteArray(16) { it.toByte() }
        val phone = ChannelCipher(key, ByteArray(8) { (0x10 + it).toByte() }, ChannelCipher.Suite.AES_CCM, authTagBytes = 16)
        val device = phone.asDeviceSide()
        val msg = "1004020008021001".hexToBytes()
        val sealed = phone.seal(msg, seq = 0)
        assertEquals("0605001800000000ce2e2eab8b486edd25792021d94508bccf16c55a8820fb9b", sealed.toHex())
        assertEquals(msg.toHex(), device.open(sealed).toHex())
        assertEquals(msg.toHex(), phone.open(device.seal(msg, seq = 7)).toHex())
    }

    @Test fun `a ccm channel with the short nonce and tag matches too`() {
        val key = ByteArray(16) { it.toByte() }
        val phone = ChannelCipher(key, ByteArray(4) { (0x20 + it).toByte() }, ChannelCipher.Suite.AES_CCM, authTagBytes = 8)
        assertEquals("06050010000000013e7da46bbaf7468b6373b869980f904e", phone.seal("1004020008021001".hexToBytes(), seq = 1).toHex())
    }

    @Test fun `cipher suite maps from the proto enum`() {
        assertEquals(ChannelCipher.Suite.AES_CCM, ChannelCipher.Suite.fromProto(1))
        assertEquals(ChannelCipher.Suite.AES_GCM, ChannelCipher.Suite.fromProto(2))
        assertEquals(ChannelCipher.Suite.CHACHA20_POLY1305, ChannelCipher.Suite.fromProto(3))
        assertEquals(null, ChannelCipher.Suite.fromProto(0))
    }

    // ── Handshake math: ECDHE + HKDF channel keys round-trip between two peers ────────────

    @Test fun `two peers derive the same channel key via ecdhe and hkdf`() {
        val client = BragiHandshake.generateEphemeral()
        val server = BragiHandshake.generateEphemeral()
        val sClient = BragiHandshake.ecdh(client.privateKey, server.publicPoint)
        val sServer = BragiHandshake.ecdh(server.privateKey, client.publicPoint)
        assertEquals(sClient.toHex(), sServer.toHex())

        val rc = ByteArray(16) { 1 }; val rs = ByteArray(16) { 2 }
        // one channel: key_length 16, nonce_length 8 (salt 4)
        val okmC = Hkdf.derive(rc + rs, sClient, ByteArray(0), 16 + 4)
        val okmS = Hkdf.derive(rc + rs, sServer, ByteArray(0), 16 + 4)
        assertEquals(okmC.toHex(), okmS.toHex())
        assertEquals(65, client.publicPoint.size) // SEC-1 uncompressed P-256 point
    }

    @Test fun `client signature over ephemeral and random verifies with the identity key`() {
        val id = BragiHandshake.generateIdentity()
        val eph = BragiHandshake.generateEphemeral()
        val random = ByteArray(16) { 7 }
        val sig = BragiHandshake.signAuthenticate(id.privateKey, eph.publicPoint, random)
        assertTrue(BragiHandshake.verifyAuthenticate(id.publicKey, eph.publicPoint, random, sig))
        // Wrong random fails.
        assertTrue(!BragiHandshake.verifyAuthenticate(id.publicKey, eph.publicPoint, ByteArray(16), sig))
    }

    @Test fun `identity key id is the last 8 bytes of sha256 of the public point`() {
        val id = BragiHandshake.generateIdentity()
        val kid = BragiHandshake.identityKeyId(id.publicPointSec1)
        assertEquals(8, kid.size)
        val full = java.security.MessageDigest.getInstance("SHA-256").digest(id.publicPointSec1)
        assertEquals(full.copyOfRange(24, 32).toHex(), kid.toHex())
    }

    @Test fun `passcode is deterministic from the public keys and nonces`() {
        val pcx = ByteArray(32) { 3 }; val psx = ByteArray(32) { 4 }
        val nc = ByteArray(16) { 5 }; val ns = ByteArray(16) { 6 }
        val a = BragiHandshake.passcode(pcx, psx, nc, ns, digits = 6)
        val b = BragiHandshake.passcode(pcx, psx, nc, ns, digits = 6)
        assertEquals(a, b)
        assertTrue(a in 0..999999)
    }
}
