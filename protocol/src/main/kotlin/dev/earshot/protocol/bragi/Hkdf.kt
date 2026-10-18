package dev.earshot.protocol.bragi

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869), used by the Bragi authenticated-access session to turn the ECDH shared
 * secret into per-channel keys and salts. The app hand-rolls it with HmacSHA256 exactly as below.
 *
 * For the secure session: PRK = extract(salt = client_random || server_random, ikm = s), then
 * OKM = expand(PRK, info = empty, L); L is the sum over channels of key_length + (nonce_length - 4).
 */
internal object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "HKDF length $length too large" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256")); doFinal(data) }
}
