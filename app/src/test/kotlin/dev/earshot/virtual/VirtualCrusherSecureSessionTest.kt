package dev.earshot.virtual

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiSession
import dev.earshot.bt.BragiTrustStore
import dev.earshot.bt.BtLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SecureSessionStatus
import dev.earshot.protocol.bragi.BragiHandshake
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.Hearing
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.ProtocolLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The secure session end to end: [BragiSession] (phone side) against [VirtualCrusher] (headphone
 * side) over the real connection, framing and encryption. A passing run means the pairing
 * passcode, the HMAC commitments, both signatures, the key schedule and the AEAD framing agree
 * between two independent implementations of the written protocol.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualCrusherSecureSessionTest {

    private class MemoryTrust : BragiTrustStore {
        private val id by lazy { BragiHandshake.generateIdentity() }
        private val servers = HashMap<String, ByteArray>()
        override fun identity() = id
        override fun trustedServer(address: String) = servers[address.uppercase()]
        override fun trust(address: String, serverPoint: ByteArray) { servers[address.uppercase()] = serverPoint }
        override fun forget(address: String) { servers.remove(address.uppercase()) }
    }

    @Before
    fun silence() {
        BtLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    private val address = "AA:BB:CC:00:11:22"

    private fun TestScope.connect(device: VirtualCrusher): BragiConnection =
        BragiConnection(VirtualCrusherTransport(device), backgroundScope, clock = { testScheduler.currentTime })

    private suspend fun onConnected(
        conn: BragiConnection, trust: MemoryTrust, userAnswer: (Int?) -> Boolean = { true }, shown: MutableList<Int?> = ArrayList(),
        userTimeoutMs: Long = BragiSession.USER_TIMEOUT_MS,
    ): Pair<HeadphoneState, BragiSession.Outcome?> {
        var outcome: BragiSession.Outcome? = null
        val session = BragiSession(trust, prompt = { passcode, _ -> shown += passcode; userAnswer(passcode) }, userTimeoutMs = userTimeoutMs)
        val s = CrusherProtocol().onConnected(conn, HeadphoneState(), secure = {
            session.establish(conn, address) { conn.installCipher(it) }.also { outcome = it }
        })
        return s to outcome
    }

    @Test
    fun `protected controls are refused before a session and the dev_info flag says so`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val conn = connect(device)
        val e = assertFailsWith<HeadphoneCommandException> { conn.call(BragiServices.MODE_READ, Hearing.modeRead(false), streaming = true) }
        assertTrue("rejected" in e.message.orEmpty(), e.message)
        // dev_info is open and carries the gate.
        val info = dev.earshot.protocol.bragi.DeviceInfo.parseDevInfo(conn.call(BragiServices.DEV_INFO, dev.earshot.protocol.bragi.DeviceInfo.devInfo()).payload)
        assertTrue(info.authenticatedAccess)
    }

    @Test
    fun `first connect pairs with a matching passcode and then controls work encrypted`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val trust = MemoryTrust()
        val shown = ArrayList<Int?>()
        val (s, outcome) = onConnected(connect(device), trust, shown = shown)

        assertEquals(BragiSession.Outcome.Secure(justPaired = true), outcome)
        assertEquals(SecureSessionStatus.SECURE, s.secureSession.status)
        // The passcode the phone showed is the one the headphones derived.
        assertEquals(1, shown.size)
        assertNotNull(shown.single())
        assertEquals(device.lastPasscode, shown.single())
        assertTrue(shown.single()!! in 0..999_999)
        // Protected reads worked: they could only have gone through the encrypted channel.
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertEquals(5, s.crusher?.intensity)
        assertNotNull(device.trustedClient)
        assertNotNull(trust.trustedServer(address))
    }

    @Test
    fun `commands after pairing are sealed and the device applies them`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val trust = MemoryTrust()
        val conn = connect(device)
        val (s, _) = onConnected(conn, trust)
        val protocol = CrusherProtocol()
        val after = protocol.setMode(conn, s, 1) // Aware
        assertEquals(HearingMode.AT, device.hearingMode)
        assertEquals(1, after.currentModeIndex)
    }

    @Test
    fun `a second connection reuses the trust without asking the user again`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val trust = MemoryTrust()
        onConnected(connect(device), trust) // pairs

        val shown = ArrayList<Int?>()
        val (s, outcome) = onConnected(connect(device), trust, shown = shown)
        assertEquals(BragiSession.Outcome.Secure(justPaired = false), outcome)
        assertTrue(shown.isEmpty(), "the user must not be asked again")
        assertEquals(SecureSessionStatus.SECURE, s.secureSession.status)
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
    }

    @Test
    fun `declining the passcode cancels the pairing and nothing is trusted`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val trust = MemoryTrust()
        val (s, outcome) = onConnected(connect(device), trust, userAnswer = { false })
        assertEquals(BragiSession.Outcome.Declined, outcome)
        assertEquals(SecureSessionStatus.DECLINED, s.secureSession.status)
        assertNull(device.trustedClient)
        assertNull(trust.trustedServer(address))
        assertTrue(s.modes.isEmpty(), "protected reads stay refused without a session")
    }

    @Test
    fun `forgetting the pairing makes the next connect pair again`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true)
        val trust = MemoryTrust()
        onConnected(connect(device), trust)
        trust.forget(address)
        val shown = ArrayList<Int?>()
        val (_, outcome) = onConnected(connect(device), trust, shown = shown)
        assertEquals(BragiSession.Outcome.Secure(justPaired = true), outcome)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a headphone that never confirms times out instead of hanging`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = true, autoConfirmOnDevice = false)
        val trust = MemoryTrust()
        val (s, outcome) = onConnected(connect(device), trust, userTimeoutMs = 2_000)
        assertTrue(outcome is BragiSession.Outcome.Failed, outcome.toString())
        assertEquals(SecureSessionStatus.FAILED, s.secureSession.status)
        assertNull(trust.trustedServer(address))
    }

    private fun secureRoundTrip(suite: Int, key: Int, nonce: Int, tag: Int) = runTest {
        val device = VirtualCrusher(authenticatedAccess = true, channelSuite = suite, channelKeyLength = key, channelNonceLength = nonce, channelTagLength = tag)
        val conn = connect(device)
        val (s, outcome) = onConnected(conn, MemoryTrust())
        assertEquals(BragiSession.Outcome.Secure(justPaired = true), outcome, "suite $suite key $key nonce $nonce tag $tag")
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        val after = CrusherProtocol().setMode(conn, s, 1)
        assertEquals(HearingMode.AT, device.hearingMode)
        assertEquals(1, after.currentModeIndex)
    }

    @Test fun `the session works with AES-CCM`() = secureRoundTrip(suite = 1, key = 16, nonce = 12, tag = 16)

    @Test fun `the session works with AES-CCM and the short nonce and tag`() = secureRoundTrip(suite = 1, key = 16, nonce = 8, tag = 8)

    @Test fun `the session works with AES-GCM and a 256-bit key`() = secureRoundTrip(suite = 2, key = 32, nonce = 12, tag = 16)

    @Test fun `the session works with ChaCha20-Poly1305`() = secureRoundTrip(suite = 3, key = 32, nonce = 12, tag = 16)

    @Test
    fun `headphones that need no session skip the handshake entirely`() = runTest {
        val device = VirtualCrusher(authenticatedAccess = false)
        val conn = connect(device)
        var called = false
        val s = CrusherProtocol().onConnected(conn, HeadphoneState(), secure = { called = true; BragiSession.Outcome.Secure(false) })
        assertTrue(!called)
        assertEquals(SecureSessionStatus.NOT_REQUIRED, s.secureSession.status)
        assertEquals(3, s.modes.size)
    }
}
