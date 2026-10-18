package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.bragi.AuthMessages
import dev.earshot.protocol.bragi.BragiHandshake
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.ChannelCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.security.SecureRandom

/**
 * Where Earshot keeps the keys for the headphones' secure session. The identity key is this app's
 * long-term key (created on first use); a trusted server key is the headphones' identity key as
 * learned during the one-time pairing, remembered per Bluetooth address.
 */
interface BragiTrustStore {
    /** This app's long-term identity key pair; created on first use and then stable. */
    fun identity(): BragiHandshake.EcKeyPair

    /** The headphones' identity key (a SEC-1 point) trusted for [address], or null if never paired. */
    fun trustedServer(address: String): ByteArray?

    fun trust(address: String, serverPoint: ByteArray)

    /** Forget the pairing for [address]; the next secure session starts with a fresh trust establishment. */
    fun forget(address: String)
}

/** Shows the pairing passcode and asks the user whether it matches what the headphones announced. */
fun interface PairingPrompt {
    /**
     * [passcode] is the number both devices derive (null when [digits] is 0: just confirm). Return
     * true when the user says it matches, false to cancel the pairing.
     */
    suspend fun confirm(passcode: Int?, digits: Int): Boolean
}

/**
 * The client side of Bragi's `authenticated_access` service. Runs only when the headphones say
 * (`dev_info.authenticated_access`) that protected calls need a secure session.
 *
 *  1. `hello` exchanges identity-key ids.
 *  2. If the headphones' key is not trusted yet, a one-time **trust establishment** runs: both sides
 *     commit to nonces, derive a numeric passcode, and the user confirms it matches on the phone
 *     and on the headphones. This is what stops anyone else from taking control.
 *  3. `authenticate` runs an ECDHE exchange signed by both identities; the result is a per-session
 *     AEAD key that [install] hands to the connection.
 *
 * Everything is standard cryptography with keys generated and kept on this phone; nothing talks to
 * a server. The server's signature is verified (the official app skips that check, the protocol
 * requires it).
 */
class BragiSession(
    private val trust: BragiTrustStore,
    private val prompt: PairingPrompt,
    private val random: SecureRandom = SecureRandom(),
    private val passcodeDigits: Int = 6,
    private val userTimeoutMs: Long = USER_TIMEOUT_MS,
) {
    sealed interface Outcome {
        /** The secure session is up; the cipher was handed to [install]. [justPaired] = a new trust was set up. */
        data class Secure(val justPaired: Boolean) : Outcome

        /** The user declined the pairing passcode. */
        data object Declined : Outcome

        data class Failed(val reason: String) : Outcome
    }

    suspend fun establish(c: BragiRequester, address: String, install: (ChannelCipher) -> Unit): Outcome {
        return try {
            val identity = trust.identity()
            val hello = AuthMessages.parseServerHello(c.call(BragiServices.AUTH_HELLO, AuthMessages.clientHello(BragiHandshake.identityKeyId(identity.publicPoint))).payload)
            if (hello.isEmpty()) return Outcome.Failed("The headphones did not announce an identity key")
            var server = trust.trustedServer(address)
            var paired = false
            if (server == null || hello.none { it.contentEquals(BragiHandshake.identityKeyId(server!!)) }) {
                // Never paired (or the headphones were reset and have a new key): trust establishment.
                when (val r = pair(c, address, identity)) {
                    is Pairing.Trusted -> { server = r.serverPoint; paired = true }
                    Pairing.Declined -> return Outcome.Declined
                    is Pairing.Failed -> return Outcome.Failed(r.reason)
                }
            }
            authenticate(c, identity, server!!, install)?.let { return Outcome.Failed(it) }
            Outcome.Secure(justPaired = paired)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadphoneCommandException) {
            Outcome.Failed(e.message ?: "The secure session failed")
        } catch (e: java.security.GeneralSecurityException) {
            Outcome.Failed("The secure session failed (${e.message})")
        } catch (e: IllegalArgumentException) {
            Outcome.Failed("The headphones sent something unexpected (${e.message})")
        } catch (e: Exception) {
            // A Keystore or provider failure must not abort the whole connect: carry on without the session.
            Outcome.Failed("The secure session could not be set up (${e.message})")
        }
    }

    // ── Trust establishment ──────────────────────────────────────────────────────────────

    private sealed interface Pairing {
        class Trusted(val serverPoint: ByteArray) : Pairing
        data object Declined : Pairing
        class Failed(val reason: String) : Pairing
    }

    private suspend fun pair(c: BragiRequester, address: String, identity: BragiHandshake.EcKeyPair): Pairing = coroutineScope {
        val start = c.call(BragiServices.AUTH_TRUST_START, AuthMessages.trustStart(identity.publicPoint, passcodeDigits))
        val serverPoints = AuthMessages.parseTrustStartResponse(start.payload)
        // Headsets have one identity key; the first is the local one in any case.
        val serverPoint = serverPoints.firstOrNull() ?: return@coroutineScope Pairing.Failed("The headphones sent no identity key")
        BragiHandshake.decodePublicKey(serverPoint) // throws when the point is not on the curve
        val clientX = BragiHandshake.pointX(identity.publicPoint)
        val serverX = BragiHandshake.pointX(serverPoint)
        if (clientX.contentEquals(serverX)) return@coroutineScope abort(c, AuthMessages.Error.IDENTITY_KEY_INVALID, "The headphones reflected our key")
        val dhKey = BragiHandshake.ecdh(identity.privateKey, serverPoint)
        if (dhKey.all { it == 0.toByte() }) return@coroutineScope abort(c, AuthMessages.Error.IDENTITY_KEY_INVALID, "Invalid shared key")

        // Commit to our nonce before seeing theirs, so neither side can pick its nonce to steer the passcode.
        val nonceClient = ByteArray(16).also(random::nextBytes)
        val commit = c.call(BragiServices.AUTH_TRUST_COMMIT, AuthMessages.trustCommit(BragiHandshake.commitment(nonceClient, clientX, serverX)))
        val nonceServer = AuthMessages.parseNonce(commit.payload) ?: return@coroutineScope abort(c, AuthMessages.Error.CONFIRMATION_INVALID, "No nonce from the headphones")

        // The nonce call answers once the user has confirmed on the headphones, so it runs in the
        // background while the phone shows its own passcode; confirm/abort travel on their own calls.
        // The headphones must have our nonce before they can judge our confirmation, so wait until
        // the request is on the wire (the link keeps order) before anything else is sent.
        val nonceSent = CompletableDeferred<Unit>()
        val serverConfirmation = async {
            val r = c.call(BragiServices.AUTH_TRUST_NONCE, AuthMessages.nonce(nonceClient), timeoutMs = userTimeoutMs, onSent = { nonceSent.complete(Unit) })
            r.payloads.mapNotNull { AuthMessages.parseConfirmation(it) }.lastOrNull()
        }
        nonceSent.await()
        val passcode = if (passcodeDigits == 0) null
        else BragiHandshake.passcode(clientX, serverX, nonceClient, nonceServer, passcodeDigits)
        val accepted = prompt.confirm(passcode, passcodeDigits)
        if (!accepted) {
            serverConfirmation.cancel()
            runCatching { c.call(BragiServices.AUTH_TRUST_ABORT, AuthMessages.trustAbort(AuthMessages.Error.PASSCODE_REJECTED)) }
            return@coroutineScope Pairing.Declined
        }
        c.call(BragiServices.AUTH_TRUST_CONFIRM, AuthMessages.confirmation(BragiHandshake.confirmation(dhKey, nonceClient, nonceServer)))
        val theirs = serverConfirmation.await() ?: return@coroutineScope abort(c, AuthMessages.Error.CONFIRMATION_TIMEOUT, "The headphones did not confirm")
        val expected = BragiHandshake.confirmation(dhKey, nonceServer, nonceClient)
        if (!expected.contentEquals(theirs)) return@coroutineScope abort(c, AuthMessages.Error.CONFIRMATION_INVALID, "The headphones' confirmation did not match")
        trust.trust(address, serverPoint)
        Pairing.Trusted(serverPoint)
    }

    private suspend fun abort(c: BragiRequester, reason: Int, message: String): Pairing {
        runCatching { c.call(BragiServices.AUTH_TRUST_ABORT, AuthMessages.trustAbort(reason)) }
        return Pairing.Failed(message)
    }

    // ── Session ──────────────────────────────────────────────────────────────────────────

    /** Returns null on success, else why it failed. */
    private suspend fun authenticate(
        c: BragiRequester, identity: BragiHandshake.EcKeyPair, serverIdentity: ByteArray, install: (ChannelCipher) -> Unit,
    ): String? {
        val eph = BragiHandshake.generateEphemeral()
        val clientRandom = ByteArray(16).also(random::nextBytes)
        val sig = BragiHandshake.signAuthenticate(identity.privateKey, eph.publicPoint, clientRandom)
        val reply = c.call(BragiServices.AUTH_AUTHENTICATE, AuthMessages.clientAuthenticate(eph.publicPoint, clientRandom, sig))
        // The answer is streamed: ephemeral key + random (+ a channel), more channels, then the signature.
        val parts = reply.payloads.map { AuthMessages.parseServerAuthenticate(it) }
        val serverEph = parts.firstNotNullOfOrNull { it.ephemeralPoint } ?: return "The headphones sent no session key"
        val serverRandom = parts.firstNotNullOfOrNull { it.random } ?: return "The headphones sent no session nonce"
        val channels = parts.mapNotNull { it.channel }
        val signature = parts.lastOrNull { it.signature != null }?.signature ?: return "The headphones did not sign the session"
        val signed = BragiHandshake.serverSignedMessage(eph.publicPoint, clientRandom, serverEph, serverRandom, channels)
        if (!BragiHandshake.verifyServerAuthenticate(BragiHandshake.decodePublicKey(serverIdentity), signed, signature)) {
            return "The headphones' signature was invalid"
        }
        val secret = BragiHandshake.ecdh(eph.privateKey, serverEph)
        val ciphers = BragiHandshake.deriveChannelCiphers(secret, clientRandom, serverRandom, channels)
        val control = ciphers.firstOrNull { (spec, _) -> spec.id == ChannelCipher.CHANNEL_BRAGI_CONTROL && spec.transport == ChannelCipher.TRANSPORT_SPP }
            ?: ciphers.firstOrNull { (spec, _) -> spec.id == ChannelCipher.CHANNEL_BRAGI_CONTROL }
            ?: return "The headphones offered no control channel"
        install(control.second)
        return null
    }

    companion object {
        /** Pairing waits for a person: confirming on the headphones can take a while. */
        const val USER_TIMEOUT_MS = 120_000L
    }
}
