package dev.earshot.protocol.bragi

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM (RFC 3610), built from plain AES blocks because Android's built-in crypto provider has
 * no CCM. The Bragi secure session lists CCM first among its cipher suites, and it is the natural
 * choice on a Bluetooth SoC, so the session must not depend on the platform offering it.
 *
 * CCM is a CBC-MAC over (flags, nonce, length), the associated data and the message, plus counter
 * mode encryption of the message and of the tag. Tag length 4..16 (even), nonce length 7..13.
 * Verified against RFC 3610 packet vector 1 and an independent implementation (see the tests).
 */
internal object AesCcm {
    private const val BLOCK = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray, tagLength: Int): ByteArray {
        checkParams(nonce, tagLength)
        val aes = aes(key)
        val tag = mac(aes, nonce, aad, plaintext, tagLength)
        val ciphertext = ctr(aes, nonce, plaintext, startCounter = 1)
        val s0 = counterBlock(aes, nonce, 0)
        val encTag = ByteArray(tagLength) { (tag[it].toInt() xor s0[it].toInt()).toByte() }
        return ciphertext + encTag
    }

    /** [ciphertextAndTag] is the ciphertext followed by the encrypted tag. Throws [AEADBadTagException] when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray, tagLength: Int): ByteArray {
        checkParams(nonce, tagLength)
        if (ciphertextAndTag.size < tagLength) throw AEADBadTagException("message shorter than its tag")
        val aes = aes(key)
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - tagLength)
        val encTag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - tagLength, ciphertextAndTag.size)
        val plaintext = ctr(aes, nonce, ciphertext, startCounter = 1)
        val s0 = counterBlock(aes, nonce, 0)
        val received = ByteArray(tagLength) { (encTag[it].toInt() xor s0[it].toInt()).toByte() }
        val expected = mac(aes, nonce, aad, plaintext, tagLength)
        var diff = 0
        for (i in 0 until tagLength) diff = diff or (received[i].toInt() xor expected[i].toInt())
        if (diff != 0) throw AEADBadTagException("CCM tag mismatch")
        return plaintext
    }

    private fun checkParams(nonce: ByteArray, tagLength: Int) {
        if (nonce.size !in 7..13) throw GeneralSecurityException("CCM nonce must be 7..13 bytes, got ${nonce.size}")
        if (tagLength !in 4..16 || tagLength % 2 != 0) throw GeneralSecurityException("CCM tag must be 4..16 even bytes, got $tagLength")
    }

    private fun aes(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    /** The CBC-MAC tag (not yet encrypted): B0, the length-prefixed associated data, then the message, zero padded. */
    private fun mac(aes: Cipher, nonce: ByteArray, aad: ByteArray, message: ByteArray, tagLength: Int): ByteArray {
        val l = 15 - nonce.size
        val b0 = ByteArray(BLOCK)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tagLength - 2) / 2) shl 3) or (l - 1)).toByte()
        nonce.copyInto(b0, 1)
        // Long shifts: with a short nonce the length field is up to 7 bytes, and an Int shift of 32+ wraps around.
        for (i in 0 until l) b0[15 - i] = (message.size.toLong() ushr (8 * i)).toByte()
        var x = aes.doFinal(b0)
        fun absorb(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val block = ByteArray(BLOCK)
                data.copyInto(block, 0, off, minOf(off + BLOCK, data.size))
                for (i in 0 until BLOCK) block[i] = (block[i].toInt() xor x[i].toInt()).toByte()
                x = aes.doFinal(block)
                off += BLOCK
            }
        }
        if (aad.isNotEmpty()) {
            val prefix = if (aad.size < 0xFF00) byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte())
            else byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (aad.size ushr 24).toByte(), (aad.size ushr 16).toByte(), (aad.size ushr 8).toByte(), aad.size.toByte())
            absorb(prefix + aad)
        }
        absorb(message)
        return x.copyOf(tagLength)
    }

    /** `A_i = (L-1) || nonce || i`, encrypted: the keystream block for counter [i]. */
    private fun counterBlock(aes: Cipher, nonce: ByteArray, i: Int): ByteArray {
        val l = 15 - nonce.size
        val a = ByteArray(BLOCK)
        a[0] = (l - 1).toByte()
        nonce.copyInto(a, 1)
        for (k in 0 until l) a[15 - k] = (i.toLong() ushr (8 * k)).toByte()
        return aes.doFinal(a)
    }

    private fun ctr(aes: Cipher, nonce: ByteArray, data: ByteArray, startCounter: Int): ByteArray {
        val out = ByteArray(data.size)
        var counter = startCounter
        var off = 0
        while (off < data.size) {
            val s = counterBlock(aes, nonce, counter++)
            val n = minOf(BLOCK, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor s[i].toInt()).toByte()
            off += n
        }
        return out
    }
}
