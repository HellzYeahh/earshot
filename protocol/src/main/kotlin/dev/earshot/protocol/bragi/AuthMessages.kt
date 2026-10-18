package dev.earshot.protocol.bragi

/**
 * Wire messages of the `authenticated_access` service (service id 25): the one-time trust
 * establishment (a numeric comparison the user confirms on both devices) and the per-session
 * ECDHE handshake. Field numbers follow the protocol definitions the Skullcandy app ships; the
 * crypto behind them is in [BragiHandshake].
 *
 * Nested messages used below:
 *  - `IdentityKeyID { 1: bytes key_id }` (8 octets)
 *  - `IdentityKey { 1: bytes nist_p256 }` and `ECDHEKey { 1: bytes nist_p256 }` (SEC-1 points)
 *  - `Signature { 1: bytes ecdsa_p256_sha512 }` (ASN.1 DER)
 *  - `ChannelCipher { 1: id, 2: transport, 3: cipher_suite, 4: key_length, 5: nonce_length, 6: authentication_tag_length }`
 */
object AuthMessages {

    // ── Small nested wrappers ────────────────────────────────────────────────────────────

    private fun bytesMessage(bytes: ByteArray): ByteArray = ProtoWriter.build { bytes(1, bytes) }
    private fun firstBytes(body: ByteArray): ByteArray? = ProtoReader.map(body)[1]?.bytes

    // ── hello ────────────────────────────────────────────────────────────────────────────

    /** `ClientHelloRequest { 1: IdentityKeyID key_id }`. */
    fun clientHello(clientKeyId: ByteArray): ByteArray = ProtoWriter.build { message(1, bytesMessage(clientKeyId)) }

    /** `ServerHelloResponse { repeated 1: IdentityKeyID key_id }` → the server's key ids (a headset has one). */
    fun parseServerHello(body: ByteArray): List<ByteArray> =
        ProtoReader.repeated(body, 1).mapNotNull { firstBytes(it) }

    fun parseClientHello(body: ByteArray): ByteArray? =
        ProtoReader.map(body)[1]?.bytes?.let { firstBytes(it) }

    fun serverHello(serverKeyIds: List<ByteArray>): ByteArray = ProtoWriter.build {
        serverKeyIds.forEach { message(1, bytesMessage(it)) }
    }

    // ── authenticate ─────────────────────────────────────────────────────────────────────

    /** `ClientAuthenticateRequest { 1: ECDHEKey, 2: bytes random(16), 3: Signature }`. */
    fun clientAuthenticate(ephemeralPoint: ByteArray, random: ByteArray, signatureDer: ByteArray): ByteArray = ProtoWriter.build {
        message(1, bytesMessage(ephemeralPoint))
        bytes(2, random)
        message(3, bytesMessage(signatureDer))
    }

    class ClientAuthenticate(val ephemeralPoint: ByteArray, val random: ByteArray, val signature: ByteArray)

    fun parseClientAuthenticate(body: ByteArray): ClientAuthenticate? {
        val f = ProtoReader.map(body)
        return ClientAuthenticate(
            ephemeralPoint = f[1]?.bytes?.let { firstBytes(it) } ?: return null,
            random = f[2]?.bytes ?: return null,
            signature = f[3]?.bytes?.let { firstBytes(it) } ?: return null,
        )
    }

    /** One `ServerAuthenticateResponse` message: any subset of the fields (the answer is streamed). */
    class ServerAuthenticatePart(
        val ephemeralPoint: ByteArray?, val random: ByteArray?,
        val channel: BragiHandshake.ChannelSpec?, val signature: ByteArray?,
    )

    fun parseServerAuthenticate(body: ByteArray): ServerAuthenticatePart {
        val f = ProtoReader.map(body)
        return ServerAuthenticatePart(
            ephemeralPoint = f[1]?.bytes?.let { firstBytes(it) },
            random = f[2]?.bytes,
            channel = f[3]?.bytes?.let { parseChannel(it) },
            signature = f[4]?.bytes?.let { firstBytes(it) },
        )
    }

    fun serverAuthenticate(
        ephemeralPoint: ByteArray? = null, random: ByteArray? = null,
        channel: BragiHandshake.ChannelSpec? = null, signature: ByteArray? = null,
    ): ByteArray = ProtoWriter.build {
        ephemeralPoint?.let { message(1, bytesMessage(it)) }
        random?.let { bytes(2, it) }
        channel?.let { message(3, channelMessage(it)) }
        signature?.let { message(4, bytesMessage(it)) }
    }

    private fun channelMessage(c: BragiHandshake.ChannelSpec): ByteArray = ProtoWriter.build {
        enum(1, c.id); enum(2, c.transport); enum(3, c.cipherSuite)
        uint32(4, c.keyLength); uint32(5, c.nonceLength); uint32(6, c.authTagLength)
    }

    private fun parseChannel(body: ByteArray): BragiHandshake.ChannelSpec {
        val f = ProtoReader.map(body)
        return BragiHandshake.ChannelSpec(
            id = f[1]?.int ?: 0, transport = f[2]?.int ?: 0, cipherSuite = f[3]?.int ?: 0,
            keyLength = f[4]?.int ?: 0, nonceLength = f[5]?.int ?: 0, authTagLength = f[6]?.int ?: 0,
        )
    }

    // ── trust establishment ──────────────────────────────────────────────────────────────

    /** `TrustEstablishmentStartRequest { 1: IdentityKey key, 3: uint32 passcode_digits }` (language omitted: the headphones' own is used). */
    fun trustStart(identityPoint: ByteArray, passcodeDigits: Int): ByteArray = ProtoWriter.build {
        message(1, bytesMessage(identityPoint))
        uint32(3, passcodeDigits)
    }

    class TrustStart(val identityPoint: ByteArray, val passcodeDigits: Int)

    fun parseTrustStart(body: ByteArray): TrustStart? {
        val f = ProtoReader.map(body)
        return TrustStart(f[1]?.bytes?.let { firstBytes(it) } ?: return null, f[3]?.int ?: 0)
    }

    /** `TrustEstablishmentStartResponse { repeated 1: IdentityKey key }` → the server's identity keys. */
    fun parseTrustStartResponse(body: ByteArray): List<ByteArray> =
        ProtoReader.repeated(body, 1).mapNotNull { firstBytes(it) }

    fun trustStartResponse(serverPoints: List<ByteArray>): ByteArray = ProtoWriter.build {
        serverPoints.forEach { message(1, bytesMessage(it)) }
    }

    /** `TrustEstablishmentCommitRequest { 1: bytes commitment(16) }`. */
    fun trustCommit(commitment: ByteArray): ByteArray = bytesMessage(commitment)
    fun parseTrustCommit(body: ByteArray): ByteArray? = firstBytes(body)

    /** `TrustEstablishmentNonceResponse { 1: bytes nonce }` and `TrustEstablishmentNonceRequest { 1: bytes nonce }`. */
    fun nonce(nonce: ByteArray): ByteArray = bytesMessage(nonce)
    fun parseNonce(body: ByteArray): ByteArray? = firstBytes(body)

    /** `TrustEstablishmentConfirmationNotification { 1: bytes confirmation_check(16) }`. */
    fun confirmation(check: ByteArray): ByteArray = bytesMessage(check)
    fun parseConfirmation(body: ByteArray): ByteArray? = firstBytes(body)

    /** `TrustEstablishmentAbortRequest { 1: ServiceError reason }`. */
    fun trustAbort(reason: Int): ByteArray = ProtoWriter.build { enum(1, reason) }

    /** The `ServiceError` values the client sends in an abort. */
    object Error {
        const val IDENTITY_KEY_INVALID = 2
        const val CONFIRMATION_INVALID = 7
        const val PASSCODE_REJECTED = 8
        const val PASSCODE_TIMEOUT = 9
        const val CONFIRMATION_TIMEOUT = 11
        const val PAIRING_ABORTED = 13
    }
}
