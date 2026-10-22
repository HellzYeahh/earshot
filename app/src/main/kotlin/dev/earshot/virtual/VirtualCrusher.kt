package dev.earshot.virtual

import dev.earshot.protocol.bragi.AuthMessages
import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiHandshake
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.ChannelCipher
import dev.earshot.protocol.bragi.Hearing
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.protocol.bragi.ProtoWriter
import dev.earshot.protocol.bragi.SpatialMode
import java.security.SecureRandom

/**
 * An in-process stand-in for the Skullcandy Crusher 1080 ANC, speaking BOS-RPC the way the
 * Skullcandy app's definitions describe it. It is the device side of every call Earshot makes, so
 * the real [dev.earshot.bt.BragiConnection] and protocol layer can be exercised end to end without
 * the headphones (unit tests, demo mode).
 *
 * What it models: hearing mode (Off / ANC "Quiet" / AT "Aware") with gain, sidetone, parametric EQ,
 * TrueSpatial, Crusher bass and battery. Streaming reads ([BragiServices.MODE_READ] and friends)
 * answer with the current state and then push every change until aborted, like the real firmware.
 *
 * With [authenticatedAccess] it also plays the secure-session side: a one-time pairing whose
 * passcode it derives like the real headset (and confirms itself unless [autoConfirmOnDevice] is
 * off), then a signed ECDHE session, after which hearing, spatial audio and Crusher calls are only
 * accepted encrypted. Before that they are refused, as the protocol describes for protected channels.
 *
 * It does not claim to match hardware byte for byte where the app's definitions are silent (the
 * STATUS body encoding, for one): those spots follow the most likely reading and are marked.
 *
 * Thread-safety: every public method is synchronised.
 */
class VirtualCrusher(
    /** Whether the unit announces `authenticated_access` in `dev_info` (the secure-session gate). */
    val authenticatedAccess: Boolean = false,
    /** Whether the user "presses the button on the headphones" at once during pairing. */
    var autoConfirmOnDevice: Boolean = true,
    /** The secure channel the headphones announce: proto cipher suite (1 CCM, 2 GCM, 3 ChaCha20-Poly1305), key, nonce and tag lengths. */
    private val channelSuite: Int = 2,
    private val channelKeyLength: Int = 16,
    private val channelNonceLength: Int = 12,
    private val channelTagLength: Int = 16,
    /** The largest sidetone volume the unit reports (the real range is read from the headphones). */
    val sidetoneVolumeMax: Int = 5,
    /** `dev_info.model` and `model_number`: the Crusher 1080 by default; the Method earbuds' own come from [method360] / [method540]. */
    val model: String = "CHP300",
    val modelNumber: String = "S6CNW",
    /** A Method earbud has neither the Crusher bass nor spatial audio: those services answer "unknown method", as firmware without them would. */
    val hasCrusherBass: Boolean = true,
    val hasSpatialAudio: Boolean = true,
) {
    private val random = SecureRandom()
    private val serverIdentity: BragiHandshake.EcKeyPair by lazy { BragiHandshake.generateIdentity() }

    /** The client identity key the pairing trusted (SEC-1 point), or null before a pairing completed. */
    @Volatile var trustedClient: ByteArray? = null; private set

    /** The passcode derived in the last pairing, so a test or a demo can show what the headphones "say". */
    @Volatile var lastPasscode: Int? = null; private set

    /** The device end of the session cipher once a secure session exists. */
    @Volatile var secureCipher: ChannelCipher? = null; private set
    private var sendSeq = 0L

    private class Pairing(val clientPoint: ByteArray, val digits: Int) {
        var commitment: ByteArray? = null
        var nonceServer: ByteArray? = null
        var expectedClientConfirmation: ByteArray? = null
    }
    private var pairing: Pairing? = null
    // ── Identity ─────────────────────────────────────────────────────────────────────────
    val firmware = "1.0.0-virtual"
    val bosVersion = "bos-virtual"
    val serial = "VIRTUAL-CRUSHER-1080"

    // ── State ────────────────────────────────────────────────────────────────────────────
    var hearingMode = HearingMode.ANC; private set
    var ancPreset = 1; private set
    var ancGain = 7; private set
    var atPreset = 1; private set
    var atGain = 5; private set
    val ancGainMax = 10
    val atGainMax = 10

    var spatial = SpatialMode.OFF; private set
    var spatialAvailable = true

    var sidetoneEnabled = true; private set
    var sidetoneVolume = 3; private set

    var crusherActive = true; private set
    var crusherIntensity = 5; private set
    var crusherMode = 1; private set
    val crusherMaxIntensity = 10
    val crusherModeCount = 3

    var batteryPercent = 68
    var charging = false

    var peqEnabled = false; private set
    var peqCurrent = 0; private set
    val peqBands = mutableListOf<Hearing.PeqBand>()

    /** Every request that reached the device, in order (tests assert on this). */
    val received = mutableListOf<BosRpcPacket>()

    private data class Sub(val address: BragiServices.Address, val tag: Int)
    private val subs = mutableListOf<Sub>()

    // ── Dispatch ─────────────────────────────────────────────────────────────────────────

    /** Services that need the secure session when [authenticatedAccess] is on (everything but device and the handshake itself). */
    fun isProtected(service: Int): Boolean =
        authenticatedAccess && service != BragiServices.DEVICE && service != BragiServices.AUTHENTICATED_ACCESS

    /** Encrypt a reply for a protected service; the transport frames it with protocol id 0x95. */
    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray = secureCipher!!.seal(plaintext, sendSeq++)

    @Synchronized
    fun open(message: ByteArray): ByteArray? = runCatching { secureCipher?.open(message) }.getOrNull()

    /** Handle one request; returns the frames to send back, in order (responses, then any pushes). [secure] = it arrived encrypted. */
    @Synchronized
    fun handle(packet: BosRpcPacket, secure: Boolean = false): List<BosRpcPacket> {
        received += packet
        val a = packet.address
        if (a.service == BragiServices.AUTHENTICATED_ACCESS && packet.type == BosMessageType.REQUEST) return handleAuth(packet)
        if (isProtected(a.service) && !secure && packet.type == BosMessageType.REQUEST) {
            return listOf(BosRpcPacket(BosMessageType.STATUS, a.service, a.method, packet.tag, byteArrayOf(NOT_AUTHENTICATED)))
        }
        if (packet.type == BosMessageType.ABORT) {
            subs.removeAll { it.address == a }
            return emptyList()
        }
        if (packet.type != BosMessageType.REQUEST) return emptyList()
        if ((a.service == BragiServices.CRUSHER && !hasCrusherBass) || (a.service == BragiServices.SPATIAL_AUDIO && !hasSpatialAudio)) {
            return listOf(BosRpcPacket(BosMessageType.STATUS, a.service, a.method, packet.tag, byteArrayOf(UNKNOWN_METHOD)))
        }
        val out = ArrayList<BosRpcPacket>()
        val tag = packet.tag
        fun final(payload: ByteArray) { out += reply(a, tag, payload, final = true); out += status(a, tag) }
        fun ok() { out += status(a, tag) }
        fun stream(payload: ByteArray) { out += reply(a, tag, payload, final = false) }

        when (a) {
            BragiServices.DEV_INFO -> final(devInfo())
            BragiServices.BATT_INFO -> final(battInfo())
            BragiServices.HEARING_MODE_INFO -> final(ProtoWriter.build {
                message(1, ProtoWriter.build { uint32(1, 1, true); uint32(2, ancGainMax, true) })
                message(2, ProtoWriter.build { uint32(1, 1, true); uint32(2, atGainMax, true) })
            })
            BragiServices.MODE_READ -> { subscribe(a, tag, packet); stream(modeState()) }
            BragiServices.MODE_CONFIGURE -> { configureMode(packet.payload); ok(); out += pushes(BragiServices.MODE_READ, modeState()) }
            BragiServices.SIDETONE_INFO -> final(ProtoWriter.build { uint32(1, sidetoneVolumeMax, true) })
            BragiServices.SIDETONE_READ -> { subscribe(a, tag, packet); stream(sidetoneState()) }
            BragiServices.SIDETONE_CONFIGURE -> { configureSidetone(packet.payload); ok(); out += pushes(BragiServices.SIDETONE_READ, sidetoneState()) }
            BragiServices.SPATIAL_INFO_GET -> { subscribe(a, tag, packet); stream(spatialInfo()) }
            BragiServices.SPATIAL_MODE_SET -> { setSpatial(packet.payload); ok(); out += pushes(BragiServices.SPATIAL_INFO_GET, spatialInfo()) }
            BragiServices.CRUSHER_CONFIG_GET -> final(ProtoWriter.build {
                uint32(1, crusherMaxIntensity, true); bool(2, false); uint32(3, crusherModeCount, true)
            })
            BragiServices.CRUSHER_STATE_GET -> { subscribe(a, tag, packet); stream(crusherState()) }
            BragiServices.CRUSHER_STATE_SET -> { setCrusher(packet.payload); final(crusherState()); out += pushes(BragiServices.CRUSHER_STATE_GET, crusherState()) }
            BragiServices.PEQ_INFO -> final(peqInfo())
            BragiServices.PEQ_READ_STATE -> { subscribe(a, tag, packet); stream(peqState()) }
            BragiServices.PEQ_CONFIGURE -> { configurePeq(packet.payload); ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            BragiServices.PEQ_ENABLE -> { peqEnabled = true; ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            BragiServices.PEQ_DISABLE -> { peqEnabled = false; ok(); out += pushes(BragiServices.PEQ_READ_STATE, peqState()) }
            else -> out += BosRpcPacket(BosMessageType.STATUS, a.service, a.method, tag, byteArrayOf(UNKNOWN_METHOD))
        }
        return out
    }

    /** A button press or app elsewhere changes the mode: push to subscribers. Tests and demo mode use it. */
    @Synchronized
    fun setHearingModeOnDevice(mode: HearingMode): List<BosRpcPacket> { hearingMode = mode; return pushes(BragiServices.MODE_READ, modeState()) }

    // ── Replies ──────────────────────────────────────────────────────────────────────────

    private fun reply(a: BragiServices.Address, tag: Int, payload: ByteArray, final: Boolean) =
        BosRpcPacket(BosMessageType.RESPONSE, a.service, a.method, tag, payload, isFinal = final)

    /** STATUS with an empty body = success (the body encoding of errors is not confirmed on hardware). */
    private fun status(a: BragiServices.Address, tag: Int) =
        BosRpcPacket(BosMessageType.STATUS, a.service, a.method, tag)

    private fun subscribe(a: BragiServices.Address, tag: Int, p: BosRpcPacket) {
        subs.removeAll { it.address == a }
        // subscribe = true is field 1 set to a non-zero varint; an empty body means false (proto3 default).
        val wantsPush = p.payload.size >= 2 && p.payload[0] == 0x08.toByte() && p.payload[1] != 0.toByte()
        if (wantsPush) subs += Sub(a, tag)
    }

    private fun pushes(a: BragiServices.Address, payload: ByteArray): List<BosRpcPacket> =
        subs.filter { it.address == a }.map { reply(a, it.tag, payload, final = false) }

    // ── Payloads ─────────────────────────────────────────────────────────────────────────

    private fun devInfo() = ProtoWriter.build {
        string(3, "Skullcandy"); string(4, model); string(5, firmware); string(7, bosVersion)
        string(9, serial); string(11, modelNumber); bool(12, authenticatedAccess)
    }

    private fun battInfo() = ProtoWriter.build {
        message(1, ProtoWriter.build { uint32(2, batteryPercent, true); uint32(3, if (charging) 2 else 1, true) })
    }

    private fun modeState() = ProtoWriter.build {
        enum(1, hearingMode.wire)
        message(2, ProtoWriter.build { uint32(1, ancPreset, true); uint32(2, ancGain, true) })
        message(3, ProtoWriter.build { uint32(1, atPreset, true); uint32(2, atGain, true) })
    }

    private fun sidetoneState() = ProtoWriter.build { bool(1, sidetoneEnabled); uint32(2, sidetoneVolume, true) }
    private fun spatialInfo() = ProtoWriter.build { bool(1, spatialAvailable); enum(2, spatial.wire) }
    private fun crusherState() = ProtoWriter.build { bool(1, crusherActive); uint32(2, crusherIntensity, true); uint32(3, crusherMode, true) }
    private fun peqState() = ProtoWriter.build { bool(1, peqEnabled); uint32(2, peqCurrent, true) }

    private fun peqInfo() = ProtoWriter.build {
        uint32(1, 5, true); uint32(2, 20, true); uint32(3, 20000, true)
        float(4, -12f, true); float(5, 12f, true); uint32(6, 49, true)
        float(7, 0.5f, true); float(8, 10f, true); uint32(9, 20, true)
    }

    // ── Mutations (ranges as the definitions state them) ─────────────────────────────────

    private fun fields(body: ByteArray) = dev.earshot.protocol.bragi.ProtoReader.map(body)

    private fun configureMode(body: ByteArray) {
        val f = fields(body)
        val mode = HearingMode.fromWire(f[1]?.int ?: 0)
        val activate = f[2]?.bool ?: false
        val preset = f[3]?.int
        val gain = f[4]?.int
        when (mode) {
            HearingMode.ANC -> { preset?.let { ancPreset = it }; gain?.let { ancGain = it.coerceIn(0, ancGainMax) } }
            HearingMode.AT -> { preset?.let { atPreset = it }; gain?.let { atGain = it.coerceIn(0, atGainMax) } }
            else -> Unit
        }
        if (activate && mode != HearingMode.UNSPECIFIED) hearingMode = mode
    }

    private fun configureSidetone(body: ByteArray) {
        val f = fields(body)
        f[1]?.let { sidetoneEnabled = it.bool }
        f[2]?.let { sidetoneVolume = it.int.coerceIn(0, sidetoneVolumeMax) }
    }

    private fun setSpatial(body: ByteArray) {
        val m = SpatialMode.fromWire(fields(body)[1]?.int ?: 0)
        if (m != SpatialMode.UNSPECIFIED) spatial = m
    }

    private fun setCrusher(body: ByteArray) {
        val f = fields(body)
        f[1]?.let { crusherActive = it.bool }
        f[2]?.let { crusherIntensity = it.int.coerceIn(0, crusherMaxIntensity) }
        f[3]?.let { crusherMode = it.int.coerceIn(0, crusherModeCount - 1) }
    }

    private fun configurePeq(body: ByteArray) {
        val f = fields(body)
        peqCurrent = f[1]?.int ?: 0
        val bands = dev.earshot.protocol.bragi.ProtoReader.repeated(body, 4).map { Hearing.PeqBand.parse(it) }
        if (f[5]?.bool == true) peqBands.clear()
        if (bands.isNotEmpty()) { peqBands.clear(); peqBands += bands }
        if (f[2]?.bool == true) peqEnabled = true
    }

    // ── authenticated_access (the headphones' side) ──────────────────────────────────────

    private fun handleAuth(p: BosRpcPacket): List<BosRpcPacket> {
        val a = p.address
        fun err(code: Int) = listOf(BosRpcPacket(BosMessageType.STATUS, a.service, a.method, p.tag, byteArrayOf(code.toByte())))
        fun final(payload: ByteArray) = listOf(reply(a, p.tag, payload, final = true), status(a, p.tag))
        val serverPoint = serverIdentity.publicPoint
        return when (a) {
            BragiServices.AUTH_HELLO -> {
                AuthMessages.parseClientHello(p.payload) ?: return err(ERR_BAD_REQUEST)
                final(AuthMessages.serverHello(listOf(BragiHandshake.identityKeyId(serverPoint))))
            }
            BragiServices.AUTH_TRUST_START -> {
                val start = AuthMessages.parseTrustStart(p.payload) ?: return err(ERR_BAD_REQUEST)
                if (runCatching { BragiHandshake.decodePublicKey(start.identityPoint) }.isFailure) return err(2) // IDENTITY_KEY_INVALID
                pairing = Pairing(start.identityPoint, start.passcodeDigits)
                final(AuthMessages.trustStartResponse(listOf(serverPoint)))
            }
            BragiServices.AUTH_TRUST_COMMIT -> {
                val pr = pairing ?: return err(12) // OPERATIONS_OUT_OF_ORDER
                pr.commitment = AuthMessages.parseTrustCommit(p.payload) ?: return err(ERR_BAD_REQUEST)
                pr.nonceServer = ByteArray(16).also(random::nextBytes)
                final(AuthMessages.nonce(pr.nonceServer!!))
            }
            BragiServices.AUTH_TRUST_NONCE -> {
                val pr = pairing ?: return err(12)
                val nonceClient = AuthMessages.parseNonce(p.payload) ?: return err(ERR_BAD_REQUEST)
                val clientX = BragiHandshake.pointX(pr.clientPoint)
                val serverX = BragiHandshake.pointX(serverPoint)
                if (!BragiHandshake.commitment(nonceClient, clientX, serverX).contentEquals(pr.commitment)) return err(6) // COMMITMENT_INVALID
                lastPasscode = BragiHandshake.passcode(clientX, serverX, nonceClient, pr.nonceServer!!, pr.digits)
                val dh = BragiHandshake.ecdh(serverIdentity.privateKey, pr.clientPoint)
                pr.expectedClientConfirmation = BragiHandshake.confirmation(dh, nonceClient, pr.nonceServer!!)
                // Non-final "success", then (once the user confirms on the headphones) the final confirmation.
                val ack = reply(a, p.tag, ByteArray(0), final = false)
                if (!autoConfirmOnDevice) listOf(ack)
                else listOf(ack, reply(a, p.tag, AuthMessages.confirmation(BragiHandshake.confirmation(dh, pr.nonceServer!!, nonceClient)), final = true), status(a, p.tag))
            }
            BragiServices.AUTH_TRUST_CONFIRM -> {
                val pr = pairing ?: return err(12)
                val theirs = AuthMessages.parseConfirmation(p.payload) ?: return err(ERR_BAD_REQUEST)
                if (!theirs.contentEquals(pr.expectedClientConfirmation)) { pairing = null; return err(7) } // CONFIRMATION_INVALID
                trustedClient = pr.clientPoint
                pairing = null
                listOf(status(a, p.tag))
            }
            BragiServices.AUTH_TRUST_ABORT -> { pairing = null; listOf(status(a, p.tag)) }
            BragiServices.AUTH_AUTHENTICATE -> authenticate(p)
            else -> err(UNKNOWN_METHOD.toInt())
        }
    }

    private fun authenticate(p: BosRpcPacket): List<BosRpcPacket> {
        val a = p.address
        fun err(code: Int) = listOf(BosRpcPacket(BosMessageType.STATUS, a.service, a.method, p.tag, byteArrayOf(code.toByte())))
        val req = AuthMessages.parseClientAuthenticate(p.payload) ?: return err(ERR_BAD_REQUEST)
        val trusted = trustedClient ?: return err(4) // IDENTITY_KEY_ID_NOT_TRUSTED
        if (!BragiHandshake.verifyAuthenticate(BragiHandshake.decodePublicKey(trusted), req.ephemeralPoint, req.random, req.signature)) return err(17) // SIGNATURE_INVALID
        val eph = BragiHandshake.generateEphemeral()
        val serverRandom = ByteArray(16).also(random::nextBytes)
        val channel = BragiHandshake.ChannelSpec(
            id = ChannelCipher.CHANNEL_BRAGI_CONTROL, transport = ChannelCipher.TRANSPORT_SPP, cipherSuite = channelSuite,
            keyLength = channelKeyLength, nonceLength = channelNonceLength, authTagLength = channelTagLength,
        )
        val signed = BragiHandshake.serverSignedMessage(req.ephemeralPoint, req.random, eph.publicPoint, serverRandom, listOf(channel))
        val signature = java.security.Signature.getInstance("SHA512withECDSA").run { initSign(serverIdentity.privateKey); update(signed); sign() }
        val secret = BragiHandshake.ecdh(eph.privateKey, req.ephemeralPoint)
        val (_, cipher) = BragiHandshake.deriveChannelCiphers(secret, req.random, serverRandom, listOf(channel)).first()
        secureCipher = cipher.asDeviceSide()
        sendSeq = 0
        // One channel: the first response is also the final one and carries every field.
        return listOf(reply(a, p.tag, AuthMessages.serverAuthenticate(eph.publicPoint, serverRandom, channel, signature), final = true), status(a, p.tag))
    }

    companion object {
        /** A virtual Skullcandy Method 360 ANC: Quiet / Aware / Off, sidetone and battery; no Crusher bass, no spatial audio. */
        fun method360() = VirtualCrusher(model = "T99_PLUS", modelNumber = "S2MTW", hasCrusherBass = false, hasSpatialAudio = false).also { it.batteryPercent = 74 }

        /** A virtual Skullcandy Method 540 ANC, the same surface under its own identity. */
        fun method540() = VirtualCrusher(model = "T120", modelNumber = "S2MFW", hasCrusherBass = false, hasSpatialAudio = false).also { it.batteryPercent = 81 }

        /** STATUS code for a protected call made without a secure session. */
        const val NOT_AUTHENTICATED: Byte = 0x0B
        private const val ERR_BAD_REQUEST = 0x60

        /** STATUS code for an unknown method. The real value is not confirmed; this only has to be non-zero. */
        const val UNKNOWN_METHOD: Byte = 0x60
    }
}
