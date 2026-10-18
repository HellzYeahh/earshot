package dev.earshot.protocol.bragi

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Bragi "Encrypted Data Message" that wraps a BOS-RPC packet once a secure session exists
 * (PPP protocol id 0x95). Only needed if the Crusher asserts `dev_info.authenticated_access`; the
 * cleartext path (0x94) uses none of this. All primitives are standard JCA.
 *
 * ```
 * 0      0x06                 message type
 * 1      0x05                 flags (len16 present | seq32 present); the app always emits 0x05
 * 2..3   len   (big-endian)   = plaintext.length + authTagLength
 * 4..7   seq   (big-endian)   per-direction sequence number, starts 0
 * 8..    ciphertext || tag    AEAD output; the whole 8-byte header is the AAD
 * ```
 * Nonce = salt' || seq32, where salt' is the channel salt with the LSB of its last byte set to the
 * direction bit (0 = phone→device, 1 = device→phone). Same key and salt in both directions.
 */
class ChannelCipher(
    private val key: ByteArray,
    /** Channel salt: nonce_length - 4 octets (so 4 → 8-byte nonce, or 8 → 12-byte nonce). */
    private val salt: ByteArray,
    private val suite: Suite,
    private val authTagBytes: Int = 16,
    /**
     * False for the phone (seals with direction bit 0, opens bit 1). True models the headphones'
     * side, which the virtual Crusher and the tests need: same key and salt, opposite direction bit.
     */
    private val asDevice: Boolean = false,
) {
    enum class Suite(val jcaTransform: String, val keyAlgo: String) {
        AES_CCM("AES/CCM/NoPadding", "AES"),
        AES_GCM("AES/GCM/NoPadding", "AES"),
        CHACHA20_POLY1305("ChaCha20-Poly1305", "ChaCha20");

        companion object {
            /** The proto `CipherSuite` enum: AES_CCM=1, AES_GCM=2, CHACHA20_POLY1305=3. */
            fun fromProto(v: Int) = when (v) { 1 -> AES_CCM; 2 -> AES_GCM; 3 -> CHACHA20_POLY1305; else -> null }
        }
    }

    private fun nonce(seq: Long, outbound: Boolean): ByteArray {
        val n = ByteArray(salt.size + 4)
        salt.copyInto(n)
        if (n.isNotEmpty()) {
            val dirBit = if (outbound) 0 else 1
            n[salt.size - 1] = ((salt[salt.size - 1].toInt() and 0xFE) or dirBit).toByte()
        }
        n[salt.size] = ((seq ushr 24) and 0xFF).toByte()
        n[salt.size + 1] = ((seq ushr 16) and 0xFF).toByte()
        n[salt.size + 2] = ((seq ushr 8) and 0xFF).toByte()
        n[salt.size + 3] = (seq and 0xFF).toByte()
        return n
    }

    private fun header(bodyLen: Int, seq: Long): ByteArray = byteArrayOf(
        0x06, 0x05,
        ((bodyLen ushr 8) and 0xFF).toByte(), (bodyLen and 0xFF).toByte(),
        ((seq ushr 24) and 0xFF).toByte(), ((seq ushr 16) and 0xFF).toByte(),
        ((seq ushr 8) and 0xFF).toByte(), (seq and 0xFF).toByte(),
    )

    private fun cipher(mode: Int, nonce: ByteArray): Cipher {
        val c = Cipher.getInstance(suite.jcaTransform)
        val keySpec = SecretKeySpec(key, suite.keyAlgo)
        when (suite) {
            Suite.CHACHA20_POLY1305 -> c.init(mode, keySpec, IvParameterSpec(nonce))
            else -> c.init(mode, keySpec, GCMParameterSpec(authTagBytes * 8, nonce))
        }
        return c
    }

    /** Wrap [plaintext] (a BOS-RPC packet) into an encrypted-data message with sequence [seq]. */
    fun seal(plaintext: ByteArray, seq: Long): ByteArray {
        val header = header(plaintext.size + authTagBytes, seq)
        val c = cipher(Cipher.ENCRYPT_MODE, nonce(seq, outbound = !asDevice))
        c.updateAAD(header)
        val body = c.doFinal(plaintext)
        return header + body
    }

    /** Unwrap an inbound encrypted-data message; returns the BOS-RPC plaintext. */
    fun open(message: ByteArray): ByteArray {
        require(message.size >= 8) { "encrypted-data message shorter than its header" }
        val header = message.copyOfRange(0, 8)
        val seq = ((message[4].toLong() and 0xFF) shl 24) or ((message[5].toLong() and 0xFF) shl 16) or
            ((message[6].toLong() and 0xFF) shl 8) or (message[7].toLong() and 0xFF)
        val c = cipher(Cipher.DECRYPT_MODE, nonce(seq, outbound = asDevice))
        c.updateAAD(header)
        return c.doFinal(message, 8, message.size - 8)
    }

    companion object {
        /** proto `ChannelID`: BRAGI_CONTROL=1, VENDOR_CONTROL=2, MICROPHONE_AUDIO=3. */
        const val CHANNEL_BRAGI_CONTROL = 1
        /** proto `TransportType`: SPP=1, IAP2=2, GATT=3. */
        const val TRANSPORT_SPP = 1
    }
}
