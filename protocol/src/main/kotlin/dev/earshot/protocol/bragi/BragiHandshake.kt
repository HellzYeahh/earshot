package dev.earshot.protocol.bragi

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptographic steps of Bragi's `authenticated_access` service, per the shipped
 * `bos_authenticated_access.proto`. Only needed when the device sets
 * `dev_info.authenticated_access`; everything is standard JCA, with keys generated locally and a
 * passcode the user compares on both devices (a normal numeric-comparison pairing).
 *
 * Curve: NIST P-256. Points are SEC-1 encoded (uncompressed 0x04 || X || Y, 65 octets).
 * Identity-key id: last 8 octets of SHA-256(SEC-1 point).
 * Authenticate signature: ECDSA-P256 over SHA-512(ephemeral_key || random), DER encoded.
 * Session secret: x-coordinate of ECDH(ephemeral keys); channel keys and salts come from
 * HKDF-SHA256 with PRK = extract(client_random || server_random, secret) and an empty info.
 */
object BragiHandshake {

    private const val CURVE = "secp256r1"
    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** An EC key pair with its SEC-1 point. [publicPointSec1] is the same bytes as [publicPoint]. */
    class EcKeyPair(private val pair: KeyPair) {
        val privateKey: PrivateKey get() = pair.private
        val publicKey: PublicKey get() = pair.public
        val publicPoint: ByteArray = encodePoint((pair.public as ECPublicKey).w)
        val publicPointSec1: ByteArray get() = publicPoint

        /** The private key as PKCS#8, for storing the identity (the caller must protect it at rest). */
        fun privateKeyPkcs8(): ByteArray = pair.private.encoded

        companion object {
            /** Rebuild a key pair stored with [privateKeyPkcs8] and [publicPoint]. */
            fun restore(pkcs8: ByteArray, publicPoint: ByteArray): EcKeyPair = EcKeyPair(
                KeyPair(decodePublicKey(publicPoint), KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))),
            )
        }
    }

    fun generateEphemeral(): EcKeyPair = generate()
    fun generateIdentity(): EcKeyPair = generate()

    private fun generate(): EcKeyPair = EcKeyPair(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair(),
    )

    // ── SEC-1 points ─────────────────────────────────────────────────────────────────────

    fun encodePoint(w: ECPoint): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        fixed32(w.affineX).copyInto(out, 1)
        fixed32(w.affineY).copyInto(out, 33)
        return out
    }

    /** Decode an uncompressed SEC-1 point to a public key. Compressed points are not supported. */
    fun decodePublicKey(point: ByteArray): PublicKey {
        require(point.size == 65 && point[0] == 0x04.toByte()) { "expected a 65-octet uncompressed P-256 point" }
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        // KeyFactory validates that the point is on the curve; an invalid point throws here.
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** The 32-octet big-endian X-coordinate of a SEC-1 point. */
    fun pointX(point: ByteArray): ByteArray {
        require(point.size >= 33) { "point too short" }
        return point.copyOfRange(1, 33)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        src.copyInto(out, 32 - src.size)
        return out
    }

    // ── ECDH ─────────────────────────────────────────────────────────────────────────────

    /** The shared secret: the x-coordinate of ECDH, 32 octets big-endian. */
    fun ecdh(privateKey: PrivateKey, peerPoint: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(decodePublicKey(peerPoint), true)
            generateSecret().let { if (it.size == 32) it else fixed32(BigInteger(1, it)) }
        }

    // ── Identity key id ──────────────────────────────────────────────────────────────────

    fun identityKeyId(publicPointSec1: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicPointSec1).copyOfRange(24, 32)

    // ── Signatures ───────────────────────────────────────────────────────────────────────

    /** Client signature for `authenticate`: ECDSA-P256 / SHA-512 over (ephemeral point || random). */
    fun signAuthenticate(identityPrivateKey: PrivateKey, ephemeralPoint: ByteArray, random: ByteArray): ByteArray =
        Signature.getInstance("SHA512withECDSA").run {
            initSign(identityPrivateKey); update(ephemeralPoint); update(random); sign()
        }

    fun verifyAuthenticate(identityPublicKey: PublicKey, ephemeralPoint: ByteArray, random: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA512withECDSA").run {
                initVerify(identityPublicKey); update(ephemeralPoint); update(random); verify(signature)
            }
        }.getOrDefault(false)

    /** One protected channel as the server announces it in `ServerAuthenticateResponse`. */
    data class ChannelSpec(
        val id: Int, val transport: Int, val cipherSuite: Int,
        val keyLength: Int, val nonceLength: Int, val authTagLength: Int,
    ) {
        /** The 6 explicit octets this channel contributes to the signed message (no protobuf involved). */
        fun signedBytes(): ByteArray = byteArrayOf(
            id.toByte(), transport.toByte(), cipherSuite.toByte(),
            keyLength.toByte(), nonceLength.toByte(), authTagLength.toByte(),
        )
        val saltLength: Int get() = nonceLength - 4
    }

    /**
     * The byte string the server signs (ECDSA-P256 / SHA-512): client ephemeral (65) || client random
     * (16) || server ephemeral (65) || server random (16) || each channel's 6 octets in the order
     * received. Verify the server's final signature over exactly this.
     */
    fun serverSignedMessage(
        clientEphemeral: ByteArray, clientRandom: ByteArray,
        serverEphemeral: ByteArray, serverRandom: ByteArray, channels: List<ChannelSpec>,
    ): ByteArray = clientEphemeral + clientRandom + serverEphemeral + serverRandom +
        channels.fold(ByteArray(0)) { acc, c -> acc + c.signedBytes() }

    fun verifyServerAuthenticate(serverIdentity: PublicKey, signedMessage: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA512withECDSA").run { initVerify(serverIdentity); update(signedMessage); verify(signature) }
        }.getOrDefault(false)

    // ── Session keys ─────────────────────────────────────────────────────────────────────

    /**
     * Derive one [ChannelCipher] per announced channel. Key material is laid out in channel order,
     * each channel taking `key_length` then `nonce_length - 4` octets of salt.
     */
    fun deriveChannelCiphers(
        sharedSecret: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray, channels: List<ChannelSpec>,
    ): List<Pair<ChannelSpec, ChannelCipher>> {
        val total = channels.sumOf { it.keyLength + it.saltLength }
        val okm = Hkdf.derive(clientRandom + serverRandom, sharedSecret, ByteArray(0), total)
        var pos = 0
        return channels.mapNotNull { spec ->
            val key = okm.copyOfRange(pos, pos + spec.keyLength); pos += spec.keyLength
            val salt = okm.copyOfRange(pos, pos + spec.saltLength); pos += spec.saltLength
            val suite = ChannelCipher.Suite.fromProto(spec.cipherSuite) ?: return@mapNotNull null
            spec to ChannelCipher(key, salt, suite, spec.authTagLength)
        }
    }

    // ── Trust establishment (one-time, user-confirmed) ───────────────────────────────────

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun right16(b: ByteArray): ByteArray = b.copyOfRange(b.size - 16, b.size)

    /** `commitment` = rightmost 16 octets of HMAC-SHA256(nonce_client, pub_client_x || pub_server_x). */
    fun commitment(nonceClient: ByteArray, pubClientX: ByteArray, pubServerX: ByteArray): ByteArray =
        right16(hmac(nonceClient, pubClientX + pubServerX))

    /** Confirmation = rightmost 16 octets of HMAC-SHA256(dhkey, nonce_local || nonce_remote). dhkey must not be all zero. */
    fun confirmation(dhKey: ByteArray, nonceLocal: ByteArray, nonceRemote: ByteArray): ByteArray {
        require(dhKey.any { it != 0.toByte() }) { "all-zero ECDH key: abort the trust establishment" }
        return right16(hmac(dhKey, nonceLocal + nonceRemote))
    }

    /**
     * The numeric-comparison passcode: SHA-256(pub_client_x || pub_server_xs || nonce_client ||
     * nonce_server) as a big-endian number, mod 2^32, mod 10^digits. Both devices show the same
     * digits and the user confirms they match. [digits] must be 0..6 (0 = no passcode, only a gesture).
     */
    fun passcode(pubClientX: ByteArray, pubServerXs: ByteArray, nonceClient: ByteArray, nonceServer: ByteArray, digits: Int): Int {
        require(digits in 0..6) { "passcode digits must be 0..6" }
        if (digits == 0) return 0
        val h = MessageDigest.getInstance("SHA-256").digest(pubClientX + pubServerXs + nonceClient + nonceServer)
        val low32 = ((h[28].toLong() and 0xFF) shl 24) or ((h[29].toLong() and 0xFF) shl 16) or
            ((h[30].toLong() and 0xFF) shl 8) or (h[31].toLong() and 0xFF)
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (low32 % mod).toInt()
    }
}
